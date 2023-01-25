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

package org.apache.ignite.internal.sql.engine.prepare;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.calcite.avatica.util.TimeUnit;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.ignite.internal.sql.engine.planner.AbstractPlannerTest;
import org.apache.ignite.internal.sql.engine.schema.IgniteSchema;
import org.apache.ignite.internal.sql.engine.trait.IgniteDistributions;
import org.apache.ignite.internal.tostring.S;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests type coercion behaviour.
 *
 * @see IgniteTypeCoercion
 */
public class TypeCoercionTest extends AbstractPlannerTest {

    private static final RelDataType VARCHAR = TYPE_FACTORY.createSqlType(SqlTypeName.VARCHAR, 20);

    private static final List<RelDataType> NUMERIC_TYPES =  SqlTypeName.NUMERIC_TYPES.stream().map(t -> {
        if (t == SqlTypeName.DECIMAL) {
            return TYPE_FACTORY.createSqlType(t, 10, 2);
        } else {
            return TYPE_FACTORY.createSqlType(t);
        }
    }).collect(Collectors.toList());

    @ParameterizedTest
    @MethodSource("booleanToNumeric")
    public void testBooleanToNumeric(TypeCoercionRule rule) {
        Tester tester = new Tester(rule);
        tester.execute();
    }

    private static Stream<TypeCoercionRule> booleanToNumeric() {
        List<TypeCoercionRule> numericRules = new ArrayList<>();
        RelDataType booleanType = TYPE_FACTORY.createSqlType(SqlTypeName.BOOLEAN);

        for (RelDataType type : NUMERIC_TYPES) {
            numericRules.add(typeCoercionRule(type, booleanType, new ToSpecificType(type)));
            numericRules.add(typeCoercionRule(booleanType, type, new ToSpecificType(type)));
        }

        return numericRules.stream();
    }

    @ParameterizedTest
    @MethodSource("varCharToNumeric")
    public void testVarCharToNumeric(TypeCoercionRule rule) {
        Tester tester = new Tester(rule);
        tester.execute();
    }

    private static Stream<TypeCoercionRule> varCharToNumeric() {
        List<TypeCoercionRule> numericRules = new ArrayList<>();
        RelDataType expectedDecimalType = TYPE_FACTORY.createSqlType(SqlTypeName.DECIMAL, 32767, 16383);

        for (RelDataType type : NUMERIC_TYPES) {
            if (type.getSqlTypeName() == SqlTypeName.DECIMAL) {
                // TODO: https://issues.apache.org/jira/browse/IGNITE-18559
                // Coerce to the widest decimal possible?
                numericRules.add(typeCoercionRule(type, VARCHAR, new ToSpecificType(expectedDecimalType)));
                numericRules.add(typeCoercionRule(VARCHAR, type, new ToSpecificType(expectedDecimalType)));
            } else {
                // cast the right hand side to type
                numericRules.add(typeCoercionRule(type, VARCHAR, new ToSpecificType(type)));
                // cast the left hand side to type
                numericRules.add(typeCoercionRule(VARCHAR, type, new ToSpecificType(type)));
            }
        }

        return numericRules.stream();
    }

    @ParameterizedTest
    @MethodSource("numericCoercionRules")
    public void testTypeCoercionBetweenNumericTypes(TypeCoercionRule rule) {
        Tester tester = new Tester(rule);
        tester.execute();
    }

    private static Stream<TypeCoercionRule> numericCoercionRules() {
        List<TypeCoercionRule> numericRules = new ArrayList<>();

        for (RelDataType lhs : NUMERIC_TYPES) {
            for (RelDataType rhs : NUMERIC_TYPES) {
                if (lhs.equals(rhs)) {
                    numericRules.add(typeCoercionRule(lhs, rhs, new NoTypeCoercion()));
                } else {
                    numericRules.add(typeCoercionRule(lhs, rhs, new ToLeastRestrictiveType()));
                }
            }
        }

        return numericRules.stream();
    }

    @ParameterizedTest
    @MethodSource("numericToInterval")
    public void testNumericToInterval(TypeCoercionRule rule) {
        Tester tester = new Tester(rule);
        tester.execute();
    }

    private static Stream<TypeCoercionRule> numericToInterval() {
        List<TypeCoercionRule> numericRules = new ArrayList<>();
        SqlIntervalQualifier intervalQualifier = new SqlIntervalQualifier(TimeUnit.DAY, TimeUnit.HOUR, SqlParserPos.ZERO);
        RelDataType intervalType = TYPE_FACTORY.createSqlIntervalType(intervalQualifier);

        for (RelDataType type : NUMERIC_TYPES) {
            numericRules.add(typeCoercionIsNotSupported(type, intervalType));
            numericRules.add(typeCoercionIsNotSupported(intervalType, type));
        }

        return numericRules.stream();
    }

