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

package org.apache.ignite.internal.sql.engine.exec.exp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.function.Supplier;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.ignite.internal.sql.engine.type.IgniteTypeSystem;
import org.apache.ignite.sql.SqlException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Sql functions test.
 */
public class IgniteSqlFunctionsTest {
    @Test
    public void testBigDecimalToString() {
        assertNull(IgniteSqlFunctions.toString((BigDecimal) null));

        assertEquals(
                "10",
                IgniteSqlFunctions.toString(BigDecimal.valueOf(10))
        );

        assertEquals(
                "9223372036854775807",
                IgniteSqlFunctions.toString(BigDecimal.valueOf(Long.MAX_VALUE))
        );

        assertEquals(
                "340282350000000000000000000000000000000",
                IgniteSqlFunctions.toString(new BigDecimal(String.valueOf(Float.MAX_VALUE)))
        );

        assertEquals(
                "-340282346638528860000000000000000000000",
                IgniteSqlFunctions.toString(BigDecimal.valueOf(-Float.MAX_VALUE))
        );
    }

    @Test
    public void testBooleanPrimitiveToBigDecimal() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> IgniteSqlFunctions.toBigDecimal(true, 10, 10));
    }

    @Test
    public void testBooleanObjectToBigDecimal() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> IgniteSqlFunctions.toBigDecimal(Boolean.valueOf(true), 10, 10));
    }

    @Test
    public void testPrimitiveToDecimal() {
        assertEquals(
                new BigDecimal(10),
                IgniteSqlFunctions.toBigDecimal((byte) 10, 10, 0)
        );

        assertEquals(
                new BigDecimal(10),
                IgniteSqlFunctions.toBigDecimal((short) 10, 10, 0)
        );

        assertEquals(
                new BigDecimal(10),
                IgniteSqlFunctions.toBigDecimal(10, 10, 0)
        );

        assertEquals(
                new BigDecimal("10.0"),
                IgniteSqlFunctions.toBigDecimal(10L, 10, 1)
        );

        assertEquals(
                new BigDecimal("10.101"),
                IgniteSqlFunctions.toBigDecimal(10.101f, 10, 3)
        );

        assertEquals(
                new BigDecimal("10.101"),
                IgniteSqlFunctions.toBigDecimal(10.101d, 10, 3)
        );
    }

    @Test
    public void testObjectToDecimal() {
        assertNull(IgniteSqlFunctions.toBigDecimal((Object) null, 10, 0));

        assertNull(IgniteSqlFunctions.toBigDecimal((Double) null, 10, 0));

        assertNull(IgniteSqlFunctions.toBigDecimal((String) null, 10, 0));

        assertEquals(
                new BigDecimal(10),
                IgniteSqlFunctions.toBigDecimal(Byte.valueOf("10"), 10, 0)
        );

        assertEquals(
                new BigDecimal(10),
                IgniteSqlFunctions.toBigDecimal(Short.valueOf("10"), 10, 0)
        );

        assertEquals(
                new BigDecimal(10),
                IgniteSqlFunctions.toBigDecimal(Integer.valueOf(10), 10, 0)
        );

        assertEquals(
                new BigDecimal("10.0"),
                IgniteSqlFunctions.toBigDecimal(Long.valueOf(10L), 10, 1)
        );

        assertEquals(
                new BigDecimal("10.101"),
                IgniteSqlFunctions.toBigDecimal(Float.valueOf(10.101f), 10, 3)
        );

        assertEquals(
                new BigDecimal("10.101"),
                IgniteSqlFunctions.toBigDecimal(Double.valueOf(10.101d), 10, 3)
        );
    }

    /** Access of dynamic parameter value - parameter is not transformed. */
    @Test
    public void testToBigDecimalFromObject() {
        Object value = new BigDecimal("100.1");
        int defaultPrecision = IgniteTypeSystem.INSTANCE.getDefaultPrecision(SqlTypeName.DECIMAL);

        assertSame(value, IgniteSqlFunctions.toBigDecimal(value, defaultPrecision, 0));
    }

    /** Tests for decimal conversion function. */
    @ParameterizedTest
    @CsvSource({
            // input, precision, scale, result (number or error)
            "0, 1, 0, 0",
            "0, 1, 2, 0.00",
            "0, 2, 2, 0.00",
            "0, 2, 4, 0.0000",

            "1, 1, 0, 1",
            "1, 3, 2, 1.00",
            "1, 2, 2, overflow",

            "0.1, 1, 1, 0.1",

            "0.12, 2, 1, 0.1",
            "0.12, 2, 2, 0.12",
            "0.123, 2, 2, 0.12",
            "0.123, 2, 1, 0.1",
            "0.123, 5, 5, 0.12300",

            "1.23, 2, 1, 1.2",

            "10, 2, 0, 10",
            "10.0, 2, 0, 10",
            "10, 3, 0, 10",

            "10.01, 2, 0, 10",
            "10.1, 3, 0, 10",
            "10.11, 2, 1, overflow",
            "10.11, 3, 1, 10.1",
            "10.00, 3, 1, 10.0",

            "100.0, 3, 1, overflow",

            "100.01, 4, 1, 100.0",
            "100.01, 4, 0, 100",
            "100.111, 3, 1, overflow",

            "11.1, 5, 3, 11.100",
            "11.100, 5, 3, 11.100",

            "-10.1, 3, 1, -10.1",
            "-10.1, 2, 0, -10",
            "-10.1, 2, 1, overflow",
    })
    public void testConvertDecimal(String input, int precision, int scale, String result) {
        Supplier<BigDecimal> convert = () -> IgniteSqlFunctions.convertDecimal(new BigDecimal(input), precision, scale);

        if (!"overflow".equalsIgnoreCase(result)) {
            BigDecimal expected = convert.get();
            assertEquals(new BigDecimal(result), expected);
        } else {
            assertThrows(SqlException.class, convert::get);
        }
    }
}
