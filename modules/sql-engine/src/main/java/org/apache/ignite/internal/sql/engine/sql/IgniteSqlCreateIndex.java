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
import org.apache.calcite.sql.SqlCall;
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

/**
 * Parse tree for {@code CREATE INDEX} statement.
 */
public class IgniteSqlCreateIndex extends SqlCreate {
    /** Idx name. */
    private final SqlIdentifier idxName;

    /** Table name.  */
    private final SqlIdentifier tblName;

    private final IgniteSqlIndexType type;

    /** Columns involved. */
    private final SqlNodeList columnList;

    /** SqlOperator type. */
    private static final SqlOperator OPERATOR =
            new SqlSpecialOperator("CREATE INDEX", SqlKind.CREATE_INDEX);

    /** Creates a SqlCreateIndex. */
    public IgniteSqlCreateIndex(SqlParserPos pos, boolean ifNotExists, SqlIdentifier idxName, SqlIdentifier tblName,
            IgniteSqlIndexType type, SqlNodeList columnList) {
        super(OPERATOR, pos, false, ifNotExists);
        this.idxName = Objects.requireNonNull(idxName, "index name");
        this.tblName = Objects.requireNonNull(tblName, "table name");
        this.type = Objects.requireNonNull(type, "type");
        this.columnList = columnList;
    }

    /** {@inheritDoc} */
    @Override public List<SqlNode> getOperandList() {
        return ImmutableNullableList.of(idxName, tblName, columnList);
    }

    /** {@inheritDoc} */
    @Override public void unparse(SqlWriter writer, int leftPrec, int rightPrec) {
        writer.keyword("CREATE");

        writer.keyword("INDEX");

        if (ifNotExists) {
            writer.keyword("IF NOT EXISTS");
        }

        idxName.unparse(writer, 0, 0);

        writer.keyword("ON");

        tblName.unparse(writer, 0, 0);

        if (type != IgniteSqlIndexType.IMPLICIT_TREE) {
            writer.keyword("USING");

            writer.keyword(type.name());
        }

        SqlWriter.Frame frame = writer.startList("(", ")");

        for (SqlNode c : columnList) {
            writer.sep(",");

            boolean desc = false;

            if (c.getKind() == SqlKind.DESCENDING) {
                c = ((SqlCall) c).getOperandList().get(0);
                desc = true;
            }

            c.unparse(writer, 0, 0);

            if (desc) {
                writer.keyword("DESC");
            }
        }

        writer.endList(frame);
    }

    public SqlIdentifier indexName() {
        return idxName;
    }

    public SqlIdentifier tableName() {
        return tblName;
    }

    public IgniteSqlIndexType type() {
        return type;
    }

    public SqlNodeList columnList() {
        return columnList;
    }

    public boolean ifNotExists() {
        return ifNotExists;
    }
}
