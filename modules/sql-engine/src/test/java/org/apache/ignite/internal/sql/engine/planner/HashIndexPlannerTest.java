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

package org.apache.ignite.internal.sql.engine.planner;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.util.List;
import org.apache.calcite.plan.RelOptPlanner.CannotPlanException;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.ignite.internal.sql.engine.rel.IgniteIndexScan;
import org.apache.ignite.internal.sql.engine.rel.IgniteRel;
import org.apache.ignite.internal.sql.engine.rel.IgniteTableScan;
import org.apache.ignite.internal.sql.engine.schema.IgniteIndex;
import org.apache.ignite.internal.sql.engine.schema.IgniteSchema;
import org.apache.ignite.internal.sql.engine.trait.IgniteDistributions;
import org.apache.ignite.internal.testframework.IgniteTestUtils;
import org.junit.jupiter.api.Test;

/**
 * Tests to verify hash index support.
 */
public class HashIndexPlannerTest extends AbstractPlannerTest {
    @Test
    public void hashIndexIsAppliedForEquiCondition() throws Exception {
        var indexName = "VAL_HASH_IDX";

        IgniteSchema schema = makeCommonSchema();

        String sql = "SELECT id FROM test_tbl WHERE val = 10";

        RelNode phys = physicalPlan(sql, schema);

        IgniteIndexScan scan = findFirstNode(phys, byClass(IgniteIndexScan.class));

        String invalidPlanMsg = "Invalid plan:\n" + RelOptUtil.toString(phys);

        assertThat(invalidPlanMsg, scan, notNullValue());
        assertThat(invalidPlanMsg, scan.indexName(), equalTo(indexName));
    }

    @Test
    public void hashIndexIsNotAppliedForRangeCondition() throws Exception {
        IgniteSchema schema = makeCommonSchema();

        String sql = "SELECT id FROM test_tbl WHERE val >= 10";

        RelNode phys = physicalPlan(sql, schema);

        IgniteTableScan scan = findFirstNode(phys, byClass(IgniteTableScan.class));

        String invalidPlanMsg = "Invalid plan:\n" + RelOptUtil.toString(phys);

        assertThat(invalidPlanMsg, scan, notNullValue());

        // Can`t use hash index scan with range.
        String sqlGt = "SELECT /*+ DISABLE_RULE('LogicalTableScanConverterRule')*/ id FROM test_tbl WHERE val >= 10";

        IgniteTestUtils.assertThrowsWithCause(() -> physicalPlan(sqlGt, schema), CannotPlanException.class,
                "There are not enough rules");
    }

    @Test
    // Can`t use hash index scan without condition.
    public void testHashIndexIsNotAppliedWithoutConditions() {
        IgniteSchema schema = makeCommonSchema();

        String sqlNoCond = "SELECT /*+ DISABLE_RULE('LogicalTableScanConverterRule')*/ * FROM test_tbl";

        IgniteTestUtils.assertThrowsWithCause(() -> physicalPlan(sqlNoCond, schema), CannotPlanException.class,
                "There are not enough rules");
    }

    @Test
    // Correct hash index scan usage with exact conditions.
    public void testHashIndexAppliedForExactKey() throws Exception {
        IgniteSchema schema = makeCommonSchema();

        String sqlEqCond = "SELECT /*+ DISABLE_RULE('LogicalTableScanConverterRule')*/ id FROM test_tbl WHERE val = 10";

        IgniteRel phys = physicalPlan(sqlEqCond, schema);

        IgniteIndexScan idxScan = findFirstNode(phys, byClass(IgniteIndexScan.class));

        assertThat("Invalid plan:\n" + RelOptUtil.toString(phys), idxScan, notNullValue());
    }

    private IgniteSchema makeCommonSchema() {
        var indexName = "VAL_HASH_IDX";

        TestTable tbl = createTable(
                "TEST_TBL",
                someAffinity(),
                "ID", Integer.class,
                "VAL", Integer.class
        );

        tbl.addIndex(new IgniteIndex(TestHashIndex.create(List.of("VAL"), indexName)));

        return createSchema(tbl);
    }

    @Test
    public void hashIndexIsAppliedForComplexConditions() throws Exception {
        var indexName = "VAL_HASH_IDX";

        TestTable leftTable = createTable(
                "LEFT_TBL",
                IgniteDistributions.single(),
                "ID", Integer.class,
                "VAL0", Integer.class,
                "VAL1", Integer.class
        );

        TestTable rightTable = createTable(
                "RIGHT_TBL",
                IgniteDistributions.single(),
                "ID", Integer.class,
                "VAL0", Integer.class,
                "VAL1", Integer.class
        );

        rightTable.addIndex(new IgniteIndex(TestHashIndex.create(List.of("VAL0", "VAL1"), indexName)));

        IgniteSchema schema = createSchema(leftTable, rightTable);

        String sql = "SELECT l.*, r.* FROM left_tbl l JOIN right_tbl r ON l.val0 = r.val0 AND l.val1 = r.val1";

        RelNode phys = physicalPlan(sql, schema, "MergeJoinConverter", "NestedLoopJoinConverter");

        IgniteIndexScan scan = findFirstNode(phys, byClass(IgniteIndexScan.class));

        String invalidPlanMsg = "Invalid plan:\n" + RelOptUtil.toString(phys);

        assertThat(invalidPlanMsg, scan, notNullValue());
        assertThat(invalidPlanMsg, scan.indexName(), equalTo(indexName));
    }

    @Test
    public void hashIndexIsNotAppliedForPartialCoveredConditions() throws Exception {
        var indexName = "VAL_HASH_IDX";

        TestTable leftTable = createTable(
                "LEFT_TBL",
                IgniteDistributions.single(),
                "ID", Integer.class,
                "VAL0", Integer.class,
                "VAL1", Integer.class
        );

        TestTable rightTable = createTable(
                "RIGHT_TBL",
                IgniteDistributions.single(),
                "ID", Integer.class,
                "VAL0", Integer.class,
                "VAL1", Integer.class
        );

        rightTable.addIndex(new IgniteIndex(TestHashIndex.create(List.of("VAL0", "VAL1"), indexName)));

        IgniteSchema schema = createSchema(leftTable, rightTable);

        String sql = "SELECT l.id FROM left_tbl l JOIN right_tbl r ON l.val0 = r.val0";

        RelNode phys = physicalPlan(sql, schema, "MergeJoinConverter", "NestedLoopJoinConverter");

        IgniteIndexScan scan = findFirstNode(phys, byClass(IgniteIndexScan.class));

        String invalidPlanMsg = "Invalid plan:\n" + RelOptUtil.toString(phys);

        assertThat(invalidPlanMsg, scan, nullValue());
    }
}
