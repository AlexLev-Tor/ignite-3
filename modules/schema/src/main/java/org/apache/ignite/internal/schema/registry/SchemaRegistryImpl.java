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

package org.apache.ignite.internal.schema.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.apache.ignite.internal.schema.BinaryRow;
import org.apache.ignite.internal.schema.SchemaDescriptor;
import org.apache.ignite.internal.schema.SchemaRegistry;
import org.apache.ignite.internal.schema.mapping.ColumnMapper;
import org.apache.ignite.internal.schema.mapping.ColumnMapping;
import org.apache.ignite.internal.schema.row.Row;
import org.jetbrains.annotations.Nullable;

/**
 * Caching registry of actual schema descriptors for a table.
 */
public class SchemaRegistryImpl implements SchemaRegistry {
    /** Cached schemas. */
    private final ConcurrentNavigableMap<Integer, SchemaDescriptor> schemaCache = new ConcurrentSkipListMap<>();

    /** Column mappers cache. */
    private final Map<Long, ColumnMapper> mappingCache = new ConcurrentHashMap<>();

    /** Schema store. */
    private final IntFunction<CompletableFuture<SchemaDescriptor>> history;

    /** The method to provide the latest schema version on cluster. */
    private final Supplier<CompletableFuture<Integer>> latestVersionStore;

    /**
     * Constructor.
     *
     * @param history            Schema history.
     * @param latestVersionStore The method to provide the latest version of the schema.
     * @param initialSchema      Initial schema.
     */
    public SchemaRegistryImpl(
            IntFunction<CompletableFuture<SchemaDescriptor>> history,
            Supplier<CompletableFuture<Integer>> latestVersionStore,
            SchemaDescriptor initialSchema
    ) {
        this.history = history;
        this.latestVersionStore = latestVersionStore;

        schemaCache.put(initialSchema.version(), initialSchema);
    }

    /** {@inheritDoc} */
    @Override
    public SchemaDescriptor schema(int ver) {
        if (ver == 0) {
            // Use last version (any version may be used) for 0 version, that mean row doesn't contain value.
            ver = schemaCache.lastKey();
        }

        SchemaDescriptor desc = schemaCache.get(ver);

        if (desc != null) {
            return desc;
        }

        CompletableFuture<SchemaDescriptor> descFut = history.apply(ver);

        if (descFut != null) {
            // TODO: remove blocking code https://issues.apache.org/jira/browse/IGNITE-17931
            desc = descFut.join();
        }

        if (desc != null) {
            schemaCache.putIfAbsent(ver, desc);

            return desc;
        }

        if (schemaCache.lastKey() < ver || ver <= 0) {
            throw new SchemaRegistryException("Incorrect schema version requested: ver=" + ver);
        } else {
            throw new SchemaRegistryException("Failed to find schema: ver=" + ver);
        }
    }

    /** {@inheritDoc} */
    @Override
    public @Nullable SchemaDescriptor schema() {
        return schema(schemaCache.lastKey());
    }

    /** {@inheritDoc} */
    @Override
    public @Nullable SchemaDescriptor schemaCached(int ver) {
        return schemaCache.get(ver);
    }

    /** {@inheritDoc} */
    @Override
    public SchemaDescriptor waitLatestSchema() {
        // TODO: remove blocking code https://issues.apache.org/jira/browse/IGNITE-17931
        int lastVer0 = latestVersionStore.get().join();
        Integer lastLocalVer = schemaCache.lastKey();

        assert lastLocalVer <= lastVer0 : "Cached schema is earlier than consensus [lastVer=" + lastLocalVer
            + ", consLastVer=" + lastVer0 + ']';

        return schema(lastVer0);
    }

    /** {@inheritDoc} */
    @Override
    public int lastSchemaVersion() {
        return schemaCache.lastKey();
    }

    /** {@inheritDoc} */
    @Override
    public Row resolve(BinaryRow row) {
        final SchemaDescriptor curSchema = waitLatestSchema();

        return resolveInternal(row, curSchema);
    }

