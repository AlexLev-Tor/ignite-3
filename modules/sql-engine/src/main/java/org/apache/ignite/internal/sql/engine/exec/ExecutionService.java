/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.sql.engine.exec;

import java.util.List;
import java.util.UUID;
import org.apache.ignite.internal.sql.engine.SqlCursor;

/**
 * ExecutionService interface.
 * // TODO Documentation https://issues.apache.org/jira/browse/IGNITE-15859
 */
public interface ExecutionService extends LifecycleAware {
    /**
     * Executes a query.
     *
     * @param schema Schema name.
     * @param query  Query.
     * @param params Query parameters.
     * @return Query cursor.
     */
    List<SqlCursor<List<?>>> executeQuery(String schema, String query, Object[] params);

    /**
     * Cancels a running query.
     *
     * @param queryId Query ID.
     */
    void cancelQuery(UUID queryId);
}