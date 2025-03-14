/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.storage.impl;

import static java.util.Comparator.comparing;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import org.apache.ignite.internal.hlc.HybridTimestamp;
import org.apache.ignite.internal.schema.BinaryRow;
import org.apache.ignite.internal.storage.MvPartitionStorage;
import org.apache.ignite.internal.storage.PartitionTimestampCursor;
import org.apache.ignite.internal.storage.ReadResult;
import org.apache.ignite.internal.storage.RowId;
import org.apache.ignite.internal.storage.StorageClosedException;
import org.apache.ignite.internal.storage.StorageException;
import org.apache.ignite.internal.storage.StorageRebalanceException;
import org.apache.ignite.internal.storage.TxIdMismatchException;
import org.apache.ignite.internal.storage.gc.GcEntry;
import org.apache.ignite.internal.storage.util.LocalLocker;
import org.apache.ignite.internal.storage.util.LockByRowId;
import org.apache.ignite.internal.util.Cursor;
import org.jetbrains.annotations.Nullable;

/**
 * Test implementation of MV partition storage.
 */
public class TestMvPartitionStorage implements MvPartitionStorage {
    /** Preserved {@link LocalLocker} instance to allow nested calls of {@link #runConsistently(WriteClosure)}. */
    private static final ThreadLocal<LocalLocker> THREAD_LOCAL_LOCKER = new ThreadLocal<>();

    private final ConcurrentNavigableMap<RowId, VersionChain> map = new ConcurrentSkipListMap<>();

    private final NavigableSet<VersionChain> gcQueue = new ConcurrentSkipListSet<>(
            comparing((VersionChain chain) -> chain.ts)
                    .thenComparing(chain -> chain.rowId)
    );

    private volatile long lastAppliedIndex;

    private volatile long lastAppliedTerm;

    private volatile byte @Nullable [] groupConfig;

    final int partitionId;

    private volatile boolean closed;

    private volatile boolean rebalance;

    final LockByRowId lockByRowId = new LockByRowId();

    public TestMvPartitionStorage(int partitionId) {
        this.partitionId = partitionId;
    }

    private static class VersionChain implements GcEntry {
        final RowId rowId;
        final @Nullable BinaryRow row;
        final @Nullable HybridTimestamp ts;
        final @Nullable UUID txId;
        final @Nullable Integer commitTableId;
        final int commitPartitionId;
        volatile @Nullable VersionChain next;

        VersionChain(
                RowId rowId,
                @Nullable BinaryRow row,
                @Nullable HybridTimestamp ts,
                @Nullable UUID txId,
                @Nullable Integer commitTableId,
                int commitPartitionId,
                @Nullable VersionChain next
        ) {
            this.rowId = rowId;
            this.row = row;
            this.ts = ts;
            this.txId = txId;
            this.commitTableId = commitTableId;
            this.commitPartitionId = commitPartitionId;
            this.next = next;
        }

        static VersionChain forWriteIntent(RowId rowId, @Nullable BinaryRow row, @Nullable UUID txId, @Nullable Integer commitTableId,
                int commitPartitionId, @Nullable VersionChain next) {
            return new VersionChain(rowId, row, null, txId, commitTableId, commitPartitionId, next);
        }

        static VersionChain forCommitted(RowId rowId, @Nullable HybridTimestamp timestamp, VersionChain uncommittedVersionChain) {
            return new VersionChain(rowId, uncommittedVersionChain.row, timestamp, null, null,
                    ReadResult.UNDEFINED_COMMIT_PARTITION_ID, uncommittedVersionChain.next);
        }

        boolean isWriteIntent() {
            return ts == null && txId != null;
        }

        @Override
        public RowId getRowId() {
            return rowId;
        }

        @Override
        public HybridTimestamp getTimestamp() {
            assert ts != null : "Method should only be invoked for instances with non-null timestamps.";

            return ts;
        }
    }

    @Override
    public <V> V runConsistently(WriteClosure<V> closure) throws StorageException {
        checkStorageClosed();

        LocalLocker locker = THREAD_LOCAL_LOCKER.get();

        if (locker != null) {
            return closure.execute(locker);
        } else {
            locker = new LocalLocker(lockByRowId);

            THREAD_LOCAL_LOCKER.set(locker);

            try {
                return closure.execute(locker);
            } finally {
                THREAD_LOCAL_LOCKER.set(null);

                locker.unlockAll();
            }
        }
    }

