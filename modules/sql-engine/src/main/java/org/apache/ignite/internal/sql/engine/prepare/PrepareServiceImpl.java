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

import static org.apache.ignite.internal.sql.engine.prepare.CacheKey.EMPTY_CLASS_ARRAY;
import static org.apache.ignite.internal.sql.engine.prepare.PlannerHelper.optimize;
import static org.apache.ignite.internal.sql.engine.trait.TraitUtils.distributionPresent;
import static org.apache.ignite.lang.ErrorGroups.Sql.PLANNING_TIMEOUT_ERR;
import static org.apache.ignite.lang.ErrorGroups.Sql.STMT_VALIDATION_ERR;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.sql.SqlDdl;
import org.apache.calcite.sql.SqlExplain;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.logger.Loggers;
import org.apache.ignite.internal.sql.api.ColumnMetadataImpl;
import org.apache.ignite.internal.sql.api.ResultSetMetadataImpl;
import org.apache.ignite.internal.sql.engine.prepare.ddl.DdlSqlToCommandConverter;
import org.apache.ignite.internal.sql.engine.rel.IgniteRel;
import org.apache.ignite.internal.sql.engine.schema.SchemaUpdateListener;
import org.apache.ignite.internal.sql.engine.sql.ParsedResult;
import org.apache.ignite.internal.sql.engine.util.BaseQueryContext;
import org.apache.ignite.internal.sql.engine.util.TypeUtils;
import org.apache.ignite.internal.storage.DataStorageManager;
import org.apache.ignite.internal.thread.NamedThreadFactory;
import org.apache.ignite.internal.util.ExceptionUtils;
import org.apache.ignite.lang.IgniteException;
import org.apache.ignite.sql.ColumnMetadata;
import org.apache.ignite.sql.ResultSetMetadata;
import org.apache.ignite.sql.SqlException;
import org.jetbrains.annotations.Nullable;

/**
 * An implementation of the {@link PrepareService} that uses a Calcite-based query planner to validate and optimize a given query.
 */
public class PrepareServiceImpl implements PrepareService, SchemaUpdateListener {
    private static final IgniteLogger LOG = Loggers.forClass(PrepareServiceImpl.class);

    /** Default planner timeout, in ms. */
    public static final long DEFAULT_PLANNER_TIMEOUT = 15000L;

    private static final long THREAD_TIMEOUT_MS = 60_000;

    private static final int THREAD_COUNT = 4;

    private final DdlSqlToCommandConverter ddlConverter;

    private final ConcurrentMap<CacheKey, CompletableFuture<QueryPlan>> cache;

    private final String nodeName;

    private volatile ThreadPoolExecutor planningPool;

    private final long plannerTimeout;

    /**
     * Factory method.
     *
     * @param nodeName Name of the current Ignite node. Will be used in thread factory as part of the thread name.
     * @param cacheSize Size of the cache of query plans. Should be non negative.
     * @param dataStorageManager Data storage manager.
     * @param dataStorageFields Data storage fields. Mapping: Data storage name -> field name -> field type.
     */
    public static PrepareServiceImpl create(
            String nodeName,
            int cacheSize,
            DataStorageManager dataStorageManager,
            Map<String, Map<String, Class<?>>> dataStorageFields
    ) {
        return new PrepareServiceImpl(
                nodeName,
                cacheSize,
                new DdlSqlToCommandConverter(dataStorageFields, dataStorageManager::defaultDataStorage),
                DEFAULT_PLANNER_TIMEOUT
        );
    }

    /**
     * Constructor.
     *
     * @param nodeName Name of the current Ignite node. Will be used in thread factory as part of the thread name.
     * @param cacheSize Size of the cache of query plans. Should be non negative.
     * @param ddlConverter A converter of the DDL-related AST to the actual command.
     * @param plannerTimeout Timeout in milliseconds to planning.
     */
    public PrepareServiceImpl(
            String nodeName,
            int cacheSize,
            DdlSqlToCommandConverter ddlConverter,
            long plannerTimeout
    ) {
        this.nodeName = nodeName;
        this.ddlConverter = ddlConverter;
        this.plannerTimeout = plannerTimeout;

        cache = Caffeine.newBuilder()
                .maximumSize(cacheSize)
                .<CacheKey, CompletableFuture<QueryPlan>>build()
                .asMap();
    }

    /** {@inheritDoc} */
    @Override
    public void start() {
        planningPool = new ThreadPoolExecutor(
                THREAD_COUNT,
                THREAD_COUNT,
                THREAD_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new NamedThreadFactory(NamedThreadFactory.threadPrefix(nodeName, "sql-planning-pool"), LOG)
        );

        planningPool.allowCoreThreadTimeOut(true);
    }

