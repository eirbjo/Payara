/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 *    Copyright (c) [2023] Payara Foundation and/or its affiliates. All rights reserved.
 *
 *     The contents of this file are subject to the terms of either the GNU
 *     General Public License Version 2 only ("GPL") or the Common Development
 *     and Distribution License("CDDL") (collectively, the "License").  You
 *     may not use this file except in compliance with the License.  You can
 *     obtain a copy of the License at
 *     https://github.com/payara/Payara/blob/master/LICENSE.txt
 *     See the License for the specific
 *     language governing permissions and limitations under the License.
 *
 *     When distributing the software, include this License Header Notice in each
 *     file and include the License file at glassfish/legal/LICENSE.txt.
 *
 *     GPL Classpath Exception:
 *     The Payara Foundation designates this particular file as subject to the "Classpath"
 *     exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 *     file that accompanied this code.
 *
 *     Modifications:
 *     If applicable, add the following below the License Header, with the fields
 *     enclosed by brackets [] replaced by your own identifying information:
 *     "Portions Copyright [year] [name of copyright owner]"
 *
 *     Contributor(s):
 *     If you wish your version of this file to be governed by only the CDDL or
 *     only the GPL Version 2, indicate your decision by adding "[Contributor]
 *     elects to include this software in this distribution under the [CDDL or GPL
 *     Version 2] license."  If you don't indicate a single choice of license, a
 *     recipient has the option to distribute your version of this file under
 *     either the CDDL, the GPL Version 2 or to extend the choice of license to
 *     its licensees as provided above.  However, if you add GPL Version 2 code
 *     and therefore, elected the GPL Version 2 license, then the option applies
 *     only if the new code is made subject to such option by the copyright
 *     holder.
 */
package fish.payara.microprofile.telemetry.tracing;

import io.opentelemetry.api.trace.SpanBuilder;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;
import org.eclipse.microprofile.opentracing.Traced;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ExtendedUriInfo;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A helper class for OpenTracing instrumentation in JAX-RS resources.
 */
public class OpenTracingHelper {

    private static final Logger LOG = Logger.getLogger(OpenTracingHelper.class.getName());
    public static final String SPAN_CONVENTION_MP_KEY = "payara.telemetry.span-convention";

    private final ResourceInfo resourceInfo;
    private final Config mpConfig;

    public OpenTracingHelper(final ResourceInfo resourceInfo) {
        this.resourceInfo = resourceInfo;
        this.mpConfig = getConfig();
    }

    /**
     * Determines the operation name of the span based on the given ContainerRequestContext and Traced annotation.
     *
     * @param request          the ContainerRequestContext object for this request
     * @param tracedAnnotation the Traced annotation object for this request
     * @return the name to use as the span's operation name
     */
    public String determineOperationName(final ContainerRequestContext request, final Traced tracedAnnotation) {
        if (tracedAnnotation != null) {
            String operationName = OpenTracingCdiUtils.getConfigOverrideValue(
                            Traced.class, "operationName", resourceInfo, String.class)
                    .orElse(tracedAnnotation.operationName());
            // If the annotation or config override providing an empty name, just set it equal to the HTTP Method,
            // followed by the method signature
            if (operationName.equals("")) {
                operationName = request.getMethod() + ":"
                        + resourceInfo.getResourceClass().getCanonicalName() + "."
                        + resourceInfo.getResourceMethod().getName();
            }
            return operationName;
        }
        // TODO: OpenTelemetry @WithSpan

        SpanStrategy naming = determineSpanStrategy();

        return naming.determineSpanName(request, resourceInfo);
    }

    private SpanStrategy determineSpanStrategy() {
        // Determine if an operation name provider has been given
        final Optional<String> operationNameProviderOptional = mpConfig.getOptionalValue("mp.opentracing.server.operation-name-provider", String.class);

        if (Optional.of("http-path").equals(operationNameProviderOptional)) {
            return SpanStrategy.OPENTRACING_PATH;
        }

        if (Optional.of("class-method").equals(operationNameProviderOptional)) {
            return SpanStrategy.OPENTRACING_CLASS_METHOD;
        }

        var vendorCompatibilitySetting = mpConfig.getOptionalValue(SPAN_CONVENTION_MP_KEY, String.class);
        if (vendorCompatibilitySetting.isPresent()) {
            switch (vendorCompatibilitySetting.get().toLowerCase()) {
                case "opentracing-http-path":
                    return SpanStrategy.OPENTRACING_PATH;
                case "opentracing-class-method":
                    return SpanStrategy.OPENTRACING_CLASS_METHOD;
                case "opentelemetry":
                    return SpanStrategy.OTEL_SEM_CONV;
                case "microprofile-telemetry":
                    return SpanStrategy.OTEL_MP_TCK;
                default:
                    LOG.fine(() -> "Unsupported value of " + SPAN_CONVENTION_MP_KEY + ": " + vendorCompatibilitySetting.get());
            }
        }

        // TODO: Remove after TCK runners are updated with span convention setting
        if (resourceInfo.getResourceClass().getPackageName().startsWith("org.eclipse.microprofile.opentracing.tck")) {
            return SpanStrategy.OPENTRACING_CLASS_METHOD;
        }

        // similar dirty hack for opentelemetry TCK
        if (resourceInfo.getResourceClass().getPackageName().startsWith("org.eclipse.microprofile.telemetry.tracing.tck")) {
            return SpanStrategy.OTEL_MP_TCK;
        }
        return SpanStrategy.OTEL_SEM_CONV;
    }