    @ParameterizedTest
    @MethodSource("numericToDate")
    public void testNumericToDate(TypeCoercionRule rule) {
        RelDataType lhs = rule.lhs;
        RelDataType rhs = rule.rhs;
        List<SqlTypeName> types = Arrays.asList(lhs.getSqlTypeName(), rhs.getSqlTypeName());

        boolean tinyIntDate = types.contains(SqlTypeName.TINYINT);
        boolean smallIntDate = types.contains(SqlTypeName.SMALLINT);
        boolean intDate = types.contains(SqlTypeName.INTEGER);
        boolean bigIntDate = types.contains(SqlTypeName.BIGINT);
        boolean decimalDateLhsIsDecimal = types.contains(SqlTypeName.DECIMAL);

        //TODO: https://issues.apache.org/jira/browse/IGNITE-18557
        // Use assumptions otherwise we got an AssertionError:
        /*
        at org.apache.calcite.sql.validate.implicit.AbstractTypeCoercion.needToCast(AbstractTypeCoercion.java:274)
        // Should keep sync with rules in SqlTypeCoercionRule.
        assert SqlTypeUtil.canCastFrom(toType, fromType, true);
         */
        Assumptions.assumeFalse(tinyIntDate || smallIntDate || bigIntDate || intDate);
        Assumptions.assumeFalse(decimalDateLhsIsDecimal);

        Tester tester = new Tester(rule);
        tester.execute();
    }

    private static Stream<TypeCoercionRule> numericToDate() {
        List<TypeCoercionRule> numericRules = new ArrayList<>();
        RelDataType dateType = TYPE_FACTORY.createSqlType(SqlTypeName.DATE);

        for (RelDataType type : NUMERIC_TYPES) {
            numericRules.add(typeCoercionIsNotSupported(type, dateType));
            numericRules.add(typeCoercionIsNotSupported(dateType, type));
        }

        return numericRules.stream();
    }

    @ParameterizedTest
    @MethodSource("varcharToInterval")
    public void testTypeCoercionBetweenIntervalAndVarchar(TypeCoercionRule rule) {
        Tester tester = new Tester(rule);
        tester.execute();
    }

    private static Stream<TypeCoercionRule> varcharToInterval() {
        List<TypeCoercionRule> numericRules = new ArrayList<>();

        SqlIntervalQualifier intervalQualifier = new SqlIntervalQualifier(TimeUnit.DAY, TimeUnit.HOUR, SqlParserPos.ZERO);
        RelDataType intervalType = TYPE_FACTORY.createSqlIntervalType(intervalQualifier);

        numericRules.add(typeCoercionRule(VARCHAR, intervalType, new NoTypeCoercion()));
        numericRules.add(typeCoercionRule(intervalType, VARCHAR, new NoTypeCoercion()));

        return numericRules.stream();
    }

    private final class Tester {

        final TypeCoercionRule rule;

        private Tester(TypeCoercionRule rule) {
            this.rule = rule;
        }

        void execute() {
            if (rule.type == null) {
                expectError(rule);
            } else {
                expectCoercion(rule);
            }
        }

        void expectCoercion(TypeCoercionRule rule) {
            if (rule.type == null) {
                throw new IllegalStateException("rule type is not specified");
            }

            runTest(rule, (planner, node) -> {
                SqlNode validNode = planner.validate(node);
                SqlSelect sqlSelect = (SqlSelect) validNode;
                String originalExpr = String.format("`A`.`C1` %s `A`.`C2`", rule.operator.getName());

                SqlCall sqlBasicCall = (SqlCall) sqlSelect.getSelectList().get(0);
                boolean coerced = !originalExpr.equals(sqlBasicCall.toString());
                checkResult(sqlBasicCall, rule.lhs, rule.rhs, rule.operator, coerced, rule.type);
            });
        }

        void expectError(TypeCoercionRule rule) {
            if (rule.type != null) {
                throw new IllegalStateException("rule type is specified. Call expectCoercion instead.");
            }

            runTest(rule, (planner, node) -> {
                String error = String.format("Cannot apply '%s' to arguments of type '<%s> %s <%s>",
                        rule.operator.getName(), rule.lhs, rule.operator.getName(), rule.rhs
                );
                CalciteContextException e = assertThrows(CalciteContextException.class, () -> planner.validate(node));
                assertThat(e.getMessage(), containsString(error));
            });
        }

        private void runTest(TypeCoercionRule rule, BiConsumer<IgnitePlanner, SqlNode> testCase) {
            RelDataType tableType = new RelDataTypeFactory.Builder(TYPE_FACTORY)
                    .add("C1", rule.lhs)
                    .add("C2", rule.rhs)
                    .build();

            TestTable testTable = createTable("A", tableType, DEFAULT_TBL_SIZE, IgniteDistributions.single());
            IgniteSchema igniteSchema = createSchema(testTable);

            String dummyQuery = String.format("SELECT c1 %s c2 FROM A", rule.operator.getName());
            PlanningContext planningCtx = plannerCtx(dummyQuery, igniteSchema);

            try (IgnitePlanner planner = planningCtx.planner()) {
                SqlNode node;
                try {
                    node = planner.parse(dummyQuery);
                } catch (SqlParseException e) {
                    throw new IllegalStateException("Unable to parse a query: " + dummyQuery, e);
                }

                testCase.accept(planner, node);
            }
        }
    }

