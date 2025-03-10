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

package org.apache.ignite.internal.cluster.management.raft.commands;

import org.apache.ignite.internal.cluster.management.ClusterState;
import org.apache.ignite.internal.cluster.management.network.messages.CmgMessageGroup;
import org.apache.ignite.internal.raft.WriteCommand;
import org.apache.ignite.network.annotations.Transferable;

/**
 * Command for initializing the CMG state. If the state has already been initialized, the sender node will be validated against the
 * existing state.
 */
@Transferable(CmgMessageGroup.Commands.INIT_CMG_STATE)
public interface InitCmgStateCommand extends WriteCommand {
    /**
     * Returns the node that wants to initialize the CMG state.
     */
    ClusterNodeMessage node();

    /**
     * Returns the CMG state.
     */
    ClusterState clusterState();
}
