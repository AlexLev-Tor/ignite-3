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

package org.apache.ignite.internal.table.distributed.replicator;

import org.apache.ignite.lang.ErrorGroups.Transactions;
import org.apache.ignite.tx.TransactionException;

/**
 * Thrown when, during an attempt to commit a transaction, it turns out that the transaction cannot be committed
 * because an incompatible schema change has happened.
 */
public class IncompatibleSchemaAbortException extends TransactionException {
    public IncompatibleSchemaAbortException(String message) {
        super(Transactions.TX_COMMIT_ERR, message);
    }
}
