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

package org.apache.ignite.internal.schema;

import static org.apache.ignite.internal.binarytuple.BinaryTupleCommon.ROW_HAS_VALUE_FLAG;

import java.nio.ByteBuffer;

/**
 * Binary row implementation that stores the schema version as a separate field.
 */
public class BinaryRowImpl implements BinaryRow {
    private final int schemaVersion;

    private final ByteBuffer binaryTuple;

    /**
     * Constructor.
     *
     * @param schemaVersion Schema version.
     * @param binaryTuple Binary tuple.
     */
    public BinaryRowImpl(int schemaVersion, ByteBuffer binaryTuple) {
        this.schemaVersion = schemaVersion;
        this.binaryTuple = binaryTuple;
    }

    @Override
    public int schemaVersion() {
        return schemaVersion;
    }

    @Override
    public boolean hasValue() {
        return (binaryTuple.get(0) & ROW_HAS_VALUE_FLAG) != 0;
    }

    @Override
    public ByteBuffer tupleSlice() {
        return binaryTuple.duplicate().order(ORDER);
    }

    @Override
    public ByteBuffer byteBuffer() {
        return ByteBuffer.allocate(tupleSliceLength() + Short.BYTES)
                .order(ORDER)
                .putShort((short) schemaVersion())
                .put(tupleSlice())
                .rewind();
    }

    @Override
    public int tupleSliceLength() {
        return binaryTuple.remaining();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        BinaryRowImpl binaryRow = (BinaryRowImpl) o;

        if (schemaVersion != binaryRow.schemaVersion) {
            return false;
        }

        return binaryTuple.equals(binaryRow.binaryTuple);
    }

    @Override
    public int hashCode() {
        int result = schemaVersion;
        result = 31 * result + binaryTuple.hashCode();
        return result;
    }
}
