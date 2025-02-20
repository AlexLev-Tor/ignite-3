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

namespace Apache.Ignite.Tests;

using System;
using System.Threading.Tasks;
using Compute;
using Ignite.Compute;
using Ignite.Table;
using Internal.Proto;
using NUnit.Framework;

/// <summary>
/// Tests partition awareness in real cluster.
/// </summary>
public class PartitionAwarenessRealClusterTests : IgniteTestsBase
{
    /// <summary>
    /// Uses <see cref="ComputeTests.NodeNameJob"/> to get the name of the node that should be the primary for the given key,
    /// and compares to the actual node that received the request (using IgniteProxy).
    /// </summary>
    /// <returns>A <see cref="Task"/> representing the asynchronous unit test.</returns>
    [Test]
    public async Task TestPutRoutesRequestToPrimaryNode()
    {
        var proxies = GetProxies();
        using var client = await IgniteClient.StartAsync(GetConfig(proxies));
        var recordView = (await client.Tables.GetTableAsync(TableName))!.RecordBinaryView;

        // Warm up.
        await recordView.GetAsync(null, new IgniteTuple { ["KEY"] = 1L });

        // Check.
        for (long key = 0; key < 50; key++)
        {
            var keyTuple = new IgniteTuple { ["KEY"] = key };

            var primaryNodeName = await client.Compute.ExecuteColocatedAsync<string>(
                TableName,
                keyTuple,
                Array.Empty<DeploymentUnit>(),
                ComputeTests.NodeNameJob);

            if (primaryNodeName.EndsWith("_3", StringComparison.Ordinal) || primaryNodeName.EndsWith("_4", StringComparison.Ordinal))
            {
                // Skip nodes without direct client connection.
                continue;
            }

            await recordView.UpsertAsync(null, keyTuple);
            var requestTargetNodeName = GetRequestTargetNodeName(proxies, ClientOp.TupleUpsert);

            Assert.AreEqual(primaryNodeName, requestTargetNodeName);
        }
    }
}
