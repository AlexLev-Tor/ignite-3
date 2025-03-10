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

package org.apache.ignite.client.handler.requests.table;

import static org.apache.ignite.client.handler.requests.table.ClientTableCommon.readTableAsync;
import static org.apache.ignite.client.handler.requests.table.ClientTableCommon.writeSchema;

import java.util.concurrent.CompletableFuture;
import org.apache.ignite.internal.client.proto.ClientMessagePacker;
import org.apache.ignite.internal.client.proto.ClientMessageUnpacker;
import org.apache.ignite.lang.ErrorGroups.Common;
import org.apache.ignite.lang.IgniteException;
import org.apache.ignite.table.manager.IgniteTables;

/**
 * Client schemas retrieval request.
 */
public class ClientSchemasGetRequest {
    /**
     * Processes the request.
     *
     * @param in     Unpacker.
     * @param out    Packer.
     * @param tables Ignite tables.
     * @return Future.
     * @throws IgniteException When schema registry is no initialized.
     */
    public static CompletableFuture<Void> process(
            ClientMessageUnpacker in,
            ClientMessagePacker out,
            IgniteTables tables
    ) {
        return readTableAsync(in, tables).thenAccept(table -> {
            if (in.tryUnpackNil()) {
                // Return the latest schema.
                out.packMapHeader(1);

                var schema = table.schemaView().schema();

                if (schema == null) {
                    throw new IgniteException(Common.COMPONENT_NOT_STARTED_ERR, "Schema registry is not initialized.");
                }

                writeSchema(out, schema.version(), schema);
            } else {
                var cnt = in.unpackArrayHeader();
                out.packMapHeader(cnt);

                for (var i = 0; i < cnt; i++) {
                    var schemaVer = in.unpackInt();
                    var schema = table.schemaView().schema(schemaVer);
                    writeSchema(out, schemaVer, schema);
                }
            }
        });
    }
}
