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

package org.apache.ignite.internal.schema.configuration.storage;

import org.apache.ignite.configuration.annotation.PolymorphicConfig;
import org.apache.ignite.configuration.annotation.PolymorphicId;
import org.apache.ignite.configuration.annotation.Value;

/**
 * Configuration schema for data storage.
 */
@PolymorphicConfig
public class DataStorageConfigurationSchema {
    /** Name of data storage. */
    @PolymorphicId(hasDefault = true)
    public String name = UnknownDataStorageConfigurationSchema.UNKNOWN_DATA_STORAGE;

    /** The number of entries in the storage to be garbage collected during a storage update operation. */
    @Value(hasDefault = true)
    public int gcOnUpdateBatchSize = 5;
}
