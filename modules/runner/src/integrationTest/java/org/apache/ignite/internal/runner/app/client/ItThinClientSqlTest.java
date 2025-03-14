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

package org.apache.ignite.internal.runner.app.client;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.apache.ignite.lang.IgniteException;
import org.apache.ignite.sql.ColumnMetadata;
import org.apache.ignite.sql.ColumnType;
import org.apache.ignite.sql.NoRowSetExpectedException;
import org.apache.ignite.sql.ResultSet;
import org.apache.ignite.sql.ResultSetMetadata;
import org.apache.ignite.sql.Session;
import org.apache.ignite.sql.SqlRow;
import org.apache.ignite.sql.Statement;
import org.apache.ignite.sql.async.AsyncResultSet;
import org.apache.ignite.table.mapper.Mapper;
import org.apache.ignite.tx.Transaction;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Thin client SQL integration test.
 */
@SuppressWarnings("resource")
public class ItThinClientSqlTest extends ItAbstractThinClientTest {
    @Test
    void testExecuteAsyncSimpleSelect() {
        AsyncResultSet<SqlRow> resultSet = client().sql()
                .createSession()
                .executeAsync(null, "select 1 as num, 'hello' as str")
                .join();

        assertTrue(resultSet.hasRowSet());
        assertFalse(resultSet.wasApplied());
        assertFalse(resultSet.hasMorePages());
        assertEquals(-1, resultSet.affectedRows());
        assertEquals(1, resultSet.currentPageSize());

        SqlRow row = resultSet.currentPage().iterator().next();
        assertEquals(1, row.intValue(0));
        assertEquals("hello", row.stringValue(1));

        ResultSetMetadata metadata = resultSet.metadata();
        assertNotNull(metadata);

        List<ColumnMetadata> columns = metadata.columns();
        assertEquals(2, columns.size());
        assertEquals("NUM", columns.get(0).name());
        assertEquals("STR", columns.get(1).name());
    }

    @Test
    void testExecuteSimpleSelect() {
        ResultSet<SqlRow> resultSet = client().sql()
                .createSession()
                .execute(null, "select 1 as num, 'hello' as str");

        assertTrue(resultSet.hasRowSet());
        assertFalse(resultSet.wasApplied());
        assertEquals(-1, resultSet.affectedRows());

        SqlRow row = resultSet.next();
        assertEquals(1, row.intValue(0));
        assertEquals("hello", row.stringValue(1));

        List<ColumnMetadata> columns = resultSet.metadata().columns();
        assertEquals(2, columns.size());
        assertEquals("NUM", columns.get(0).name());
        assertEquals("STR", columns.get(1).name());
    }

    @Test
    void testTxCorrectness() throws InterruptedException {
        Session ses = client().sql().createSession();

        // Create table.
        ses.execute(null, "CREATE TABLE testExecuteDdlDml(ID INT NOT NULL PRIMARY KEY, VAL VARCHAR)");
        waitForTableOnAllNodes("testExecuteDdlDml");

        // Async
        Transaction tx = client().transactions().begin();

        ses.executeAsync(tx, "INSERT INTO testExecuteDdlDml VALUES (?, ?)",
                Integer.MAX_VALUE, "hello " + Integer.MAX_VALUE).join();

        tx.rollback();

        tx = client().transactions().begin();

        ses.executeAsync(tx, "INSERT INTO testExecuteDdlDml VALUES (?, ?)",
                100, "hello " + Integer.MAX_VALUE).join();

        tx.commit();

        // Sync
        tx = client().transactions().begin();

        ses.executeAsync(tx, "INSERT INTO testExecuteDdlDml VALUES (?, ?)",
                Integer.MAX_VALUE, "hello " + Integer.MAX_VALUE).join();

        tx.rollback();

        tx = client().transactions().begin();

        ses.execute(tx, "INSERT INTO testExecuteDdlDml VALUES (?, ?)",
                200, "hello " + Integer.MAX_VALUE);

        tx.commit();

        // Outdated tx.
        Transaction tx0 = tx;

        assertThrows(CompletionException.class, () -> {
            ses.executeAsync(tx0, "INSERT INTO testExecuteDdlDml VALUES (?, ?)",
                Integer.MAX_VALUE, "hello " + Integer.MAX_VALUE).join();
        });

        assertThrows(IgniteException.class, () -> {
            ses.execute(tx0, "INSERT INTO testExecuteDdlDml VALUES (?, ?)",
                    Integer.MAX_VALUE, "hello " + Integer.MAX_VALUE);
        });

        for (int i = 0; i < 10; i++) {
            ses.execute(null, "INSERT INTO testExecuteDdlDml VALUES (?, ?)", i, "hello " + i);
        }

        ResultSet<SqlRow> selectRes = ses.execute(null, "SELECT * FROM testExecuteDdlDml ORDER BY ID");

        var rows = new ArrayList<SqlRow>();
        selectRes.forEachRemaining(rows::add);

        assertEquals(1 + 1 + 10, rows.size());

        // Delete table.
        ses.execute(null, "DROP TABLE testExecuteDdlDml");
    }

