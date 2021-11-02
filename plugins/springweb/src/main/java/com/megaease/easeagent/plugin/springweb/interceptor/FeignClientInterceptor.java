package com.megaease.easeagent.plugin.springweb.interceptor;

import com.megaease.easeagent.plugin.Interceptor;
import com.megaease.easeagent.plugin.MethodInfo;
import com.megaease.easeagent.plugin.annotation.AdviceTo;
import com.megaease.easeagent.plugin.api.Context;
import com.megaease.easeagent.plugin.api.context.ProgressContext;
import com.megaease.easeagent.plugin.api.trace.Span;
import com.megaease.easeagent.plugin.api.trace.utils.HttpRequest;
import com.megaease.easeagent.plugin.api.trace.utils.HttpResponse;
import com.megaease.easeagent.plugin.api.trace.utils.HttpUtils;
import com.megaease.easeagent.plugin.springweb.advice.FeignClientAdvice;
import feign.Request;
import feign.Response;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.*;

@AdviceTo(value = FeignClientAdvice.class, qualifier = "default")
public class FeignClientInterceptor implements Interceptor {
    private static final Object ENTER = new Object();
    private static final Object PROGRESS_CONTEXT = new Object();

    @Override
    public void before(MethodInfo methodInfo, Context context) {
        if (!context.enter(ENTER, 1)) {
            return;
        }
        FeignClientRequestWrapper requestWrapper = new FeignClientRequestWrapper((Request) methodInfo.getArgs()[0]);
        ProgressContext progressContext = context.nextProgress(requestWrapper);
        HttpUtils.handleReceive(progressContext.span().start(), requestWrapper);
        progressContext.span().tag("s", FeignClientInterceptor.class.getName());
        context.put(PROGRESS_CONTEXT, progressContext);
    }

    @Override
    public void after(MethodInfo methodInfo, Context context) {
        if (!context.out(ENTER, 1)) {
            return;
        }
        ProgressContext progressContext = context.get(PROGRESS_CONTEXT);
        try {
            Request request = (Request) methodInfo.getArgs()[0];
            Response response = (Response) methodInfo.getRetValue();
            FeignClientResponseWrapper responseWrapper = new FeignClientResponseWrapper(methodInfo.getThrowable(), request, response);
            progressContext.finish(responseWrapper);
            HttpUtils.finish(progressContext.span(), responseWrapper);
        } finally {
            progressContext.scope().close();
        }
    }


    private static String getFirstHeaderValue(Map<String, Collection<String>> headers, String name) {
        Collection<String> values = headers.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.iterator().next();
    }

    static class FeignClientRequestWrapper implements HttpRequest {

        private final Request request;

        private final Map<String, Collection<String>> headers = new HashMap<>();

        public FeignClientRequestWrapper(Request request) {
            this.request = request;
            Field headersField = HeadersFieldFinder.getHeadersField();
            if (headersField != null) {
                Map<String, Collection<String>> originHeaders = HeadersFieldFinder.getHeadersFieldValue(headersField, request);
                if (originHeaders != null) {
                    headers.putAll(originHeaders);
                }
                HeadersFieldFinder.setHeadersFieldValue(headersField, request, headers);
            }
        }

        @Override
        public Span.Kind kind() {
            return Span.Kind.CLIENT;
        }


        @Override
        public String method() {
            return request.httpMethod().name();
        }

        @Override
        public String path() {
            return request.url();
        }

        @Override
        public String route() {
            return null;
        }

        @Override
        public String getRemoteAddr() {
            return null;
        }

        @Override
        public int getRemotePort() {
            return 0;
        }

        @Override
        public String getRemoteHost() {
            return null;
        }

        @Override
        public String header(String name) {
            Collection<String> values = headers.get(name);
            if (values == null || values.isEmpty()) {
                return null;
            }
            return values.iterator().next();
        }

        @Override
        public boolean cacheScope() {
            return false;
        }

        @Override
        public void setHeader(String name, String value) {
            Collection<String> values = headers.computeIfAbsent(name, k -> new ArrayList<>());
            values.add(value);
        }
    }

    static class FeignClientResponseWrapper implements HttpResponse, com.megaease.easeagent.plugin.api.trace.Response {
        private final Throwable caught;
        private final Request request;
        private final Response response;
        private final Map<String, Collection<String>> headers;

        public FeignClientResponseWrapper(Throwable caught, Request request, Response response) {
            this.caught = caught;
            this.request = request;
            this.response = response;
            this.headers = response.headers();
        }

        @Override
        public String method() {
            return request.httpMethod().name();
        }

        @Override
        public String route() {
            return null;
//            Object maybeRoute = request.getAttribute(TraceConst.HTTP_ATTRIBUTE_ROUTE);
//            return maybeRoute instanceof String ? (String) maybeRoute : null;
        }

        @SneakyThrows
        @Override
        public int statusCode() {
            return response.status();
        }

        @Override
        public Throwable maybeError() {
            if (caught != null) {
                return caught;
            }
            return null;
        }

        @Override
        public Set<String> keys() {
            return null;
        }

        @Override
        public String header(String name) {
            return getFirstHeaderValue(headers, name);
        }
    }


    static class HeadersFieldFinder {

        private static final Logger logger = LoggerFactory.getLogger(HeadersFieldFinder.class);

        private static Field headersField;

        static Field getHeadersField() {
            if (headersField != null) {
                return headersField;
            }
            try {
                headersField = Request.class.getDeclaredField("headers");
                headersField.setAccessible(true);
                return headersField;
            } catch (Exception e) {
                logger.warn(e.getMessage());
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        static Map<String, Collection<String>> getHeadersFieldValue(Field headersField, Object target) {
            try {
                return (Map<String, Collection<String>>) headersField.get(target);
            } catch (IllegalAccessException e) {
                logger.warn("can not get header in FeignClient. {}", e.getMessage());
            }
            return null;
        }

        static void setHeadersFieldValue(Field headersField, Object target, Object fieldValue) {
            try {
                headersField.set(target, fieldValue);
            } catch (IllegalAccessException e) {
                logger.warn("can not set header in FeignClient. {}", e.getMessage());
            }
        }
    }
}