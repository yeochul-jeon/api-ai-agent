package com.apiagent.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestContextFilterTest {

    private final RequestContextFilter filter = new RequestContextFilter();

    @Test
    void 필수_헤더로_컨텍스트_생성() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Target-URL", "https://api.example.com/graphql");
        request.addHeader("X-API-Type", "graphql");
        request.addHeader("X-Target-Headers", "{\"Authorization\": \"Bearer token123\"}");

        var captured = new RequestContext[1];
        var chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                captured[0] = RequestContextHolder.get();
            }
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(captured[0]).isNotNull();
        assertThat(captured[0].targetUrl()).isEqualTo("https://api.example.com/graphql");
        assertThat(captured[0].apiType()).isEqualTo("graphql");
        assertThat(captured[0].targetHeaders()).containsEntry("Authorization", "Bearer token123");
    }

    @Test
    void 헤더_없으면_컨텍스트_null() throws Exception {
        var request = new MockHttpServletRequest();

        var captured = new RequestContext[1];
        var chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                captured[0] = RequestContextHolder.get();
            }
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(captured[0]).isNull();
    }

    @Test
    void 필터_후_컨텍스트_정리() throws Exception {
        // ScopedValue는 스코프 종료 시 자동 정리 — 헤더 포함 요청으로 필터 실행 후 검증
        var request = new MockHttpServletRequest();
        request.addHeader("X-Target-URL", "https://api.example.com/graphql");
        request.addHeader("X-API-Type", "graphql");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        // 필터 doFilter 완료 후 ScopedValue 스코프가 종료되므로 null이어야 함
        assertThat(RequestContextHolder.get()).isNull();
    }
}
