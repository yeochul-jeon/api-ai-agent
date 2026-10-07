package com.apiagent.context;

/**
 * ScopedValue 기반 요청 컨텍스트 홀더.
 * Virtual Threads 환경에서 명시적 스코프 경계를 통한 자동 정리 보장.
 */
public final class RequestContextHolder {

    public static final ScopedValue<RequestContext> SCOPE = ScopedValue.newInstance();

    private RequestContextHolder() {}

    public static RequestContext get() {
        return SCOPE.isBound() ? SCOPE.get() : null;
    }

    public static RequestContext require() {
        if (!SCOPE.isBound()) {
            throw new IllegalStateException("RequestContext not set. Missing required headers?");
        }
        return SCOPE.get();
    }
}
