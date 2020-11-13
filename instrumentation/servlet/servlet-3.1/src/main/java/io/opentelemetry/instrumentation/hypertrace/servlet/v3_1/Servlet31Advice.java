/*
 * Copyright The Hypertrace Authors
 *
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

package io.opentelemetry.instrumentation.hypertrace.servlet.v3_1;

import io.opentelemetry.instrumentation.hypertrace.servlet.common.ServletSpanDecorator;
import io.opentelemetry.trace.Span;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;
import org.hypertrace.agent.config.Config.AgentConfig;
import org.hypertrace.agent.core.HypertraceConfig;
import org.hypertrace.agent.core.HypertraceSemanticAttributes;
import org.hypertrace.agent.filter.FilterProvider;
import org.hypertrace.agent.filter.FilterResult;

public class Servlet31Advice {

  // request attribute key injected at first filerChain.doFilter
  private static final String ALREADY_LOADED = "__org.hypertrace.agent.on_start_executed";

  @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = FilterResult.class)
  public static Object start(
      @Advice.Argument(value = 0, readOnly = false) ServletRequest request,
      @Advice.Argument(value = 1, readOnly = false) ServletResponse response,
      @Advice.Local("rootStart") Boolean rootStart) {

    if (!HypertraceConfig.isInstrumentationEnabled(InstrumentationName.INSTRUMENTATION_NAME)) {
      return null;
    }
    if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
      return null;
    }

    // TODO run on every doFilter and check if user removed wrapper
    // TODO what if user unwraps request and reads the body?

    // run the instrumentation only for the root FilterChain.doFilter()
    if (request.getAttribute(ALREADY_LOADED) != null) {
      return null;
    }
    request.setAttribute(ALREADY_LOADED, true);

    HttpServletRequest httpRequest = (HttpServletRequest) request;
    HttpServletResponse httpResponse = (HttpServletResponse) response;
    Span currentSpan = InstrumentationName.TRACER.getCurrentSpan();

    rootStart = true;
    response = new BufferingHttpServletResponse(httpResponse);
    request = new BufferingHttpServletRequest(httpRequest, (HttpServletResponse) response);

    ServletSpanDecorator.addSessionId(currentSpan, httpRequest);

    // set request headers
    Enumeration<String> headerNames = httpRequest.getHeaderNames();
    Map<String, String> headers = new HashMap<>();
    while (headerNames.hasMoreElements()) {
      String headerName = headerNames.nextElement();
      String headerValue = httpRequest.getHeader(headerName);

      if (HypertraceConfig.get().getDataCapture().getHttpHeaders().getRequest().getValue()) {
        currentSpan.setAttribute(
            HypertraceSemanticAttributes.httpRequestHeader(headerName), headerValue);
      }
      headers.put(headerName, headerValue);
    }
    FilterResult filterResult =
        FilterProvider.getFilterEvaluator().evaluateRequestHeaders(currentSpan, headers);
    if (filterResult.blockExecution()) {
      httpResponse.setStatus(403);
      return filterResult;
    }
    return null;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Argument(0) ServletRequest request,
      @Advice.Argument(1) ServletResponse response,
      @Advice.Local("rootStart") Boolean rootStart) {
    if (rootStart != null) {
      if (!(request instanceof BufferingHttpServletRequest)
          || !(response instanceof BufferingHttpServletResponse)) {
        return;
      }

      request.removeAttribute(ALREADY_LOADED);
      Span currentSpan = InstrumentationName.TRACER.getCurrentSpan();

      AtomicBoolean responseHandled = new AtomicBoolean(false);
      if (request.isAsyncStarted()) {
        try {
          request
              .getAsyncContext()
              .addListener(new BodyCaptureAsyncListener(responseHandled, currentSpan));
        } catch (IllegalStateException e) {
          // org.eclipse.jetty.server.Request may throw an exception here if request became
          // finished after check above. We just ignore that exception and move on.
        }
      }

      if (!request.isAsyncStarted() && responseHandled.compareAndSet(false, true)) {
        BufferingHttpServletResponse bufferingResponse = (BufferingHttpServletResponse) response;
        BufferingHttpServletRequest bufferingRequest = (BufferingHttpServletRequest) request;

        // set response headers
        AgentConfig agentConfig = HypertraceConfig.get();
        if (agentConfig.getDataCapture().getHttpHeaders().getResponse().getValue()) {
          for (String headerName : bufferingResponse.getHeaderNames()) {
            String headerValue = bufferingResponse.getHeader(headerName);
            currentSpan.setAttribute(
                HypertraceSemanticAttributes.httpResponseHeader(headerName), headerValue);
          }
        }
        // Bodies are captured at the end after all user processing.
        if (agentConfig.getDataCapture().getHttpBody().getRequest().getValue()) {
          currentSpan.setAttribute(
              HypertraceSemanticAttributes.HTTP_REQUEST_BODY,
              bufferingRequest.getBufferedBodyAsString());
        }
        if (agentConfig.getDataCapture().getHttpBody().getResponse().getValue()) {
          currentSpan.setAttribute(
              HypertraceSemanticAttributes.HTTP_RESPONSE_BODY,
              bufferingResponse.getBufferAsString());
        }
      }
    }
  }
}