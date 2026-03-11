package com.apiagent.client.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class RestApiClientTest {

    @Test
    void unsafe_메서드_차단() {
        var client = new RestApiClient(org.springframework.web.client.RestClient.builder());
        var result = client.executeRequest("POST", "/users", null, null,
                null, "http://example.com", null, false, null);

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error").toString()).contains("not allowed");
    }

    @Test
    void unsafe_메서드_허용_패스() {
        var client = new RestApiClient(org.springframework.web.client.RestClient.builder());
        // allow_unsafe=true이면 허용되지만, 실제 요청은 실패할 수 있음
        // 여기서는 allowUnsafePaths 매칭만 테스트
        assertThat(RestApiClient.isPathAllowed("/graphql", List.of("/graphql"))).isTrue();
        assertThat(RestApiClient.isPathAllowed("/users", List.of("/graphql"))).isFalse();
        assertThat(RestApiClient.isPathAllowed("/api/v1/data", List.of("/api/*"))).isTrue();
    }

    @Test
    void globMatch_패턴() {
        assertThat(RestApiClient.globMatch("/users/*", "/users/123")).isTrue();
        assertThat(RestApiClient.globMatch("/users/*", "/posts/123")).isFalse();
        assertThat(RestApiClient.globMatch("*", "/anything")).isTrue();
    }

    @Test
    void baseUrl_없으면_에러() {
        var client = new RestApiClient(org.springframework.web.client.RestClient.builder());
        var result = client.executeRequest("GET", "/users", null, null,
                null, "", null, false, null);

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error").toString()).contains("No base URL");
    }
}
