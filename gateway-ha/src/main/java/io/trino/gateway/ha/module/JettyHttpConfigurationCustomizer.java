/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.gateway.ha.module;

import com.google.inject.Binder;
import com.google.inject.Inject;
import com.google.inject.Module;
import io.airlift.http.server.HttpServerInfo;
import io.airlift.log.Logger;
import io.trino.gateway.ha.config.HaGatewayConfiguration;
import jakarta.annotation.PostConstruct;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;

/**
 * Module and customizer to configure Jetty's HTTP server with larger header buffers.
 * This addresses the "431 Request Header Fields Too Large" error by directly
 * configuring Jetty's HttpConfiguration after the server is created.
 */
public class JettyHttpConfigurationCustomizer
        implements Module
{
    private static final Logger log = Logger.get(JettyHttpConfigurationCustomizer.class);
    private static final int DEFAULT_REQUEST_HEADER_SIZE = 32 * 1024 * 1024; // 32MB
    private static final int DEFAULT_RESPONSE_HEADER_SIZE = 32 * 1024 * 1024; // 32MB

    private final HaGatewayConfiguration configuration;

    public JettyHttpConfigurationCustomizer(HaGatewayConfiguration configuration)
    {
        this.configuration = configuration;
    }

    @Override
    public void configure(Binder binder)
    {
        binder.bind(JettyServerCustomizer.class).asEagerSingleton();
    }

    /**
     * Component that customizes the Jetty server after it's been created by Airlift.
     */
    public static class JettyServerCustomizer
    {
        private final HttpServerInfo httpServerInfo;
        private final int requestHeaderSize;
        private final int responseHeaderSize;

        @Inject
        public JettyServerCustomizer(HttpServerInfo httpServerInfo, HaGatewayConfiguration configuration)
        {
            this.httpServerInfo = httpServerInfo;
            this.requestHeaderSize = parseHeaderSize(
                    configuration.getServerConfig().get("http-server.max-request-header-size"),
                    DEFAULT_REQUEST_HEADER_SIZE);
            this.responseHeaderSize = parseHeaderSize(
                    configuration.getServerConfig().get("http-server.max-response-header-size"),
                    DEFAULT_RESPONSE_HEADER_SIZE);
        }

        @PostConstruct
        public void customize()
        {
            try {
                // Get the Jetty server from HttpServerInfo via reflection
                java.lang.reflect.Field serverField = httpServerInfo.getClass().getDeclaredField("server");
                serverField.setAccessible(true);
                Server server = (Server) serverField.get(httpServerInfo);

                if (server != null) {
                    // Configure all HttpConfiguration instances
                    server.getConnectors();
                    org.eclipse.jetty.server.ServerConnector[] connectors =
                            (org.eclipse.jetty.server.ServerConnector[]) server.getConnectors();

                    for (org.eclipse.jetty.server.ServerConnector connector : connectors) {
                        for (org.eclipse.jetty.server.ConnectionFactory factory : connector.getConnectionFactories()) {
                            if (factory instanceof org.eclipse.jetty.server.HttpConnectionFactory) {
                                HttpConfiguration httpConfig = ((org.eclipse.jetty.server.HttpConnectionFactory) factory).getHttpConfiguration();
                                httpConfig.setRequestHeaderSize(requestHeaderSize);
                                httpConfig.setResponseHeaderSize(responseHeaderSize);
                                log.info("Configured Jetty HTTP connector: requestHeaderSize=%d bytes, responseHeaderSize=%d bytes",
                                        requestHeaderSize, responseHeaderSize);
                            }
                        }
                    }
                }
            }
            catch (Exception e) {
                log.error(e, "Failed to customize Jetty HttpConfiguration. Headers may still have size limits.");
            }
        }

        private static int parseHeaderSize(String value, int defaultSize)
        {
            if (value == null) {
                return defaultSize;
            }

            try {
                // Parse values like "32MB", "64kB", "1GB", etc.
                value = value.trim().toUpperCase();
                long multiplier = 1;

                if (value.endsWith("GB")) {
                    multiplier = 1024L * 1024 * 1024;
                    value = value.substring(0, value.length() - 2);
                }
                else if (value.endsWith("MB")) {
                    multiplier = 1024L * 1024;
                    value = value.substring(0, value.length() - 2);
                }
                else if (value.endsWith("KB")) {
                    multiplier = 1024L;
                    value = value.substring(0, value.length() - 2);
                }
                else if (value.endsWith("B")) {
                    multiplier = 1;
                    value = value.substring(0, value.length() - 1);
                }

                long bytes = Long.parseLong(value.trim()) * multiplier;
                if (bytes > Integer.MAX_VALUE) {
                    log.warn("Header size %d bytes exceeds Integer.MAX_VALUE, using default %d", bytes, defaultSize);
                    return defaultSize;
                }
                return (int) bytes;
            }
            catch (Exception e) {
                log.warn(e, "Failed to parse header size value '%s', using default %d bytes", value, defaultSize);
                return defaultSize;
            }
        }
    }
}
