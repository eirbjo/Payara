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
package fish.payara.microprofile.telemetry.tracing.jaxrs;

import fish.payara.nucleus.requesttracing.RequestTracingService;
import fish.payara.opentracing.OpenTelemetryService;
import fish.payara.microprofile.telemetry.tracing.jaxrs.client.PayaraTracingServices;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Context;
import org.glassfish.jersey.server.monitoring.ApplicationEvent;
import org.glassfish.jersey.server.monitoring.ApplicationEventListener;
import org.glassfish.jersey.server.monitoring.RequestEvent;
import org.glassfish.jersey.server.monitoring.RequestEventListener;

import java.util.logging.Logger;

@Priority(Priorities.HEADER_DECORATOR - 500)
public class OpenTelemetryApplicationEventListener implements ApplicationEventListener {

    private static final Logger LOG = Logger.getLogger(OpenTelemetryApplicationEventListener.class.getName());

    private RequestTracingService requestTracing;
    private OpenTelemetryService openTelemetryService;

    @Context
    private ResourceInfo resourceInfo;

    @Context
    private Configuration configuration;

    /**
     * Initialization of internal services.
     */
    @PostConstruct
    public void postConstruct() {
        LOG.finest("postConstruct()");
        final PayaraTracingServices payaraTracingServices = new PayaraTracingServices();
        this.requestTracing = payaraTracingServices.getRequestTracingService();
        this.openTelemetryService = payaraTracingServices.getOpenTelemetryService();
    }


    @Override
    public void onEvent(final ApplicationEvent event) {
        LOG.config(() -> "onEvent(event.type=" + event.getType() + ")");
    }


    @Override
    public RequestEventListener onRequest(final RequestEvent event) {
        LOG.finer(() -> "onRequest(event.type=" + event.getType() + ")");
        if (!isRequestTracingInProgress()) {
            LOG.finest("isRequestTracingInProgress() returned false, nothing to do.");
            return null;
        }
        return new OpenTelemetryRequestEventListener(this.resourceInfo, this.openTelemetryService);
    }


    private boolean isRequestTracingInProgress() {
        return this.openTelemetryService.isEnabled();
    }
}

