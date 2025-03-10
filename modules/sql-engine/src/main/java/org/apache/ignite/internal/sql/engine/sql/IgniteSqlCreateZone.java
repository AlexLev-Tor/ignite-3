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

package org.apache.ignite.internal.sql.engine.sql;

import java.util.List;
import java.util.Objects;
import org.apache.calcite.sql.SqlCreate;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlSpecialOperator;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.util.ImmutableNullableList;
import org.jetbrains.annotations.Nullable;

/**
 * Parse tree for {@code CREATE ZONE} statement with Ignite specific features.
 */
public class IgniteSqlCreateZone extends SqlCreate {
    private final SqlIdentifier name;

    private final @Nullable SqlIdentifier engineName;

    private final @Nullable SqlNodeList createOptionList;

    private static final SqlOperator OPERATOR = new SqlSpecialOperator("CREATE ZONE", SqlKind.OTHER_DDL);

    /** Creates a SqlCreateZone. */
    public IgniteSqlCreateZone(
            SqlParserPos pos,
            boolean ifNotExists,
            SqlIdentifier name,
            @Nullable SqlNodeList createOptionList,
            @Nullable SqlIdentifier engineName
    ) {
        super(OPERATOR, pos, false, ifNotExists);

        assert engineName == null || engineName.isSimple() : engineName;

        this.name = Objects.requireNonNull(name, "name");
        this.createOptionList = createOptionList;
        this.engineName = engineName;
    }

    /** {@inheritDoc} */
    @SuppressWarnings("nullness")
    @Override
    public List<SqlNode> getOperandList() {
        return ImmutableNullableList.of(name, createOptionList);
    }

    /** {@inheritDoc} */
    @Override
    public void unparse(SqlWriter writer, int leftPrec, int rightPrec) {
        writer.keyword("CREATE");
        writer.keyword("ZONE");
        if (ifNotExists) {
            writer.keyword("IF NOT EXISTS");
        }

        name.unparse(writer, leftPrec, rightPrec);

        if (engineName != null) {
            writer.keyword("ENGINE");

            engineName.unparse(writer, 0, 0);
        }

        if (createOptionList != null) {
            writer.keyword("WITH");

            createOptionList.unparse(writer, 0, 0);
        }
    }

    /**
     * Get name of the distribution zone.
     */
    public SqlIdentifier name() {
        return name;
    }

    /**
     * Get list of the specified options to create distribution zone with.
     */
    public SqlNodeList createOptionList() {
        return createOptionList;
    }

    /**
     * Get whether the IF NOT EXISTS is specified.
     */
    public boolean ifNotExists() {
        return ifNotExists;
    }

    /**
     * Returns an engine name of the zone to create.
     */
    public @Nullable SqlIdentifier engineName() {
        return engineName;
    }
}
