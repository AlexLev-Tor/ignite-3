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

package org.apache.ignite.internal.storage.rocksdb;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static org.apache.ignite.internal.storage.rocksdb.RocksDbStorageUtils.KEY_BYTE_ORDER;
import static org.apache.ignite.internal.storage.rocksdb.RocksDbStorageUtils.ROW_ID_SIZE;
import static org.apache.ignite.internal.storage.rocksdb.RocksDbStorageUtils.getRowIdUuid;
import static org.apache.ignite.internal.storage.rocksdb.RocksDbStorageUtils.putRowIdUuid;
import static org.apache.ignite.internal.util.ArrayUtils.BYTE_EMPTY_ARRAY;

import java.nio.ByteBuffer;
import org.apache.ignite.internal.rocksdb.ColumnFamily;
import org.apache.ignite.internal.storage.RowId;
import org.apache.ignite.internal.storage.StorageException;
import org.jetbrains.annotations.Nullable;
import org.rocksdb.AbstractWriteBatch;
import org.rocksdb.RocksDBException;

/**
 * Wrapper around the "meta" Column Family inside a RocksDB-based storage, which stores some auxiliary information needed for internal
 * storage logic.
 */
public class RocksDbMetaStorage {
    /**
     * Prefix to store partition meta information, such as last applied index and term.
     * Key format is {@code [prexif, tableId, partitionId]} in BE.
     */
    public static final byte[] PARTITION_META_PREFIX = {0};

    /**
     * Prefix to store partition configuration. Key format is {@code [prexif, tableId, partitionId]} in BE.
     */
    public static final byte[] PARTITION_CONF_PREFIX = {1};

    /**
     * Prefix to store index column family name. Key format is {@code [prexif, indexId]} in BE.
     */
    public static final byte[] INDEX_CF_PREFIX = {2};

    /**
     * Prefix to store next row id to build in index. Key format is {@code [prexif, indexId, partitionId]} in BE.
     */
    public static final byte[] INDEX_ROW_ID_PREFIX = {3};

    private final ColumnFamily metaColumnFamily;

    public RocksDbMetaStorage(ColumnFamily metaColumnFamily) {
        this.metaColumnFamily = metaColumnFamily;
    }

    /**
     * Creates a byte array, that uses the {@code prefix} as a prefix, and every other {@code int} values as a 4-bytes chunk in Big Endian.
     */
    public static byte[] createKey(byte[] prefix, int... values) {
        ByteBuffer buf = ByteBuffer.allocate(prefix.length + Integer.BYTES * values.length).order(BIG_ENDIAN);

        buf.put(prefix);

        for (int value : values) {
            buf.putInt(value);
        }

        return buf.array();
    }

    /**
     * Returns a column family instance, associated with the meta storage.
     */
    public ColumnFamily columnFamily() {
        return metaColumnFamily;
    }

    /**
     * Returns the row ID for which the index needs to be built, {@code null} means that the index building has completed.
     *
     * @param indexId Index ID.
     * @param partitionId Partition ID.
     * @param ifAbsent Will be returned if next the row ID for which the index needs to be built has never been saved.
     */
    public @Nullable RowId getNextRowIdToBuilt(int indexId, int partitionId, RowId ifAbsent) {
        try {
            byte[] lastBuiltRowIdBytes = metaColumnFamily.get(createKey(INDEX_ROW_ID_PREFIX, indexId, partitionId));

            if (lastBuiltRowIdBytes == null) {
                return ifAbsent;
            }

            if (lastBuiltRowIdBytes.length == 0) {
                return null;
            }

            return new RowId(partitionId, getRowIdUuid(ByteBuffer.wrap(lastBuiltRowIdBytes), 0));
        } catch (RocksDBException e) {
            throw new StorageException(
                    "Failed to read next row ID to built: [partitionId={}, indexId={}]",
                    e,
                    partitionId, indexId
            );
        }
    }

    /**
     * Puts row ID for which the index needs to be built, {@code null} means index building is finished.
     *
     * @param writeBatch Write batch.
     * @param partitionId Partition ID.
     * @param indexId Index ID.
     * @param rowId Row ID.
     */
    public void putNextRowIdToBuilt(AbstractWriteBatch writeBatch, int indexId, int partitionId, @Nullable RowId rowId) {
        try {
            writeBatch.put(metaColumnFamily.handle(), createKey(INDEX_ROW_ID_PREFIX, indexId, partitionId), indexLastBuildRowId(rowId));
        } catch (RocksDBException e) {
            throw new StorageException(
                    "Failed to save next row ID to built: [partitionId={}, indexId={}, rowId={}]",
                    e,
                    partitionId, indexId, rowId
            );
        }
    }

    private static byte[] indexLastBuildRowId(@Nullable RowId rowId) {
        if (rowId == null) {
            return BYTE_EMPTY_ARRAY;
        }

        ByteBuffer buffer = ByteBuffer.allocate(ROW_ID_SIZE).order(KEY_BYTE_ORDER);

        putRowIdUuid(buffer, rowId.uuid());

        return buffer.array();
    }
}
