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

import static java.util.Objects.requireNonNull;
import static org.apache.calcite.sql.type.NonNullableAccessors.getCollation;
import static org.apache.calcite.util.Static.RESOURCE;

import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.rel.type.DynamicRecordType;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeFactoryImpl;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlCollation;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlDynamicParam;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlUtil;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorScope;
import org.apache.calcite.sql.validate.implicit.TypeCoercionImpl;
import org.apache.calcite.util.Util;
import org.apache.ignite.internal.sql.engine.type.IgniteCustomType;
import org.apache.ignite.internal.sql.engine.type.IgniteCustomTypeCoercionRules;
import org.apache.ignite.internal.sql.engine.type.IgniteTypeFactory;
import org.apache.ignite.internal.sql.engine.util.IgniteResource;
import org.apache.ignite.internal.sql.engine.util.TypeUtils;
import org.jetbrains.annotations.Nullable;

/** Implicit type cast implementation. */
public class IgniteTypeCoercion extends TypeCoercionImpl {

    // We are using thread local here b/c TypeCoercion is expected to be stateless.
    private static final ThreadLocal<ContextStack> contextStack = ThreadLocal.withInitial(ContextStack::new);

    private final IgniteTypeFactory typeFactory;

    public IgniteTypeCoercion(RelDataTypeFactory typeFactory, SqlValidator validator) {
        super(typeFactory, validator);
        this.typeFactory = (IgniteTypeFactory) typeFactory;
    }

    /** {@inheritDoc} **/
    @Override
    public boolean binaryComparisonCoercion(SqlCallBinding binding) {
        ContextStack ctxStack = contextStack.get();
        Context ctx = ctxStack.push(ContextType.UNSPECIFIED);
        try {
            return doBinaryComparisonCoercion(binding);
        } finally {
            ctxStack.pop(ctx);
        }
    }

