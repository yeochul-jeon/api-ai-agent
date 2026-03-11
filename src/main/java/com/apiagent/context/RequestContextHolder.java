package com.apiagent.context;

/**
 * ThreadLocal 기반 요청 컨텍스트 홀더.
 * Virtual Threads 환경에서도 요청별 격리 보장.
 */
public final class RequestContextHolder {

    private static final ThreadLocal<RequestContext> CONTEXT = new ThreadLocal<>();

    private RequestContextHolder() {}

    public static void set(RequestContext context) {
        CONTEXT.set(context);
    }

    public static RequestContext get() {
        return CONTEXT.get();
    }

    public static RequestContext require() {
        var ctx = CONTEXT.get();
        if (ctx == null) {
            throw new IllegalStateException("RequestContext not set. Missing required headers?");
        }
        return ctx;
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