    /** {@inheritDoc} */
    @Override
    public void stop() throws Exception {
        planningPool.shutdownNow();
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<QueryPlan> prepareAsync(ParsedResult parsedResult, BaseQueryContext ctx) {
        CompletableFuture<QueryPlan> result;

        PlanningContext planningContext = PlanningContext.builder()
                .parentContext(ctx)
                .query(parsedResult.originalQuery())
                .plannerTimeout(plannerTimeout)
                .build();

        result = prepareAsync0(parsedResult, planningContext);

        return result.exceptionally(ex -> {
                    Throwable th = ExceptionUtils.unwrapCause(ex);
                    if (planningContext.timeouted() && th instanceof RelOptPlanner.CannotPlanException) {
                        throw new SqlException(PLANNING_TIMEOUT_ERR);
                    }

                    throw new IgniteException(th);
                }
        );
    }

    private CompletableFuture<QueryPlan> prepareAsync0(ParsedResult parsedResult, PlanningContext planningContext) {
        try {
            switch (parsedResult.queryType()) {
                case QUERY:
                    return prepareQuery(parsedResult, planningContext);
                case DDL:
                    return prepareDdl(parsedResult, planningContext);
                case DML:
                    return prepareDml(parsedResult, planningContext);
                case EXPLAIN:
                    return prepareExplain(parsedResult, planningContext);
                default:
                    throw new AssertionError("Unexpected queryType=" + parsedResult.queryType());
            }
        } catch (CalciteContextException e) {
            throw new SqlException(STMT_VALIDATION_ERR, "Failed to validate query. " + e.getMessage(), e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onSchemaUpdated() {
        cache.clear();
    }

    private CompletableFuture<QueryPlan> prepareDdl(ParsedResult parsedResult, PlanningContext ctx) {
        SqlNode sqlNode = parsedResult.parsedTree();

        assert sqlNode instanceof SqlDdl : sqlNode == null ? "null" : sqlNode.getClass().getName();

        return CompletableFuture.completedFuture(new DdlPlan(ddlConverter.convert((SqlDdl) sqlNode, ctx)));
    }

    private CompletableFuture<QueryPlan> prepareExplain(ParsedResult parsedResult, PlanningContext ctx) {
        return CompletableFuture.supplyAsync(() -> {
            IgnitePlanner planner = ctx.planner();

            SqlNode sqlNode = parsedResult.parsedTree();

            assert single(sqlNode);

            // Validate
            // We extract query subtree inside the validator.
            SqlNode explainNode = planner.validate(sqlNode);
            // Extract validated query.
            SqlNode validNode = ((SqlExplain) explainNode).getExplicandum();

            // Convert to Relational operators graph
            IgniteRel igniteRel = optimize(validNode, planner);

            String plan = RelOptUtil.toString(igniteRel, SqlExplainLevel.ALL_ATTRIBUTES);

            return new ExplainPlan(plan);
        }, planningPool);
    }

    private static boolean single(SqlNode sqlNode) {
        return !(sqlNode instanceof SqlNodeList);
    }

    private CompletableFuture<QueryPlan> prepareQuery(ParsedResult parsedResult, PlanningContext ctx) {
        CacheKey key = createCacheKey(parsedResult, ctx);

        CompletableFuture<QueryPlan> planFut = cache.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
            IgnitePlanner planner = ctx.planner();

            SqlNode sqlNode = parsedResult.parsedTree();

            assert single(sqlNode);

            // Validate
            ValidationResult validated = planner.validateAndGetTypeMetadata(sqlNode);

            SqlNode validatedNode = validated.sqlNode();

            IgniteRel igniteRel = optimize(validatedNode, planner);

            // Split query plan to query fragments.
            List<Fragment> fragments = new Splitter().go(igniteRel);

            QueryTemplate template = new QueryTemplate(fragments);

            return new MultiStepQueryPlan(template, resultSetMetadata(validated.dataType(), validated.origins()));
        }, planningPool));

        return planFut.thenApply(QueryPlan::copy);
    }

    private CompletableFuture<QueryPlan> prepareDml(ParsedResult parsedResult, PlanningContext ctx) {
        var key = createCacheKey(parsedResult, ctx);

        CompletableFuture<QueryPlan> planFut = cache.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
            IgnitePlanner planner = ctx.planner();

            SqlNode sqlNode = parsedResult.parsedTree();

            assert single(sqlNode);

            // Validate
            SqlNode validatedNode = planner.validate(sqlNode);

            // Convert to Relational operators graph
            IgniteRel igniteRel = optimize(validatedNode, planner);

            // Split query plan to query fragments.
            List<Fragment> fragments = new Splitter().go(igniteRel);

            QueryTemplate template = new QueryTemplate(fragments);

            return new MultiStepDmlPlan(template);
        }, planningPool));

        return planFut.thenApply(QueryPlan::copy);
    }

    private static CacheKey createCacheKey(ParsedResult parsedResult, PlanningContext ctx) {
        boolean distributed = distributionPresent(ctx.config().getTraitDefs());

        Class[] paramTypes = ctx.parameters().length == 0
                ? EMPTY_CLASS_ARRAY :
                Arrays.stream(ctx.parameters()).map(p -> (p != null) ? p.getClass() : Void.class).toArray(Class[]::new);

        return new CacheKey(ctx.schemaName(), parsedResult.normalizedQuery(), distributed, paramTypes);
    }

    private ResultSetMetadata resultSetMetadata(
            RelDataType rowType,
            @Nullable List<List<String>> origins
    ) {
        return new LazyResultSetMetadata(
                () -> {
                    List<ColumnMetadata> fieldsMeta = new ArrayList<>(rowType.getFieldCount());

                    for (int i = 0; i < rowType.getFieldCount(); ++i) {
                        RelDataTypeField fld = rowType.getFieldList().get(i);

                        ColumnMetadataImpl fldMeta = new ColumnMetadataImpl(
                                fld.getName(),
                                TypeUtils.columnType(fld.getType()),
                                fld.getType().getPrecision(),
                                fld.getType().getScale(),
                                fld.getType().isNullable(),
                                origins == null ? null : ColumnMetadataImpl.originFromList(origins.get(i))
                        );

                        fieldsMeta.add(fldMeta);
                    }

                    return new ResultSetMetadataImpl(fieldsMeta);
                }
        );
    }
}
