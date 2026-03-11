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
        RequestContextHolder.set(new RequestContext("url", "graphql", null, null, null, false, null));

        var request = new MockHttpServletRequest();
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(RequestContextHolder.get()).isNull();
    }
}