    @Test
    void testExecuteAsyncDdlDml() throws InterruptedException {
        Session session = client().sql().createSession();

        // Create table.
        AsyncResultSet createRes = session
                .executeAsync(null, "CREATE TABLE testExecuteAsyncDdlDml(ID INT PRIMARY KEY, VAL VARCHAR)")
                .join();

        assertFalse(createRes.hasRowSet());
        assertNull(createRes.metadata());
        assertTrue(createRes.wasApplied());
        assertEquals(-1, createRes.affectedRows());
        assertThrows(NoRowSetExpectedException.class, createRes::currentPageSize);

        waitForTableOnAllNodes("testExecuteAsyncDdlDml");

        // Insert data.
        for (int i = 0; i < 10; i++) {
            AsyncResultSet insertRes = session
                    .executeAsync(null, "INSERT INTO testExecuteAsyncDdlDml VALUES (?, ?)", i, "hello " + i)
                    .join();

            assertFalse(insertRes.hasRowSet());
            assertNull(insertRes.metadata());
            assertFalse(insertRes.wasApplied());
            assertEquals(1, insertRes.affectedRows());
            assertThrows(NoRowSetExpectedException.class, insertRes::currentPage);
        }

        // Query data.
        AsyncResultSet<SqlRow> selectRes = session
                .executeAsync(null, "SELECT VAL as MYVALUE, ID, ID + 1 FROM testExecuteAsyncDdlDml ORDER BY ID")
                .join();

        assertTrue(selectRes.hasRowSet());
        assertFalse(selectRes.wasApplied());
        assertEquals(-1, selectRes.affectedRows());
        assertEquals(10, selectRes.currentPageSize());

        List<ColumnMetadata> columns = selectRes.metadata().columns();
        assertEquals(3, columns.size());
        assertEquals("MYVALUE", columns.get(0).name());
        assertEquals("ID", columns.get(1).name());
        // TODO: Uncomment after https://issues.apache.org/jira/browse/IGNITE-19106 Column namings are partially broken
        //assertEquals("ID + 1", columns.get(2).name());

        var rows = new ArrayList<SqlRow>();
        selectRes.currentPage().forEach(rows::add);

        assertEquals(10, rows.size());
        assertEquals("hello 1", rows.get(1).stringValue(0));
        assertEquals(1, rows.get(1).intValue(1));
        assertEquals(2, rows.get(1).intValue(2));

        // Update data.
        AsyncResultSet updateRes = session
                .executeAsync(null, "UPDATE testExecuteAsyncDdlDml SET VAL='upd' WHERE ID < 5").join();

        assertFalse(updateRes.wasApplied());
        assertFalse(updateRes.hasRowSet());
        assertNull(updateRes.metadata());
        assertEquals(5, updateRes.affectedRows());

        // Delete table.
        AsyncResultSet deleteRes = session.executeAsync(null, "DROP TABLE testExecuteAsyncDdlDml").join();

        assertFalse(deleteRes.hasRowSet());
        assertNull(deleteRes.metadata());
        assertTrue(deleteRes.wasApplied());
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void testExecuteDdlDml() throws InterruptedException {
        Session session = client().sql().createSession();

        // Create table.
        ResultSet createRes = session.execute(
                null,
                "CREATE TABLE testExecuteDdlDml(ID INT NOT NULL PRIMARY KEY, VAL VARCHAR)");

        waitForTableOnAllNodes("testExecuteDdlDml");

        assertFalse(createRes.hasRowSet());
        assertNull(createRes.metadata());
        assertTrue(createRes.wasApplied());
        assertEquals(-1, createRes.affectedRows());

        // Insert data.
        for (int i = 0; i < 10; i++) {
            ResultSet insertRes = session.execute(
                    null,
                    "INSERT INTO testExecuteDdlDml VALUES (?, ?)", i, "hello " + i);

            assertFalse(insertRes.hasRowSet());
            assertNull(insertRes.metadata());
            assertFalse(insertRes.wasApplied());
            assertEquals(1, insertRes.affectedRows());
        }

        // Query data.
        ResultSet<SqlRow> selectRes = session
                .execute(null, "SELECT VAL as MYVALUE, ID, ID + 1 FROM testExecuteDdlDml ORDER BY ID");

        assertTrue(selectRes.hasRowSet());
        assertFalse(selectRes.wasApplied());
        assertEquals(-1, selectRes.affectedRows());

        List<ColumnMetadata> columns = selectRes.metadata().columns();
        assertEquals(3, columns.size());

        assertEquals("MYVALUE", columns.get(0).name());
        assertEquals("VAL", columns.get(0).origin().columnName());
        assertEquals("PUBLIC", columns.get(0).origin().schemaName());
        assertEquals("TESTEXECUTEDDLDML", columns.get(0).origin().tableName());
        assertTrue(columns.get(0).nullable());
        assertEquals(String.class, columns.get(0).valueClass());
        assertEquals(ColumnType.STRING, columns.get(0).type());

        assertEquals(ColumnMetadata.UNDEFINED_SCALE, columns.get(0).scale());
        assertEquals(2 << 15, columns.get(0).precision());

        assertEquals("ID", columns.get(1).name());
        assertEquals("ID", columns.get(1).origin().columnName());
        assertEquals("PUBLIC", columns.get(1).origin().schemaName());
        assertEquals("TESTEXECUTEDDLDML", columns.get(1).origin().tableName());
        assertFalse(columns.get(1).nullable());

        // TODO: Uncomment after https://issues.apache.org/jira/browse/IGNITE-19106 Column namings are partially broken
        //assertEquals("ID + 1", columns.get(2).name());
        assertNull(columns.get(2).origin());

        var rows = new ArrayList<SqlRow>();
        selectRes.forEachRemaining(rows::add);

        assertEquals(10, rows.size());
        assertEquals("hello 1", rows.get(1).stringValue(0));
        assertEquals(1, rows.get(1).intValue(1));
        assertEquals(2, rows.get(1).intValue(2));

        // Update data.
        ResultSet updateRes = session.execute(null, "UPDATE testExecuteDdlDml SET VAL='upd' WHERE ID < 5");

        assertFalse(updateRes.wasApplied());
        assertFalse(updateRes.hasRowSet());
        assertNull(updateRes.metadata());
        assertEquals(5, updateRes.affectedRows());

        // Delete table.
        ResultSet deleteRes = session.execute(null, "DROP TABLE testExecuteDdlDml");

        assertFalse(deleteRes.hasRowSet());
        assertNull(deleteRes.metadata());
        assertTrue(deleteRes.wasApplied());
    }

    @Test
    void testFetchNextPage() throws InterruptedException {
        Session session = client().sql().createSession();

        session.executeAsync(null, "CREATE TABLE testFetchNextPage(ID INT PRIMARY KEY, VAL INT)").join();
        waitForTableOnAllNodes("testFetchNextPage");

        for (int i = 0; i < 10; i++) {
            session.executeAsync(null, "INSERT INTO testFetchNextPage VALUES (?, ?)", i, i).join();
        }

        Statement statement = client().sql().statementBuilder().pageSize(4).query("SELECT ID FROM testFetchNextPage ORDER BY ID").build();

        AsyncResultSet<SqlRow> asyncResultSet = session.executeAsync(null, statement).join();

        assertEquals(4, asyncResultSet.currentPageSize());
        assertTrue(asyncResultSet.hasMorePages());
        assertEquals(0, asyncResultSet.currentPage().iterator().next().intValue(0));

        asyncResultSet.fetchNextPage().toCompletableFuture().join();

        assertEquals(4, asyncResultSet.currentPageSize());
        assertTrue(asyncResultSet.hasMorePages());
        assertEquals(4, asyncResultSet.currentPage().iterator().next().intValue(0));

        asyncResultSet.fetchNextPage().toCompletableFuture().join();

        assertEquals(2, asyncResultSet.currentPageSize());
        assertFalse(asyncResultSet.hasMorePages());
        assertEquals(8, asyncResultSet.currentPage().iterator().next().intValue(0));
    }

    @Test
    void testInvalidSqlThrowsException() {
        CompletionException ex = assertThrows(
                CompletionException.class,
                () -> client().sql().createSession().executeAsync(null, "select x from bad").join());

        var clientEx = (IgniteException) ex.getCause();

        assertThat(clientEx.getMessage(), Matchers.containsString("Object 'BAD' not found"));
    }

    @Test
    void testTransactionRollbackRevertsSqlUpdate() throws InterruptedException {
        Session session = client().sql().createSession();

        session.executeAsync(null, "CREATE TABLE testTx(ID INT PRIMARY KEY, VAL INT)").join();
        waitForTableOnAllNodes("testTx");

        session.executeAsync(null, "INSERT INTO testTx VALUES (1, 1)").join();

        Transaction tx = client().transactions().begin();
        session.executeAsync(tx, "UPDATE testTx SET VAL=2").join();
        tx.rollback();

        var res = session.executeAsync(null, "SELECT VAL FROM testTx").join();
        assertEquals(1, res.currentPage().iterator().next().intValue(0));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testResultSetMapping(boolean useStatement) {
        Session session = client().sql().createSession();
        String query = "select 123 + ? as num, 'Hello!' as str";

        ResultSet<Pojo> resultSet = useStatement
                ? session.execute(null, Mapper.of(Pojo.class), client().sql().statementBuilder().query(query).build(), 10)
                : session.execute(null, Mapper.of(Pojo.class), query, 10);

        assertTrue(resultSet.hasRowSet());

        Pojo row = resultSet.next();

        assertEquals(133, row.num);
        assertEquals("Hello!", row.str);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testResultSetMappingAsync(boolean useStatement) {
        Session session = client().sql().createSession();
        String query = "select 1 as num, concat('hello ', ?) as str";

        AsyncResultSet<Pojo> resultSet = useStatement
                ? session.executeAsync(null, Mapper.of(Pojo.class), client().sql().statementBuilder().query(query).build(), "world").join()
                : session.executeAsync(null, Mapper.of(Pojo.class), query, "world").join();

        assertTrue(resultSet.hasRowSet());
        assertEquals(1, resultSet.currentPageSize());

        Pojo row = resultSet.currentPage().iterator().next();

        assertEquals(1, row.num);
        assertEquals("hello world", row.str);
    }


    @Test
    void testResultSetMappingColumnNameMismatch() {
        String query = "select 1 as foo, 2 as bar";

        ResultSet<Pojo> resultSet = client().sql().createSession().execute(null, Mapper.of(Pojo.class), query);
        Pojo row = resultSet.next();

        assertEquals(0, row.num);
        assertNull(row.str);
    }

    @Test
    void testAllColumnTypes() throws InterruptedException {
        Session session = client().sql().createSession();

        String createTable = "CREATE TABLE testAllColumnTypes("
                + "ID INT PRIMARY KEY, "
                + "VAL_BYTE TINYINT, "
                + "VAL_SHORT SMALLINT, "
                + "VAL_INT INT, "
                + "VAL_LONG BIGINT, "
                + "VAL_FLOAT REAL, "
                + "VAL_DOUBLE DOUBLE, "
                + "VAL_DECIMAL DECIMAL(10, 2), "
                + "VAL_STRING VARCHAR, "
                + "VAL_DATE DATE, "
                + "VAL_TIME TIME, "
                + "VAL_TIMESTAMP TIMESTAMP, "
                + "VAL_UUID UUID, "
                + "VAL_BYTES BINARY)";

        session.execute(null, createTable);

        waitForTableOnAllNodes("testAllColumnTypes");

        String insertData = "INSERT INTO testAllColumnTypes VALUES ("
                + "1, "
                + "1, "
                + "2, "
                + "3, "
                + "4, "
                + "5.5, "
                + "6.6, "
                + "7.77, "
                + "'foo', "
                + "'2020-01-01', "
                + "'12:00:00', "
                + "'2020-01-01 12:00:00', "
                + "'10000000-2000-3000-4000-500000000000', "
                + "x'42')";

        session.execute(null, insertData);

        var resultSet = session.execute(null, "SELECT * FROM testAllColumnTypes");
        assertTrue(resultSet.hasRowSet());

        var row = resultSet.next();
        var meta = resultSet.metadata();

        assertNotNull(meta);
        assertEquals(14, meta.columns().size());

        assertEquals(1, row.intValue(0));
        assertEquals(1, row.byteValue(1));
        assertEquals(2, row.shortValue(2));
        assertEquals(3, row.intValue(3));
        assertEquals(4, row.longValue(4));
        assertEquals(5.5f, row.floatValue(5));
        assertEquals(6.6, row.doubleValue(6));
        assertEquals(new BigDecimal("7.77"), row.value(7));
        assertEquals("foo", row.stringValue(8));
        assertEquals(LocalDate.of(2020, 1, 1), row.value(9));
        assertEquals(LocalTime.of(12, 0, 0), row.value(10));
        assertEquals(LocalDateTime.of(2020, 1, 1, 12, 0, 0), row.value(11));
        assertEquals(UUID.fromString("10000000-2000-3000-4000-500000000000"), row.value(12));
        assertArrayEquals(new byte[]{0x42}, row.value(13));

        assertEquals(ColumnType.INT8, meta.columns().get(1).type());
        assertEquals(ColumnType.INT16, meta.columns().get(2).type());
        assertEquals(ColumnType.INT32, meta.columns().get(3).type());
        assertEquals(ColumnType.INT64, meta.columns().get(4).type());
        assertEquals(ColumnType.FLOAT, meta.columns().get(5).type());
        assertEquals(ColumnType.DOUBLE, meta.columns().get(6).type());
        assertEquals(ColumnType.DECIMAL, meta.columns().get(7).type());
        assertEquals(ColumnType.STRING, meta.columns().get(8).type());
        assertEquals(ColumnType.DATE, meta.columns().get(9).type());
        assertEquals(ColumnType.TIME, meta.columns().get(10).type());
        assertEquals(ColumnType.DATETIME, meta.columns().get(11).type());
        assertEquals(ColumnType.UUID, meta.columns().get(12).type());
        assertEquals(ColumnType.BYTE_ARRAY, meta.columns().get(13).type());
    }

    private static class Pojo {
        public long num;

        public String str;
    }
}
