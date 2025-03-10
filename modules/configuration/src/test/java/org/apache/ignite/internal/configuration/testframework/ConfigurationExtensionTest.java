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

package org.apache.ignite.internal.configuration.testframework;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.ignite.internal.configuration.notifications.ConfigurationStorageRevisionListenerHolder;
import org.apache.ignite.internal.configuration.sample.DiscoveryConfiguration;
import org.apache.ignite.internal.configuration.sample.ExtendedDiscoveryConfiguration;
import org.apache.ignite.internal.configuration.sample.ExtendedDiscoveryConfigurationSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Test basic scenarios of {@link ConfigurationExtension}.
 */
@ExtendWith(ConfigurationExtension.class)
class ConfigurationExtensionTest {
    /** Injected field. */
    @InjectConfiguration(internalExtensions = ExtendedDiscoveryConfigurationSchema.class)
    private DiscoveryConfiguration fieldCfg;

    @InjectRevisionListenerHolder
    private ConfigurationStorageRevisionListenerHolder fieldRevisionListenerHolder;

    /** Test that contains injected parameter. */
    @Test
    public void injectConfiguration(
            @InjectConfiguration("mock.joinTimeout=100") DiscoveryConfiguration paramCfg
    ) throws Exception {
        assertEquals(5000, fieldCfg.joinTimeout().value());

        assertEquals(100, paramCfg.joinTimeout().value());

        paramCfg.change(d -> d.changeJoinTimeout(200)).get(1, SECONDS);

        assertEquals(200, paramCfg.joinTimeout().value());

        paramCfg.joinTimeout().update(300).get(1, SECONDS);

        assertEquals(300, paramCfg.joinTimeout().value());
    }

    /** Tests that notifications work on injected configuration instance. */
    @Test
    public void notifications() throws Exception {
        List<String> log = new ArrayList<>();

        fieldCfg.listen(ctx -> {
            log.add("update");

            return completedFuture(null);
        });

        fieldCfg.joinTimeout().listen(ctx -> {
            log.add("join");

            return completedFuture(null);
        });

        fieldCfg.failureDetectionTimeout().listen(ctx -> {
            log.add("failure");

            return completedFuture(null);
        });

        fieldCfg.change(change -> change.changeJoinTimeout(1000_000)).get(1, SECONDS);

        assertEquals(List.of("update", "join"), log);

        log.clear();

        fieldCfg.failureDetectionTimeout().update(2000_000).get(1, SECONDS);

        assertEquals(List.of("update", "failure"), log);
    }

    /** Tests that internal configuration extensions work properly on injected configuration instance. */
    @Test
    public void internalConfiguration(
            @InjectConfiguration(internalExtensions = {ExtendedConfigurationSchema.class}) BasicConfiguration cfg
    ) throws Exception {
        assertThat(cfg, is(instanceOf(ExtendedConfiguration.class)));

        assertEquals(1, cfg.visible().value());

        assertEquals(2, ((ExtendedConfiguration) cfg).invisible().value());

        cfg.change(change -> {
            assertThat(change, is(instanceOf(ExtendedChange.class)));

            change.changeVisible(3);

            ((ExtendedChange) change).changeInvisible(4);
        }).get(1, SECONDS);

        assertEquals(3, cfg.visible().value());

        assertEquals(4, ((ExtendedConfiguration) cfg).invisible().value());
    }

    @Test
    void testFieldConfigurationStorageRevisionListenerHolder() throws Exception {
        assertNotNull(fieldRevisionListenerHolder);

        List<Long> revisions = new CopyOnWriteArrayList<>();

        fieldRevisionListenerHolder.listenUpdateStorageRevision(revision -> {
            revisions.add(revision);

            return completedFuture(null);
        });

        fieldCfg.joinTimeout().update(1_000_000).get(1, SECONDS);

        fieldCfg.joinTimeout().update(2_000_000).get(1, SECONDS);

        assertEquals(2, revisions.size(), revisions::toString);

        assertTrue(revisions.get(0) < revisions.get(1), revisions::toString);
    }

    @Test
    void testParamConfigurationStorageRevisionListenerHolder(
            @InjectConfiguration("mock.joinTimeout=100") DiscoveryConfiguration paramCfg,
            @InjectRevisionListenerHolder ConfigurationStorageRevisionListenerHolder paramRevisionListenerHolder
    ) throws Exception {
        assertNotNull(paramRevisionListenerHolder);

        assertSame(fieldRevisionListenerHolder, paramRevisionListenerHolder);

        List<Long> revisions = new CopyOnWriteArrayList<>();

        paramRevisionListenerHolder.listenUpdateStorageRevision(revision -> {
            revisions.add(revision);

            return completedFuture(null);
        });

        paramCfg.joinTimeout().update(1_000_000).get(1, SECONDS);

        paramCfg.joinTimeout().update(2_000_000).get(1, SECONDS);

        assertEquals(2, revisions.size(), revisions::toString);

        assertTrue(revisions.get(0) < revisions.get(1), revisions::toString);
    }

    /** Test UUID generation in mocks. */
    @Test
    public void testInjectInternalId(
            @InjectConfiguration(
                    internalExtensions = ExtendedDiscoveryConfigurationSchema.class,
                    name = "test"
            ) DiscoveryConfiguration discoveryConfig
    ) {
        assertNotNull(((ExtendedDiscoveryConfiguration) discoveryConfig).id().value());
    }
}
