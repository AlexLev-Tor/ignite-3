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

package org.apache.ignite.internal.tx.message;

import static org.apache.ignite.internal.hlc.HybridTimestamp.nullableHybridTimestamp;

import java.util.UUID;
import org.apache.ignite.internal.hlc.HybridTimestamp;
import org.apache.ignite.internal.replicator.message.ReplicaRequest;
import org.apache.ignite.internal.replicator.message.TimestampAware;
import org.apache.ignite.network.annotations.Transferable;
import org.jetbrains.annotations.Nullable;

/**
 * Transaction cleanup replica request that will trigger following actions processing.
 *
 *  <ol>
 *      <li>Convert all pending entries(writeIntents) to either regular values(TxState.COMMITED) or remove them (TxState.ABORTED).</li>
 *      <li>Release all locks that were held on local replica by given transaction.</li>
 *  </ol>
 */
@Transferable(value = TxMessageGroup.TX_CLEANUP_REQUEST)
public interface TxCleanupReplicaRequest extends ReplicaRequest, TimestampAware {
    /**
     * Returns transaction Id.
     *
     * @return Transaction id.
     */
    UUID txId();

    /**
     * Returns {@code True} if a commit request.
     *
     * @return {@code True} to commit.
     */
    boolean commit();

    /**
     * Returns a transaction commit timestamp.
     *
     * @return Commit timestamp.
     */
    long commitTimestampLong();

    /**
     * Returns a transaction commit timestamp.
     *
     * @return Commit timestamp.
     */
    default @Nullable HybridTimestamp commitTimestamp() {
        return nullableHybridTimestamp(commitTimestampLong());
    }

    /**
     * Gets a raft term.
     * TODO: A temp solution until lease-based engine will be implemented (IGNITE-17256, IGNITE-15083)
     *
     * @return Raft term.
     */
    @Deprecated
    Long term();
}
