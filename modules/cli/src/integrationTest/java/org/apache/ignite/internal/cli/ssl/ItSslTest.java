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

package org.apache.ignite.internal.cli.ssl;

import static org.junit.jupiter.api.Assertions.assertAll;

import jakarta.inject.Inject;
import org.apache.ignite.internal.NodeConfig;
import org.apache.ignite.internal.cli.call.connect.ConnectCall;
import org.apache.ignite.internal.cli.core.call.UrlCallInput;
import org.apache.ignite.internal.cli.core.flow.builder.Flows;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for SSL. */
public class ItSslTest extends CliSslNotInitializedIntegrationTestBase {
    @Inject
    ConnectCall connectCall;

    /** Mimics non-REPL "connect" command without starting REPL mode. Overriding getCommandClass and returning TopLevelCliReplCommand
     * wouldn't help because it will start to ask questions.
     */
    private void connect(String url) {
        Flows.from(new UrlCallInput(url))
                .then(Flows.fromCall(connectCall))
                .print()
                .start();
    }

    @Test
    @DisplayName("Should get SSL error, when connect to secured node without SSL settings")
    void connectToSecuredNodeWithoutSslSettings() {
        // When connect via HTTPS without SSL
        connect("https://localhost:10401");

        // Then
        assertAll(
                () -> assertErrOutputContains("SSL error"),
                this::assertOutputIsEmpty
        );
    }

    @Test
    @DisplayName("Should connect to cluster with given url")
    void connectToSecuredNode() {
        // When set up ssl configuration
        execute("cli", "config", "set", "ignite.rest.key-store.path=" + NodeConfig.resolvedKeystorePath);
        execute("cli", "config", "set", "ignite.rest.key-store.password=" + NodeConfig.keyStorePassword);
        execute("cli", "config", "set", "ignite.rest.trust-store.path=" + NodeConfig.resolvedTruststorePath);
        execute("cli", "config", "set", "ignite.rest.trust-store.password=" + NodeConfig.trustStorePassword);
        resetOutput();

        // And connect via HTTPS
        connect("https://localhost:10401");

        // Then
        assertAll(
                this::assertErrOutputIsEmpty,
                () -> assertOutputContains("Connected to https://localhost:10401")
        );
    }

    @Test
    @DisplayName("Should show an error message, when key-store password is incorrect")
    void incorrectKeyStorePassword() {
        // When set up ssl configuration
        execute("cli", "config", "set", "ignite.rest.key-store.path=" + NodeConfig.resolvedKeystorePath);
        execute("cli", "config", "set", "ignite.rest.key-store.password=wrong-password");
        execute("cli", "config", "set", "ignite.rest.trust-store.path=" + NodeConfig.resolvedTruststorePath);
        execute("cli", "config", "set", "ignite.rest.trust-store.password=" + NodeConfig.trustStorePassword);
        resetOutput();

        // And connect via HTTPS
        connect("https://localhost:10401");

        // Then
        assertAll(
                () -> assertErrOutputContains("Key-store password was incorrect"),
                this::assertOutputIsEmpty
        );
    }

    @Test
    @DisplayName("Should show an error message, when trust-store password is incorrect")
    void incorrectTrustStorePassword() {
        // When set up ssl configuration
        execute("cli", "config", "set", "ignite.rest.key-store.path=" + NodeConfig.resolvedKeystorePath);
        execute("cli", "config", "set", "ignite.rest.key-store.password=" + NodeConfig.keyStorePassword);
        execute("cli", "config", "set", "ignite.rest.trust-store.path=" + NodeConfig.resolvedTruststorePath);
        execute("cli", "config", "set", "ignite.rest.trust-store.password=wrong-password");
        resetOutput();

        // And connect via HTTPS
        connect("https://localhost:10401");

        // Then
        assertAll(
                () -> assertErrOutputContains("Trust-store password was incorrect"),
                this::assertOutputIsEmpty
        );
    }

    @Test
    @DisplayName("Should show an error message, when key-store path is incorrect")
    void incorrectKeyStorePath() {
        // When set up ssl configuration
        execute("cli", "config", "set", "ignite.rest.key-store.path=" + NodeConfig.resolvedKeystorePath + "-wrong-path");
        execute("cli", "config", "set", "ignite.rest.key-store.password=" + NodeConfig.keyStorePassword);
        execute("cli", "config", "set", "ignite.rest.trust-store.path=" + NodeConfig.resolvedTruststorePath);
        execute("cli", "config", "set", "ignite.rest.trust-store.password=" + NodeConfig.keyStorePassword);
        resetOutput();

        // And connect via HTTPS
        connect("https://localhost:10401");

        // Then
        assertAll(
                () -> assertErrOutputContains("File does not exist or it does not refer to a normal file"),
                this::assertOutputIsEmpty
        );
    }

    @Test
    @DisplayName("Should show an error message, when trust-store path is incorrect")
    void incorrectTrustStorePath() {
        // When set up ssl configuration
        execute("cli", "config", "set", "ignite.rest.key-store.path=" + NodeConfig.resolvedKeystorePath);
        execute("cli", "config", "set", "ignite.rest.key-store.password=" + NodeConfig.keyStorePassword);
        execute("cli", "config", "set", "ignite.rest.trust-store.path=" + NodeConfig.resolvedTruststorePath + "-wrong-path");
        execute("cli", "config", "set", "ignite.rest.trust-store.password=" + NodeConfig.keyStorePassword);
        resetOutput();

        // And connect via HTTPS
        connect("https://localhost:10401");

        // Then
        assertAll(
                () -> assertErrOutputContains("File does not exist or it does not refer to a normal file"),
                this::assertOutputIsEmpty
        );
    }
}
