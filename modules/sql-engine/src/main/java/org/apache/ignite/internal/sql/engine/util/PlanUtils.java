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

package org.apache.ignite.internal.sql.engine.util;

import java.util.List;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.sql.fun.SqlAvgAggFunction;
import org.apache.calcite.sql.fun.SqlCountAggFunction;
import org.apache.calcite.sql.fun.SqlMinMaxAggFunction;
import org.apache.calcite.sql.fun.SqlSumAggFunction;

/**
 * Plan util methods.
 */
public class PlanUtils {
    /**
     * Return {@code true} if observes AGGREGATE and DISTINCT simultaneously.
     *
     * @param aggCalls Aggregates.
     * @return {@code true} If found, {@code false} otherwise.
     */
    public static boolean complexDistinctAgg(List<AggregateCall> aggCalls) {
        for (AggregateCall call : aggCalls) {
            if (call.isDistinct()
                    && (call.getAggregation() instanceof SqlCountAggFunction
                    || call.getAggregation() instanceof SqlAvgAggFunction
                    || call.getAggregation() instanceof SqlSumAggFunction
                    || call.getAggregation() instanceof SqlMinMaxAggFunction)) {
                return true;
            }
        }
        return false;
    }
}