    private boolean doBinaryComparisonCoercion(SqlCallBinding binding) {
        // Although it is not reflected in the docs, this method is also invoked for MAX, MIN (and other similar operators)
        // by ComparableOperandTypeChecker. When that is the case, fallback to default rules.
        SqlCall call = binding.getCall();
        if (binding.getOperandCount() != 2 || !SqlKind.BINARY_COMPARISON.contains(call.getKind())) {
            return super.binaryComparisonCoercion(binding);
        }

        SqlValidatorScope scope = binding.getScope();
        RelDataType leftType = validator.deriveType(scope, call.operand(0));
        RelDataType rightType = validator.deriveType(scope, call.operand(1));

        //
        // binaryComparisonCoercion that makes '1' > 1 work, may introduce some inconsistent results
        // for dynamic parameters:
        //
        // SELECT * FROM t WHERE int_col > ?:str
        // Rejected: int and str do not have compatible type families.
        //
        // SELECT * FROM t WHERE str_col > ?:int
        // Accepted: as it adds implicit cast to 'str_col' and we end up with
        //
        // SELECT * FROM t WHERE int(str_col) = ?:int
        //
        // Which is a perfectly valid plan, but it is not what one might expect.
        validateBinaryComparisonCoercion(binding, leftType, rightType, (IgniteSqlValidator) validator);

        if (leftType.equals(rightType)) {
            // If types are the same fallback to default rules.
            return super.binaryComparisonCoercion(binding);
        } else {
            // Otherwise find the least restrictive type among the operand types
            // and coerce the operands to that type if such type exists.
            //
            // An example of a least restrictive type from the javadoc for RelDataTypeFactory::leastRestrictive:
            // leastRestrictive(INT, NUMERIC(3, 2)) could be NUMERIC(12, 2)
            //
            // A least restrictive type between types of different type families does not exist -
            // the method returns null (See SqlTypeFactoryImpl::leastRestrictive).
            //
            RelDataType targetType = factory.leastRestrictive(Arrays.asList(leftType, rightType));

            if (targetType == null) {
                // If least restrictive type does not exist fallback to default rules.
                return super.binaryComparisonCoercion(binding);
            } else {
                boolean coerced = false;

                if (!leftType.equals(targetType)) {
                    coerced = coerceOperandType(scope, call, 0, targetType);
                }

                if (!rightType.equals(targetType)) {
                    boolean rightCoerced = coerceOperandType(scope, call, 1, targetType);
                    coerced = coerced || rightCoerced;
                }

                return coerced;
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean binaryArithmeticCoercion(SqlCallBinding binding) {
        ContextStack ctxStack = contextStack.get();
        Context ctx = ctxStack.push(ContextType.UNSPECIFIED);
        try {
            return super.binaryArithmeticCoercion(binding);
        } finally {
            ctxStack.pop(ctx);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean builtinFunctionCoercion(SqlCallBinding binding, List<RelDataType> operandTypes, List<SqlTypeFamily> expectedFamilies) {
        ContextStack ctxStack = contextStack.get();
        Context ctx = ctxStack.push(ContextType.UNSPECIFIED);
        try {
            return super.builtinFunctionCoercion(binding, operandTypes, expectedFamilies);
        } finally {
            ctxStack.pop(ctx);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean userDefinedFunctionCoercion(SqlValidatorScope scope, SqlCall call, SqlFunction function) {
        ContextStack ctxStack = contextStack.get();
        Context ctx = ctxStack.push(ContextType.UNSPECIFIED);
        try {
            return super.userDefinedFunctionCoercion(scope, call, function);
        } finally {
            ctxStack.pop(ctx);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean inOperationCoercion(SqlCallBinding binding) {
        ContextStack ctxStack = contextStack.get();
        Context ctx = ctxStack.push(ContextType.IN);
        try {
            return super.inOperationCoercion(binding);
        } finally {
            ctxStack.pop(ctx);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean rowTypeCoercion(@Nullable SqlValidatorScope scope, SqlNode query,
            int columnIndex, RelDataType targetType) {

        ContextStack ctxStack = contextStack.get();

        Context ctx;
        if (ctxStack.currentContext() == ContextType.MODIFY) {
            // If current context is MODIFY, this means that rowTypeCoercion is called
            // for INSERT INTO of NOT MATCHED arm of MERGE statement.
            ctx = ctxStack.push(ContextType.INSERT);
        } else {
            // Otherwise it is a set operation.
            ctx = ctxStack.push(ContextType.SET_OP);
        }

        try {
            return super.rowTypeCoercion(scope, query, columnIndex, targetType);
        } finally {
            ctxStack.pop(ctx);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean caseWhenCoercion(SqlCallBinding callBinding) {
        ContextStack ctxStack = contextStack.get();
        Context ctx = ctxStack.push(ContextType.CASE_EXPR);
        try {
            return super.caseWhenCoercion(callBinding);
        } finally {
            ctxStack.pop(ctx);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean querySourceCoercion(@Nullable SqlValidatorScope scope,
            RelDataType sourceRowType, RelDataType targetRowType, SqlNode query) {

        ContextStack ctxStack = contextStack.get();
        Context ctx = ctxStack.push(ContextType.MODIFY);
        try {
            return super.querySourceCoercion(scope, sourceRowType, targetRowType, query);
        } finally {
            ctxStack.pop(ctx);
        }
    }

    /** {@inheritDoc} */
    @Override
    public @Nullable RelDataType getWiderTypeFor(List<RelDataType> typeList, boolean stringPromotion) {
        ContextStack ctxStack = contextStack.get();
        ContextType ctxType = ctxStack.currentContext();
        // Disable string promotion for case expression operands
        // to comply with 9.5 clause of the SQL standard (Result of data type combinations).
        if (ctxType == ContextType.CASE_EXPR) {
            return super.getWiderTypeFor(typeList, false);
        } else {
            return super.getWiderTypeFor(typeList, stringPromotion);
        }
    }

    /** {@inheritDoc} */
    @Override
    protected boolean needToCast(SqlValidatorScope scope, SqlNode node, RelDataType toType) {
        RelDataType fromType = validator.deriveType(scope, node);
        if (SqlTypeUtil.isInterval(toType)) {
            if (SqlTypeUtil.isInterval(fromType)) {
                // Two different families of intervals: INTERVAL_DAY_TIME and INTERVAL_YEAR_MONTH.
                return fromType.getSqlTypeName().getFamily() != toType.getSqlTypeName().getFamily();
            }
        } else if (SqlTypeUtil.isIntType(toType)) {
            if (fromType == null) {
                return false;
            }
            // The following checks ensure that there no ClassCastException when casting from one
            // integer type to another (e.g. int to smallint, int to bigint)
            if (SqlTypeUtil.isIntType(fromType) && fromType.getSqlTypeName() != toType.getSqlTypeName()) {
                return true;
            }
        } else if (toType.getSqlTypeName() == SqlTypeName.ANY || fromType.getSqlTypeName() == SqlTypeName.ANY) {
            // IgniteCustomType: whether we need implicit cast from one type to another.
            return TypeUtils.customDataTypeNeedCast(typeFactory, fromType, toType);
        }

        return super.needToCast(scope, node, toType);
    }

    // The method is fully copy from parent class with modified handling of dynamic parameters.

    /** {@inheritDoc} */
    @Override
    protected boolean coerceOperandType(
            @Nullable SqlValidatorScope scope,
            SqlCall call,
            int index,
            RelDataType targetType) {
        // Transform the JavaType to SQL type because the SqlDataTypeSpec
        // does not support deriving JavaType yet.
        if (RelDataTypeFactoryImpl.isJavaType(targetType)) {
            targetType = ((JavaTypeFactory) factory).toSql(targetType);
        }

        SqlNode operand = call.getOperandList().get(index);
        if (operand instanceof SqlDynamicParam) {
            SqlDynamicParam dynamicParam = (SqlDynamicParam) operand;
            ContextType contextType = contextStack.get().currentContext();

            validateCoerceOperand(call, dynamicParam, targetType, contextType, (IgniteSqlValidator) validator);
        }

        // We should never coerce DEFAULT, since it is going to break
        // convertColumnList and DEFAULTs are not going to be replaced with
        // values, produced by initializerFactory.newColumnDefaultValue.
        if (operand.getKind() == SqlKind.DEFAULT) {
            // DEFAULT is also of type ANY
            return false;
        }

        requireNonNull(scope, "scope");
        RelDataType operandType = validator.deriveType(scope, operand);
        if (coerceStringToArray(call, operand, index, operandType, targetType)) {
            return true;
        }
        // Check it early.
        if (!needToCast(scope, operand, targetType)) {
            return false;
        }
        // Fix up nullable attr.
        RelDataType targetType1 = syncAttributes(operandType, targetType);
        SqlNode desired = castTo(operand, targetType1);
        call.setOperand(index, desired);
        updateInferredType(desired, targetType1);
        return true;
    }

    /** {@inheritDoc} */
    @Override
    protected boolean coerceColumnType(
            @Nullable SqlValidatorScope scope,
            SqlNodeList nodeList,
            int index,
            RelDataType targetType) {

        // see coerceColumnType implementation.
        if (index >= nodeList.size()) {
            return false;
        }

        // we can not call SqlValidatorImpl.getValidatedNodeType because index's node type may not be know.
        RelDataType[] originalDataType = {null};
        boolean coerced = doCoerceColumnType(scope, nodeList, index, targetType, originalDataType);

        if (!coerced) {
            return false;
        }

        // see coerceColumnType implementation.
        SqlNode node = nodeList.get(index);
        if (node instanceof SqlIdentifier) {
            SqlIdentifier id = (SqlIdentifier) node;
            if (id.isStar()) {
                return true;
            }
        }

        ContextStack ctxStack = contextStack.get();
        ContextType ctxType = ctxStack.currentContext();

        if (ctxType == ContextType.SET_OP && originalDataType[0] != null) {
            // Make coercion to between type incompatible families illegal for set operations.
            // Returns false so that SetopOperandTypeChecker is going to raise appropriate exception,
            // when coercion fails.
            return TypeUtils.typeFamiliesAreCompatible(typeFactory, targetType, originalDataType[0]);
        } else {
            return true;
        }
    }

    // The method is fully copy from parent class with modified handling of dynamic parameters.

    private boolean doCoerceColumnType(
            @Nullable SqlValidatorScope scope,
            SqlNodeList nodeList,
            int index,
            RelDataType targetType, RelDataType[] originalDataType) {
        // Transform the JavaType to SQL type because the SqlDataTypeSpec
        // does not support deriving JavaType yet.
        if (RelDataTypeFactoryImpl.isJavaType(targetType)) {
            targetType = ((JavaTypeFactory) factory).toSql(targetType);
        }

        // This will happen when there is a star/dynamic-star column in the select list,
        // and the source is values expression, i.e. `select * from (values(1, 2, 3))`.
        // There is no need to coerce the column type, only remark
        // the inferred row type has changed, we will then add in type coercion
        // when expanding star/dynamic-star.

        // See SqlToRelConverter#convertSelectList for details.
        if (index >= nodeList.size()) {
            // Can only happen when there is a star(*) in the column,
            // just return true.
            return true;
        }

        final SqlNode node = nodeList.get(index);
        if (node instanceof SqlDynamicParam) {
            SqlDynamicParam dynamicParam = (SqlDynamicParam) node;
            boolean coerce = validateCoerceColumn(dynamicParam, targetType, (IgniteSqlValidator) validator);
            if (!coerce) {
                return false;
            }
        }

        if (node instanceof SqlIdentifier) {
            // Do not expand a star/dynamic table col.
            SqlIdentifier node1 = (SqlIdentifier) node;
            if (node1.isStar()) {
                return true;
            } else if (DynamicRecordType.isDynamicStarColName(Util.last(node1.names))) {
                // Should support implicit cast for dynamic table.
                return false;
            }
        }

        requireNonNull(scope, "scope is needed for needToCast(scope, operand, targetType)");
        if (node instanceof SqlCall) {
            SqlCall node2 = (SqlCall) node;
            if (node2.getOperator().kind == SqlKind.AS) {
                final SqlNode operand = node2.operand(0);
                RelDataType operandType = validator.deriveType(scope, operand);
                if (!needToCast(scope, operand, targetType)) {
                    return false;
                }
                RelDataType targetType2 = syncAttributes(operandType, targetType);
                final SqlNode casted = castTo(operand, targetType2);
                node2.setOperand(0, casted);
                updateInferredType(casted, targetType2);
                // store original type, if coerced.
                originalDataType[0] = operandType;
                return true;
            }
        }
        RelDataType operandType = validator.deriveType(scope, node);
        if (!needToCast(scope, node, targetType)) {
            return false;
        }
        RelDataType targetType3 = syncAttributes(operandType, targetType);
        final SqlNode node3 = castTo(node, targetType3);
        nodeList.set(index, node3);
        updateInferredType(node3, targetType3);
        // store original node type, if coerced.
        originalDataType[0] = operandType;
        return true;
    }

    /**
     * Sync the data type additional attributes before casting,
     * i.e. nullability, charset, collation.
     */
    RelDataType syncAttributes(
            RelDataType fromType,
            RelDataType toType) {
        RelDataType syncedType = toType;
        if (fromType != null) {
            syncedType = factory.createTypeWithNullability(syncedType, fromType.isNullable());
            if (SqlTypeUtil.inCharOrBinaryFamilies(fromType)
                    && SqlTypeUtil.inCharOrBinaryFamilies(toType)) {
                Charset charset = fromType.getCharset();
                if (charset != null && SqlTypeUtil.inCharFamily(syncedType)) {
                    SqlCollation collation = getCollation(fromType);
                    syncedType =
                            factory.createTypeWithCharsetAndCollation(syncedType, charset,
                                    collation);
                }
            }
        }
        return syncedType;
    }

    /** {@inheritDoc} **/
    @Override
    public @Nullable RelDataType commonTypeForBinaryComparison(@Nullable RelDataType type1, @Nullable RelDataType type2) {
        if (type1 == null || type2 == null) {
            return null;
        }

        // IgniteCustomType: If one of the arguments is a custom data type,
        // check whether it is possible to convert another type to it.
        // Returns not null to indicate that a CAST operation can be added
        // to convert another type to this custom data type.
        if (type1 instanceof IgniteCustomType) {
            IgniteCustomType to = (IgniteCustomType) type1;
            return tryCustomTypeCoercionRules(type2, to);
        } else if (type2 instanceof IgniteCustomType) {
            IgniteCustomType to = (IgniteCustomType) type2;
            return tryCustomTypeCoercionRules(type1, to);
        } else {
            return super.commonTypeForBinaryComparison(type1, type2);
        }
    }

    private @Nullable RelDataType tryCustomTypeCoercionRules(RelDataType from, IgniteCustomType to) {
        IgniteCustomTypeCoercionRules typeCoercionRules = typeFactory.getCustomTypeCoercionRules();
        if (typeCoercionRules.needToCast(from, to)) {
            return to;
        } else {
            return null;
        }
    }

    private static SqlNode castTo(SqlNode node, RelDataType type) {
        SqlDataTypeSpec targetDataType;
        if (type instanceof IgniteCustomType) {
            var customType = (IgniteCustomType) type;
            var nameSpec = customType.createTypeNameSpec();

            targetDataType = new SqlDataTypeSpec(nameSpec, SqlParserPos.ZERO);
        } else {
            targetDataType = SqlTypeUtil.convertTypeToSpec(type).withNullable(type.isNullable());
        }

        return SqlStdOperatorTable.CAST.createCall(SqlParserPos.ZERO, node, targetDataType);
    }

    /**
     * Validates dynamic parameters in binary comparison operation.
     */
    private void validateBinaryComparisonCoercion(SqlCallBinding binding, RelDataType leftType,
            RelDataType rightType, IgniteSqlValidator validator) {

        SqlNode lhs = binding.operand(0);
        SqlNode rhs = binding.operand(1);

        if (lhs instanceof SqlDynamicParam) {
            validateOperand((SqlDynamicParam) lhs, rightType, binding.getOperator(), validator);
        }

        if (rhs instanceof SqlDynamicParam) {
            validateOperand((SqlDynamicParam) rhs, leftType, binding.getOperator(), validator);
        }
    }

    /**
     * Validates dynamic parameter as a call operand or in assignment.
     */
    private void validateCoerceOperand(SqlCall call, SqlDynamicParam dynamicParam, RelDataType targetType,
            ContextType ctxType, IgniteSqlValidator validator) {

        if (ctxType == ContextType.INSERT || ctxType == ContextType.MODIFY) {
            // Treat ROW operator as the same way as assignment.
            validateAssignment(dynamicParam, targetType, ctxType, validator);
        } else if (ctxType == ContextType.IN) {
            // Use IN operation instead of ROW operator for errors.
            validateOperand(dynamicParam, targetType, SqlStdOperatorTable.IN, validator);
        } else {
            validateOperand(dynamicParam, targetType, call.getOperator(), validator);
        }
    }

    /**
     * Validates dynamic parameter type in SET operation or in assignment (UPDATE/INSERT).
     */
    private boolean validateCoerceColumn(SqlDynamicParam dynamicParam, RelDataType targetType,
            IgniteSqlValidator validator) {

        ContextType ctxType = contextStack.get().currentContext();

        // This method throws and error in case of INSERT/UPDATE, as
        // We throw error here because SqlValidatorImpl::checkTypeAssignment ignores Dynamic Params when type coercion fails.
        //
        // For set operations we rely on the fact SetopOperandTypeChecker is going to raise appropriate exception,
        // because CalciteResource::columnTypeMismatchInSetop requires a name of a set operation
        // (and we have no such information here).

        if (ctxType == ContextType.SET_OP) {
            RelDataType paramType = validator.getDynamicParamType(dynamicParam);
            return TypeUtils.typeFamiliesAreCompatible(typeFactory, targetType, paramType);
        } else {
            validateAssignment(dynamicParam, targetType, ctxType, validator);
            return true;
        }
    }

    // TODO: https://issues.apache.org/jira/browse/IGNITE-19721 - move this check to SqlValidator (if possible).
    private void validateAssignment(SqlDynamicParam node, RelDataType targetType, ContextType ctxType, IgniteSqlValidator validator) {

        RelDataType paramType = validator.getDynamicParamType(node);
        boolean compatible = TypeUtils.typeFamiliesAreCompatible(typeFactory, targetType, paramType);
        if (compatible) {
            return;
        }

        if (ctxType == ContextType.INSERT) {
            // Throw the same error if T1 and T2 are not compatible:
            //
            // 1) INSERT INTO (t1_col) VALUES (<T2>)
            // 2) INSERT in not match arm of MERGE statement.
            //
            var ex = RESOURCE.incompatibleValueType(SqlStdOperatorTable.VALUES.getName());
            throw SqlUtil.newContextException(node.getParserPosition(), ex);
        } else {
            // Error for UPDATE in both standalone UPDATE and WHEN MATCHED arm of MERGE statements.

            String paramTypeString = paramType.toString();
            String targetTypeString = targetType.toString();

            var ex = IgniteResource.INSTANCE.assignmentRequiresExplicitCast(paramTypeString, targetTypeString);
            throw SqlUtil.newContextException(node.getParserPosition(), ex);
        }
    }

    // TODO: https://issues.apache.org/jira/browse/IGNITE-19721 - move this check to SqlValidator (if possible).
    private void validateOperand(SqlDynamicParam node, RelDataType targetType, SqlOperator operator, IgniteSqlValidator validator) {

        RelDataType paramType = validator.getDynamicParamType(node);
        boolean compatible = TypeUtils.typeFamiliesAreCompatible(typeFactory, targetType, paramType);
        if (compatible) {
            return;
        }

        var ex = IgniteResource.INSTANCE.operationRequiresExplicitCast(operator.getName());
        throw SqlUtil.newContextException(node.getParserPosition(), ex);
    }

    /**
     * A context in which type coercion operation is being called.
     */
    enum ContextType {
        /**
         * Corresponds to {@link IgniteTypeCoercion#caseWhenCoercion(SqlCallBinding)}.
         */
        CASE_EXPR,

        /**
         * Corresponds to {@link IgniteTypeCoercion#rowTypeCoercion(SqlValidatorScope, SqlNode, int, RelDataType)} called for
         * INSERT INTO statement.
         */
        INSERT,

        /**
         * Corresponds to {@link IgniteTypeCoercion#rowTypeCoercion(SqlValidatorScope, SqlNode, int, RelDataType)} called for
         * UPDATE both standalone and in MATCHED arm of MERGE statement.
         */
        MODIFY,

        /**
         * Corresponds to {@link IgniteTypeCoercion#rowTypeCoercion(SqlValidatorScope, SqlNode, int, RelDataType)} called for
         * set operator (UNION, EXCEPT, etc).
         */
        SET_OP,

        /**
         * Corresponds to {@link IgniteTypeCoercion#inOperationCoercion(SqlCallBinding)}.
         */
        IN,

        /**
         * Unspecified context.
         */
        UNSPECIFIED
    }

    private static class Context {
        final ContextType type;

        private Context(ContextType type) {
            this.type = requireNonNull(type, "type");
        }
    }

    /**
     * We need a stack of type coercion "contexts" to distinguish between possibly
     * nested calls for {@link #getWiderTypeFor(List, boolean)} and other type coercion operations.
     */
    private static final class ContextStack {
        private final ArrayDeque<Context> stack = new ArrayDeque<>();

        Context push(ContextType contextType) {
            Context scope = new Context(contextType);
            stack.push(scope);
            return scope;
        }

        void pop(Context current) {
            if (Objects.equals(stack.peek(), current)) {
                stack.pop();
            }
        }

        ContextType currentContext() {
            Context current = stack.peek();
            return current != null ? current.type : ContextType.UNSPECIFIED;
        }
    }
}