    private static void checkResult(SqlCall sqlCall, RelDataType lhs, RelDataType rhs,
            SqlOperator operator, boolean coerced, TypeCoercionRuleType ruleType) {

        String originalExpression = sqlCall.toString();
        RelDataType dataType = ruleType.coerceTypes(lhs, rhs);
        RelDataType newLhs = dataType != null && !lhs.equals(dataType) ? dataType : null;
        RelDataType newRhs = dataType != null && !rhs.equals(dataType) ? dataType : null;

        if (newLhs == null && newRhs == null) {
            assertFalse(coerced, "should not have been coerced. Expr: " + sqlCall);
            assertEquals(originalExpression, sqlCall.toString(),
                    "Expression has been modified although type coercion has not been performed");
        } else {
            String expectedExpr;
            if (newLhs != null && newRhs == null) {
                // add cast to the left hand side of the expression
                expectedExpr = String.format("CAST(`A`.`C1` AS %s) %s `A`.`C2`", newLhs, operator.getName());
                assertTrue(coerced, "should have been coerced. Expr: " + sqlCall);
            } else if (newLhs == null) {
                // add cast to the right hand side of the expression
                expectedExpr = String.format("`A`.`C1` %s CAST(`A`.`C2` AS %s)", operator, newRhs);
                assertTrue(coerced, "should have been coerced. Expr: " + sqlCall);
                assertEquals(expectedExpr, sqlCall.toString());
            } else {
                // adds cast to both sides of the expression
                expectedExpr = String.format("CAST(`A`.`C1` AS %s) %s CAST(`A`.`C2` AS %s)", newLhs, operator.getName(), newRhs);
                assertEquals(expectedExpr, sqlCall.toString());
            }

            assertTrue(coerced, "should have been coerced. Expr: " + sqlCall);
            assertEquals(expectedExpr, sqlCall.toString(), "expression with casts");
        }
    }

    /** Type coercion between the given types behaves according the specified {@link TypeCoercionRuleType rule type}. **/
    private static TypeCoercionRule typeCoercionRule(RelDataType lhs, RelDataType rhs, TypeCoercionRuleType typeCoercion) {
        return new TypeCoercionRule(lhs, rhs, SqlStdOperatorTable.EQUALS, typeCoercion);
    }

    /** Type coercion between the given types is not supported and we must throw an exception. **/
    private static TypeCoercionRule typeCoercionIsNotSupported(RelDataType lhs, RelDataType rhs) {
        return new TypeCoercionRule(lhs, rhs, SqlStdOperatorTable.EQUALS, null);
    }

    private static final class TypeCoercionRule {
        final RelDataType lhs;

        final RelDataType rhs;

        final SqlOperator operator;

        final TypeCoercionRuleType type;

        TypeCoercionRule(RelDataType type1, RelDataType type2, SqlOperator operator, @Nullable TypeCoercionRuleType type) {
            this.lhs = type1;
            this.rhs = type2;
            this.operator = operator;
            this.type = type;
        }

        @Override
        public String toString() {
            return lhs + " " + operator.getName() + " " + rhs + " rule: " + type;
        }
    }

    abstract static class TypeCoercionRuleType {
        @Nullable
        abstract RelDataType coerceTypes(RelDataType type1, RelDataType type2);
    }

    /**
     * No casts are added to neither operand.
     */
    static final class NoTypeCoercion extends TypeCoercionRuleType {

        @Override
        @Nullable RelDataType coerceTypes(RelDataType type1, RelDataType type2) {
            return null;
        }

        @Override
        public String toString() {
            return S.toString(NoTypeCoercion.class, this);
        }
    }

    /**
     * Types are coerced to {@link RelDataTypeFactory#leastRestrictive(List) the least restrictive type}.
     */
    static final class ToLeastRestrictiveType extends TypeCoercionRuleType {

        @Override
        @Nullable RelDataType coerceTypes(RelDataType type1, RelDataType type2) {
            return TYPE_FACTORY.leastRestrictive(Arrays.asList(type1, type2));
        }

        @Override
        public String toString() {
            return S.toString(ToLeastRestrictiveType.class, this);
        }
    }

    /**
     * Operands are casted to the specified type.
     */
    static final class ToSpecificType extends TypeCoercionRuleType {

        private final RelDataType type;

        ToSpecificType(RelDataType type) {
            this.type = type;
        }

        @Override
        @Nullable RelDataType coerceTypes(RelDataType type1, RelDataType type2) {
            return type;
        }

        @Override
        public String toString() {
            return S.toString(ToSpecificType.class, this);
        }
    }
}