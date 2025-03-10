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

package org.apache.ignite.internal.jdbc;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import org.apache.ignite.sql.ColumnType;

/**
 * Utils for type conversion related to JDBC.
 */
public class JdbcConverterUtils {
    /**
     * Column type to Java class for JDBC.
     */
    public static Class<?> columnTypeToJdbcClass(ColumnType type) {
        assert type != null;

        switch (type) {
            case DATE:
                return Date.class;
            case TIME:
                return Time.class;
            case DATETIME:
            case TIMESTAMP:
                return Timestamp.class;
            default:
                return ColumnType.columnTypeToClass(type);
        }
    }
}