    /** {@inheritDoc} */
    @Override
    public Row resolve(BinaryRow row, SchemaDescriptor schemaDescriptor) {
        return resolveInternal(row, schemaDescriptor);
    }

    @Override
    public List<Row> resolve(Collection<BinaryRow> binaryRows) {
        SchemaDescriptor curSchema = waitLatestSchema();

        var rows = new ArrayList<Row>(binaryRows.size());

        for (BinaryRow row : binaryRows) {
            rows.add(row == null ? null : resolveInternal(row, curSchema));
        }

        return rows;
    }

    /**
     * Resolves a schema for row. The method is optimal when the latest schema is already got.
     *
     * @param row Binary row.
     * @param curSchema The latest available local schema.
     * @return Schema-aware row.
     * @throws SchemaRegistryException if no schema exists for the given row.
     */
    private Row resolveInternal(BinaryRow row, SchemaDescriptor curSchema) {
        if (curSchema == null) {
            throw new SchemaRegistryException("No schema found for the row: schemaVersion=" + row.schemaVersion());
        } else if (row.schemaVersion() == 0 || curSchema.version() == row.schemaVersion()) {
            return new Row(curSchema, row);
        }

        final SchemaDescriptor rowSchema = schema(row.schemaVersion());

        ColumnMapper mapping = resolveMapping(curSchema, rowSchema);

        return new UpgradingRowAdapter(curSchema, rowSchema, row, mapping);
    }

    /**
     * Get cached or create a column mapper that maps column of current schema to the row schema as they may have different schema indices.
     *
     * @param curSchema Target schema.
     * @param rowSchema Row schema.
     * @return Column mapper for target schema.
     */
    ColumnMapper resolveMapping(SchemaDescriptor curSchema, SchemaDescriptor rowSchema) {
        assert curSchema.version() > rowSchema.version();

        if (curSchema.version() == rowSchema.version() + 1) {
            return curSchema.columnMapping();
        }

        final long mappingKey = (((long) curSchema.version()) << 32) | (rowSchema.version());

        ColumnMapper mapping;

        if ((mapping = mappingCache.get(mappingKey)) != null) {
            return mapping;
        }

        mapping = schema(rowSchema.version() + 1).columnMapping();

        for (int i = rowSchema.version() + 2; i <= curSchema.version(); i++) {
            mapping = ColumnMapping.mergeMapping(mapping, schema(i));
        }

        mappingCache.putIfAbsent(mappingKey, mapping);

        return mapping;
    }

    /**
     * Registers new schema.
     *
     * @param desc Schema descriptor.
     * @throws SchemaRegistrationConflictException If schema of provided version was already registered.
     * @throws SchemaRegistryException If schema of incorrect version provided.
     */
    public void onSchemaRegistered(SchemaDescriptor desc) {
        int lastVer = schemaCache.lastKey();

        if (desc.version() != lastVer + 1) {
            if (desc.version() > 0 && desc.version() <= lastVer) {
                throw new SchemaRegistrationConflictException("Schema with given version has been already registered: " + desc.version());
            }

            throw new SchemaRegistryException("Try to register schema of wrong version: ver=" + desc.version() + ", lastVer=" + lastVer);
        }

        schemaCache.put(desc.version(), desc);
    }

    /**
     * Cleanup given schema version from history.
     *
     * @param ver Schema version to remove.
     * @throws SchemaRegistryException If incorrect schema version provided.
     */
    public void onSchemaDropped(int ver) {
        int lastVer = schemaCache.lastKey();

        if (ver >= lastVer || ver <= 0 || schemaCache.keySet().first() < ver) {
            throw new SchemaRegistryException("Incorrect schema version to clean up to: " + ver);
        }

        if (schemaCache.remove(ver) != null) {
            mappingCache.keySet().removeIf(k -> (k & 0xFFFF_FFFFL) == ver);
        }
    }

    /**
     * For test purposes only.
     *
     * @return ColumnMapping cache.
     */
    Map<Long, ColumnMapper> mappingCache() {
        return mappingCache;
    }
}