    @Override
    public CompletableFuture<Void> flush() {
        checkStorageClosed();

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public long lastAppliedIndex() {
        checkStorageClosed();

        return lastAppliedIndex;
    }

    @Override
    public long lastAppliedTerm() {
        checkStorageClosed();

        return lastAppliedTerm;
    }

    @Override
    public void lastApplied(long lastAppliedIndex, long lastAppliedTerm) throws StorageException {
        checkStorageClosedOrInProcessOfRebalance();

        this.lastAppliedIndex = lastAppliedIndex;
        this.lastAppliedTerm = lastAppliedTerm;
    }

    @Override
    public byte @Nullable [] committedGroupConfiguration() {
        checkStorageClosed();

        byte[] currentConfig = groupConfig;
        return currentConfig == null ? null : Arrays.copyOf(currentConfig, currentConfig.length);
    }

    @Override
    public void committedGroupConfiguration(byte[] config) {
        checkStorageClosedOrInProcessOfRebalance();

        this.groupConfig = Arrays.copyOf(config, config.length);
    }

    @Override
    public synchronized @Nullable BinaryRow addWrite(
            RowId rowId,
            @Nullable BinaryRow row,
            UUID txId,
            int commitTableId,
            int commitPartitionId
    ) throws TxIdMismatchException {
        checkStorageClosed();

        BinaryRow[] res = {null};

        map.compute(rowId, (ignored, versionChain) -> {
            if (versionChain != null && versionChain.ts == null) {
                if (!txId.equals(versionChain.txId)) {
                    throw new TxIdMismatchException(txId, versionChain.txId);
                }

                res[0] = versionChain.row;

                return VersionChain.forWriteIntent(rowId, row, txId, commitTableId, commitPartitionId, versionChain.next);
            }

            return VersionChain.forWriteIntent(rowId, row, txId, commitTableId, commitPartitionId, versionChain);
        });

        return res[0];
    }

    @Override
    public synchronized @Nullable BinaryRow abortWrite(RowId rowId) {
        checkStorageClosedOrInProcessOfRebalance();

        BinaryRow[] res = {null};

        map.computeIfPresent(rowId, (ignored, versionChain) -> {
            if (!versionChain.isWriteIntent()) {
                return versionChain;
            }

            assert versionChain.ts == null;

            res[0] = versionChain.row;

            return versionChain.next;
        });

        return res[0];
    }

    @Override
    public synchronized void commitWrite(RowId rowId, HybridTimestamp timestamp) {
        checkStorageClosed();

        map.compute(rowId, (ignored, versionChain) -> {
            assert versionChain != null;

            if (!versionChain.isWriteIntent()) {
                return versionChain;
            }

            return resolveCommittedVersionChain(VersionChain.forCommitted(rowId, timestamp, versionChain));
        });
    }

    @Override
    public synchronized void addWriteCommitted(
            RowId rowId,
            @Nullable BinaryRow row,
            HybridTimestamp commitTimestamp
    ) throws StorageException {
        checkStorageClosed();

        map.compute(rowId, (ignored, versionChain) -> {
            if (versionChain != null && versionChain.isWriteIntent()) {
                throw new StorageException("Write intent exists for " + rowId);
            }

            return resolveCommittedVersionChain(new VersionChain(
                    rowId,
                    row,
                    commitTimestamp,
                    null,
                    null,
                    ReadResult.UNDEFINED_COMMIT_PARTITION_ID,
                    versionChain
            ));
        });
    }

    private @Nullable VersionChain resolveCommittedVersionChain(VersionChain committedVersionChain) {
        VersionChain nextChain = committedVersionChain.next;

        if (nextChain != null) {
            // Avoid creating tombstones for tombstones.
            if (committedVersionChain.row == null && nextChain.row == null) {
                return nextChain;
            }

            // Calling it from the compute is fine. Concurrent writes of the same row are impossible, and if we call the compute closure
            // several times, the same tuple will be inserted into the GC queue (timestamp and rowId don't change in this case).
            gcQueue.add(committedVersionChain);
        } else {
            if (committedVersionChain.row == null) {
                // If there is only one version, and it is a tombstone, then remove the chain.
                return null;
            }
        }

        return committedVersionChain;
    }

    @Override
    public ReadResult read(RowId rowId, @Nullable HybridTimestamp timestamp) {
        checkStorageClosedOrInProcessOfRebalance();

        if (rowId.partitionId() != partitionId) {
            throw new IllegalArgumentException(
                    String.format("RowId partition [%d] is not equal to storage partition [%d].", rowId.partitionId(), partitionId));
        }

        VersionChain versionChain = map.get(rowId);

        if (versionChain == null) {
            return ReadResult.empty(rowId);
        }

        return read(versionChain, timestamp, null);
    }

    /**
     * Reads the value from the version chain using either transaction id or timestamp.
     *
     * @param versionChain Version chain.
     * @param timestamp Timestamp or {@code null} if transaction id is defined.
     * @param txId Transaction id or {@code null} if timestamp is defined.
     * @return Read result.
     */
    private static ReadResult read(
            VersionChain versionChain,
            @Nullable HybridTimestamp timestamp,
            @Nullable UUID txId
    ) {
        assert timestamp == null ^ txId == null;

        if (timestamp == null) {
            // Search by transaction id.

            if (versionChain.txId != null && !versionChain.txId.equals(txId)) {
                throw new TxIdMismatchException(txId, versionChain.txId);
            }

            return versionChainToReadResult(versionChain, true);
        }

        VersionChain cur = versionChain;

        if (cur.isWriteIntent()) {
            // We have a write-intent.
            if (cur.next == null) {
                // We *only* have a write-intent, return it.
                BinaryRow binaryRow = cur.row;

                return ReadResult.createFromWriteIntent(cur.rowId, binaryRow, cur.txId, cur.commitTableId, cur.commitPartitionId, null);
            }

            // Move to first commit.
            cur = cur.next;
        }

        return walkVersionChain(versionChain, timestamp, cur);
    }

    private static ReadResult versionChainToReadResult(VersionChain versionChain, boolean fillLastCommittedTs) {
        if (versionChain.isWriteIntent()) {
            VersionChain next = versionChain.next;

            return ReadResult.createFromWriteIntent(
                    versionChain.rowId,
                    versionChain.row,
                    versionChain.txId,
                    versionChain.commitTableId,
                    versionChain.commitPartitionId, fillLastCommittedTs && next != null ? next.ts : null
            );
        }

        return ReadResult.createFromCommitted(versionChain.rowId, versionChain.row, versionChain.ts);
    }

    /**
     * Walks version chain to find a row by timestamp. See {@link MvPartitionStorage#read(RowId, HybridTimestamp)} for details.
     *
     * @param chainHead Version chain head.
     * @param timestamp Timestamp.
     * @param firstCommit First commit chain element.
     * @return Read result.
     */
    private static ReadResult walkVersionChain(VersionChain chainHead, HybridTimestamp timestamp, VersionChain firstCommit) {
        boolean hasWriteIntent = chainHead.ts == null;

        if (hasWriteIntent && timestamp.compareTo(firstCommit.ts) > 0) {
            // It's the latest commit in chain, query ts is greater than commit ts and there is a write-intent.
            // So we just return write-intent.
            BinaryRow binaryRow = chainHead.row;

            return ReadResult.createFromWriteIntent(
                    chainHead.rowId,
                    binaryRow,
                    chainHead.txId,
                    chainHead.commitTableId,
                    chainHead.commitPartitionId,
                    firstCommit.ts);
        }

        VersionChain cur = firstCommit;

        while (cur != null) {
            assert cur.ts != null;

            if (timestamp.compareTo(cur.ts) >= 0) {
                // This commit has timestamp matching the query ts, meaning that commit is the one we are looking for.
                BinaryRow binaryRow = cur.row;

                return ReadResult.createFromCommitted(cur.rowId, binaryRow, cur.ts);
            }

            cur = cur.next;
        }

        return ReadResult.empty(chainHead.rowId);
    }

    @Override
    public Cursor<ReadResult> scanVersions(RowId rowId) throws StorageException {
        checkStorageClosedOrInProcessOfRebalance();

        return new ScanVersionsCursor(rowId);
    }

    @Override
    public PartitionTimestampCursor scan(HybridTimestamp timestamp) {
        checkStorageClosedOrInProcessOfRebalance();

        Iterator<VersionChain> iterator = map.values().iterator();

        return new PartitionTimestampCursor() {
            @Nullable
            private VersionChain currentChain;

            @Nullable
            private ReadResult currentReadResult;

            @Override
            public @Nullable BinaryRow committed(HybridTimestamp timestamp) {
                if (currentChain == null) {
                    throw new IllegalStateException();
                }

                // We don't check if row conforms the key filter here, because we've already checked it.
                ReadResult read = read(currentChain, timestamp, null);

                if (read.transactionId() == null) {
                    return read.binaryRow();
                }

                return null;
            }

            @Override
            public void close() {
                // No-op.
            }

            @Override
            public boolean hasNext() {
                checkStorageClosedOrInProcessOfRebalance();

                if (currentReadResult != null) {
                    return true;
                }

                currentChain = null;

                while (iterator.hasNext()) {
                    VersionChain chain = iterator.next();
                    ReadResult readResult = read(chain, timestamp, null);

                    if (!readResult.isEmpty() || readResult.isWriteIntent()) {
                        currentChain = chain;
                        currentReadResult = readResult;

                        return true;
                    }
                }

                return false;
            }

            @Override
            public ReadResult next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }

                ReadResult res = currentReadResult;

                assert res != null;

                currentReadResult = null;

                return res;
            }
        };
    }

    @Override
    public @Nullable RowId closestRowId(RowId lowerBound) throws StorageException {
        checkStorageClosedOrInProcessOfRebalance();

        return map.ceilingKey(lowerBound);
    }

    @Override
    public synchronized @Nullable GcEntry peek(HybridTimestamp lowWatermark) {
        assert THREAD_LOCAL_LOCKER.get() != null;

        try {
            VersionChain versionChain = gcQueue.first();

            if (versionChain.ts.compareTo(lowWatermark) > 0) {
                return null;
            }

            return versionChain;
        } catch (NoSuchElementException e) {
            return null;
        }
    }

    @Override
    public synchronized @Nullable BinaryRow vacuum(GcEntry entry) {
        assert THREAD_LOCAL_LOCKER.get() != null;
        assert THREAD_LOCAL_LOCKER.get().isLocked(entry.getRowId());

        checkStorageClosedOrInProcessOfRebalance();

        VersionChain dequeuedVersionChain;

        try {
            dequeuedVersionChain = gcQueue.first();
        } catch (NoSuchElementException e) {
            return null;
        }

        if (dequeuedVersionChain != entry) {
            return null;
        }

        RowId rowId = dequeuedVersionChain.rowId;

        VersionChain versionChainToRemove = dequeuedVersionChain.next;
        assert versionChainToRemove != null;
        assert versionChainToRemove.next == null;

        dequeuedVersionChain.next = null;
        gcQueue.remove(dequeuedVersionChain);

        // Tombstones must be deleted.
        if (dequeuedVersionChain.row == null) {
            map.compute(rowId, (ignored, head) -> {
                if (head == dequeuedVersionChain) {
                    return null;
                }

                for (VersionChain cur = head; cur != null; cur = cur.next) {
                    if (cur.next == dequeuedVersionChain) {
                        cur.next = null;

                        gcQueue.remove(cur);
                    }
                }

                return head;
            });
        }

        return versionChainToRemove.row;
    }

    @Override
    public long rowsCount() {
        checkStorageClosedOrInProcessOfRebalance();

        return map.size();
    }

    @Override
    public void close() {
        closed = true;

        clear0();
    }

    public void destroy() {
        close();
    }

    /** Removes all entries from this storage. */
    public synchronized void clear() {
        checkStorageClosedOrInProcessOfRebalance();

        clear0();
    }

    private synchronized void clear0() {
        map.clear();

        gcQueue.clear();

        lastAppliedIndex = 0;
        lastAppliedTerm = 0;
        groupConfig = null;
    }

    private void checkStorageClosed() {
        if (closed) {
            throw new StorageClosedException();
        }
    }

    private void checkStorageClosedForRebalance() {
        if (closed) {
            throw new StorageRebalanceException();
        }
    }

    private void checkStorageInProcessOfRebalance() {
        if (rebalance) {
            throw new StorageRebalanceException();
        }
    }

    private void checkStorageClosedOrInProcessOfRebalance() {
        checkStorageClosed();
        checkStorageInProcessOfRebalance();
    }

    void startRebalance() {
        checkStorageClosedForRebalance();

        rebalance = true;

        clear0();

        lastAppliedIndex = REBALANCE_IN_PROGRESS;
        lastAppliedTerm = REBALANCE_IN_PROGRESS;

        groupConfig = null;
    }

    void abortRebalance() {
        checkStorageClosedForRebalance();

        if (!rebalance) {
            return;
        }

        rebalance = false;

        clear0();

        lastAppliedIndex = 0;
        lastAppliedTerm = 0;

        groupConfig = null;
    }

    void finishRebalance(long lastAppliedIndex, long lastAppliedTerm, byte[] groupConfig) {
        checkStorageClosedForRebalance();

        assert rebalance;

        rebalance = false;

        this.lastAppliedIndex = lastAppliedIndex;
        this.lastAppliedTerm = lastAppliedTerm;
        this.groupConfig = Arrays.copyOf(groupConfig, groupConfig.length);
    }

    boolean closed() {
        return closed;
    }

    private class ScanVersionsCursor implements Cursor<ReadResult> {
        private final RowId rowId;

        @Nullable
        private Boolean hasNext;

        @Nullable
        private VersionChain versionChain;

        private ScanVersionsCursor(RowId rowId) {
            this.rowId = rowId;
        }

        @Override
        public void close() {
            // No-op.
        }

        @Override
        public boolean hasNext() {
            advanceIfNeeded();

            return hasNext;
        }

        @Override
        public ReadResult next() {
            advanceIfNeeded();

            if (!hasNext) {
                throw new NoSuchElementException();
            }

            hasNext = null;

            return versionChainToReadResult(versionChain, false);
        }

        private void advanceIfNeeded() {
            checkStorageClosedOrInProcessOfRebalance();

            if (hasNext != null) {
                return;
            }

            versionChain = versionChain == null ? map.get(rowId) : versionChain.next;

            hasNext = versionChain != null;
        }
    }
}
