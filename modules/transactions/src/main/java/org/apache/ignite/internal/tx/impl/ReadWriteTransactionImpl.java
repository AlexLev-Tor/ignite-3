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

package org.apache.ignite.internal.tx.impl;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.apache.ignite.internal.tx.TxState.ABORTED;
import static org.apache.ignite.internal.tx.TxState.COMMITED;
import static org.apache.ignite.internal.tx.TxState.PENDING;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ignite.internal.hlc.HybridTimestamp;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.logger.Loggers;
import org.apache.ignite.internal.replicator.TablePartitionId;
import org.apache.ignite.internal.tx.InternalTransaction;
import org.apache.ignite.internal.tx.TransactionIds;
import org.apache.ignite.internal.tx.TxManager;
import org.apache.ignite.lang.IgniteBiTuple;
import org.apache.ignite.network.ClusterNode;
import org.jetbrains.annotations.NotNull;

/**
 * The read-write implementation of an internal transaction.
 */
public class ReadWriteTransactionImpl extends IgniteAbstractTransactionImpl {
    private static final IgniteLogger LOG = Loggers.forClass(InternalTransaction.class);

    /** Enlisted partitions: partition id -> (primary replica node, raft term). */
    private final Map<TablePartitionId, IgniteBiTuple<ClusterNode, Long>> enlisted = new ConcurrentHashMap<>();

    /** Enlisted operation futures in this transaction. */
    private final List<CompletableFuture<?>> enlistedResults = new CopyOnWriteArrayList<>();

    /** Reference to the partition that stores the transaction state. */
    private final AtomicReference<TablePartitionId> commitPartitionRef = new AtomicReference<>();

    /** The future used on repeated commit/rollback. */
    private final AtomicReference<CompletableFuture<Void>> finishFut = new AtomicReference<>();

    /**
     * The constructor.
     *
     * @param txManager The tx manager.
     * @param id The id.
     */
    public ReadWriteTransactionImpl(TxManager txManager, @NotNull UUID id) {
        super(txManager, id);
    }

    /** {@inheritDoc} */
    @Override
    public boolean assignCommitPartition(TablePartitionId tablePartitionId) {
        return commitPartitionRef.compareAndSet(null, tablePartitionId);
    }

    /** {@inheritDoc} */
    @Override
    public TablePartitionId commitPartition() {
        return commitPartitionRef.get();
    }

    /** {@inheritDoc} */
    @Override
    public IgniteBiTuple<ClusterNode, Long> enlistedNodeAndTerm(TablePartitionId partGroupId) {
        return enlisted.get(partGroupId);
    }

    /** {@inheritDoc} */
    @Override
    public IgniteBiTuple<ClusterNode, Long> enlist(TablePartitionId tablePartitionId, IgniteBiTuple<ClusterNode, Long> nodeAndTerm) {
        return enlisted.computeIfAbsent(tablePartitionId, k -> nodeAndTerm);
    }

    /** {@inheritDoc} */
    @Override
    protected CompletableFuture<Void> finish(boolean commit) {
        if (!finishFut.compareAndSet(null, new CompletableFuture<>())) {
            return finishFut.get();
        }

        // TODO: https://issues.apache.org/jira/browse/IGNITE-17688 Add proper exception handling.
        CompletableFuture<Void> mainFinishFut = CompletableFuture
                .allOf(enlistedResults.toArray(new CompletableFuture[0]))
                .thenCompose(
                        ignored -> {
                            if (!enlisted.isEmpty()) {
                                Map<ClusterNode, List<IgniteBiTuple<TablePartitionId, Long>>> groups = new LinkedHashMap<>();

                                enlisted.forEach((groupId, groupMeta) -> {
                                    ClusterNode recipientNode = groupMeta.get1();

                                    if (groups.containsKey(recipientNode)) {
                                        groups.get(recipientNode).add(new IgniteBiTuple<>(groupId, groupMeta.get2()));
                                    } else {
                                        List<IgniteBiTuple<TablePartitionId, Long>> items = new ArrayList<>();

                                        items.add(new IgniteBiTuple<>(groupId, groupMeta.get2()));

                                        groups.put(recipientNode, items);
                                    }
                                });

                                TablePartitionId commitPart = commitPartitionRef.get();
                                ClusterNode recipientNode = enlisted.get(commitPart).get1();
                                Long term = enlisted.get(commitPart).get2();

                                LOG.debug("Finish [recipientNode={}, term={} commit={}, txId={}, groups={}",
                                        recipientNode, term, commit, id(), groups);

                                assert recipientNode != null;
                                assert term != null;

                                return txManager.finish(
                                        commitPart,
                                        recipientNode,
                                        term,
                                        commit,
                                        groups,
                                        id()
                                );
                            } else {
                                // TODO: IGNITE-17638 TestOnly code, let's consider using Txn state map instead of states.
                                txManager.changeState(id(), PENDING, commit ? COMMITED : ABORTED);

                                return completedFuture(null);
                            }
                        }
                );

        mainFinishFut.handle((res, e) -> finishFut.get().complete(null));

        return mainFinishFut;
    }

    /** {@inheritDoc} */
    @Override
    public void enlistResultFuture(CompletableFuture<?> resultFuture) {
        enlistedResults.add(resultFuture);
    }

    /** {@inheritDoc} */
    @Override
    public boolean isReadOnly() {
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public HybridTimestamp readTimestamp() {
        return null;
    }

    @Override
    public HybridTimestamp startTimestamp() {
        return TransactionIds.beginTimestamp(id());
    }
}