    public void augmentSpan(SpanBuilder spanBuilder) {
        determineSpanStrategy().augmentSpan(spanBuilder);
    }

    /**
     * Strategy for naming spans.
     */
    enum SpanStrategy {
        /**
         * As defined by HTTP semantic conventions
         *
         * @see <a href="https://opentelemetry.io/docs/reference/specification/trace/semantic_conventions/http/#http-server">Spec</a>
         */
        OTEL_SEM_CONV {
            @Override
            String determineSpanName(ContainerRequestContext request, ResourceInfo resourceInfo) {
                var uriInfo = request.getUriInfo();
                var result = new StringBuilder();
                result.append(request.getMethod()).append(" ")
                        .append(uriInfo.getBaseUri().getPath());
                SpanStrategy.trimTrailingSlash(result);
                appendTemplate(resourceInfo, uriInfo, result);
                return result.toString();
            }
        },

        /**
         * As defined by MP OpenTelemetry Tracing, which is not following semantic conventions
         *
         * @see <a href="https://github.com/eclipse/microprofile-telemetry/blob/main/tracing/tck/src/main/java/org/eclipse/microprofile/telemetry/tracing/tck/rest/RestSpanTest.java">Test Case</a>
         */
        OTEL_MP_TCK {
            @Override
            String determineSpanName(ContainerRequestContext request, ResourceInfo resourceInfo) {
                var uriInfo = request.getUriInfo();
                var result = new StringBuilder();
                result.append(uriInfo.getBaseUri().getPath());
                // strip / at end
                trimTrailingSlash(result);
                SpanStrategy.appendTemplate(resourceInfo, uriInfo, result);
                return result.toString();
            }
        },

        /**
         * Opentracing Class-Method
         *
         * @see <a href="https://download.eclipse.org/microprofile/microprofile-opentracing-2.0/microprofile-opentracing-spec-2.0.html#server-span-name">MP spec</a>
         */
        OPENTRACING_CLASS_METHOD {
            @Override
            String determineSpanName(ContainerRequestContext request, ResourceInfo resourceInfo) {
                return request.getMethod() + ":"
                        + resourceInfo.getResourceClass().getCanonicalName() + "."
                        + resourceInfo.getResourceMethod().getName();
            }
        },
        /**
         * OpenTracing HTTP path
         *
         * @see <a href="https://download.eclipse.org/microprofile/microprofile-opentracing-2.0/microprofile-opentracing-spec-2.0.html#server-span-name">MP spec</a>
         */
        OPENTRACING_PATH {
            @Override
            String determineSpanName(ContainerRequestContext request, ResourceInfo resourceInfo) {
                final Path classLevelAnnotation = resourceInfo.getResourceClass().getAnnotation(Path.class);
                final Path methodLevelAnnotation = resourceInfo.getResourceMethod().getAnnotation(Path.class);

                // If the provider is set to "http-path" and the class-level @Path annotation is actually present
                if (classLevelAnnotation != null) {
                    String operationName = request.getMethod() + ":";

                    if (classLevelAnnotation.value().startsWith("/")) {
                        operationName += classLevelAnnotation.value();
                    } else {
                        operationName += "/" + classLevelAnnotation.value();
                    }

                    // If the method-level @Path annotation is present, use its value
                    if (methodLevelAnnotation != null) {
                        if (methodLevelAnnotation.value().startsWith("/")) {
                            operationName += methodLevelAnnotation.value();
                        } else {
                            operationName += "/" + methodLevelAnnotation.value();
                        }
                    }
                    return operationName;
                } else {
                    return OPENTRACING_CLASS_METHOD.determineSpanName(request, resourceInfo);
                }
            }
        };

        private static void trimTrailingSlash(StringBuilder result) {
            if (result.length() > 1 && result.charAt(result.length() -1) == '/')  {
                result.setLength(result.length() - 1);
            }
        }

        private static void appendTemplate(ResourceInfo resourceInfo, UriInfo uriInfo, StringBuilder result) {
            if (uriInfo.getMatchedResources().size() == 1) {
                // simple matching of single method. But I might just use the other method for everything.
                var classRoute = resourceInfo.getResourceClass().getAnnotation(Path.class);
                var methodRoute = resourceInfo.getResourceMethod().getAnnotation(Path.class);
                if (classRoute != null && !"/".equals(classRoute.value())) {
                    result.append(classRoute.value());
                }
                if (methodRoute != null && !"/".equals(methodRoute.value())) {
                    result.append(methodRoute.value());
                }
            } else if (uriInfo instanceof ExtendedUriInfo) {
                var templates = ((ExtendedUriInfo) uriInfo).getMatchedTemplates();
                // "Entries are ordered in reverse request URI matching order, with the root resource URI template last."
                for(int i = templates.size()-1;i >= 0; i--) {
                    var template = templates.get(i).getTemplate();
                    if ("/".equals(template)) {
                        continue;
                    }
                    result.append(template);
                }
            } else {
                // Shouldn't happen, as we use Jersey. Unless some classpath magic happened.
                result.append("*");
            }
        }


        abstract String determineSpanName(ContainerRequestContext request, ResourceInfo resourceInfo);

        void augmentSpan(SpanBuilder spanBuilder) {
            if (this == OPENTRACING_CLASS_METHOD || this == OPENTRACING_PATH) {
                spanBuilder.setAttribute("span.kind", "server");
            }
        }
    }

    /**
     * Checks if CDI has been initialized by trying to get the BeanManager. If CDI is initialized,
     * gets the Traced annotation from the target method.
     *
     * @return the Traced annotation object for this request, or null if CDI has not been initialized
     */
    public Traced getTracedAnnotation() {
        final BeanManager beanManager = getBeanManager();
        if (beanManager == null) {
            return null;
        }
        return OpenTracingCdiUtils.getAnnotation(beanManager, Traced.class, resourceInfo);
    }

    /**
     * Helper method that checks if any specified skip patterns match this method name
     *
     * @param request the request to check if we should skip
     * @return
     */
    public boolean canTrace(final ContainerRequest request, final Traced tracedAnnotation) {
        // we cannot trace if we don't have enough information.
        // this can occur on early request processing stages
        if (request == null || resourceInfo.getResourceClass() == null || resourceInfo.getResourceMethod() == null) {
            return false;
        }

        // Prepend a slash for safety (so that a pattern of "/blah" or just "blah" will both match)
        final String uriPath = "/" + request.getUriInfo().getPath();
        // Because the openapi resource path is always empty we need to use the base path
        final String baseUriPath = request.getUriInfo().getBaseUri().getPath();
        // First, check for the mandatory skips
        if (uriPath.equals("/health")
                || uriPath.equals("/metrics")
                || uriPath.contains("/metrics/base")
                || uriPath.contains("/metrics/vendor")
                || uriPath.contains("/metrics/application")
                || baseUriPath.equals("/openapi/")) {
            return false;
        }

        // If a skip pattern property has been given, check if any of them match the method
        final Optional<String> skipPatternOptional = mpConfig.getOptionalValue("mp.opentracing.server.skip-pattern",
                String.class);
        if (skipPatternOptional.isPresent()) {
            final String skipPatterns = skipPatternOptional.get();

            final String[] splitSkipPatterns = skipPatterns.split("\\|");

            for (final String skipPattern : splitSkipPatterns) {
                if (uriPath.matches(skipPattern)) {
                    return false;
                }
            }
        }

        return tracedAnnotation == null || getTracingFromConfig().orElse(tracedAnnotation.value());
    }

    private BeanManager getBeanManager() {
        try {
            return CDI.current().getBeanManager();
        } catch (final IllegalStateException ise) {
            // *Should* only get here if CDI hasn't been initialised, indicating that the app isn't using it
            LOG.log(Level.FINE, "Error getting Bean Manager, presumably due to this application not using CDI",
                    ise);
            return null;
        }
    }

    /**
     * Gets the value of the "value" parameter in the Traced annotation from configuration, if available.
     *
     * @return an Optional<Boolean> indicating whether or not tracing is enabled for this request
     */
    private Optional<Boolean> getTracingFromConfig() {
        return OpenTracingCdiUtils.getConfigOverrideValue(Traced.class, "value", resourceInfo, Boolean.class);
    }

    /**
     * Gets the Config object for current application.
     *
     * @return the Config object for this request, or null if no config could be found
     */
    public static Config getConfig() {
        try {
            return ConfigProvider.getConfig();
        } catch (final IllegalArgumentException ex) {
            return EMPTY_CONFIG;
        }
    }

    static final Config EMPTY_CONFIG = new Config() {
        @Override
        public <T> T getValue(String s, Class<T> aClass) {
            throw new NoSuchElementException("Empty config");
        }

        @Override
        public ConfigValue getConfigValue(String s) {
            throw new NoSuchElementException("Empty Config");
        }

        @Override
        public <T> Optional<T> getOptionalValue(String s, Class<T> aClass) {
            return Optional.empty();
        }

        @Override
        public Iterable<String> getPropertyNames() {
            return List.of();
        }

        @Override
        public Iterable<ConfigSource> getConfigSources() {
            return List.of();
        }

        @Override
        public <T> Optional<Converter<T>> getConverter(Class<T> aClass) {
            return Optional.empty();
        }

        @Override
        public <T> T unwrap(Class<T> aClass) {
            return null;
        }
    };
}
