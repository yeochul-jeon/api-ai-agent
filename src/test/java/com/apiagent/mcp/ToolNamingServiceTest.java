package com.apiagent.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolNamingServiceTest {

    private final ToolNamingService service = new ToolNamingService();

    @Test
    void toSnakeCase_변환() {
        assertThat(service.toSnakeCase("My API Agent")).isEqualTo("my_api_agent");
        assertThat(service.toSnakeCase("hello-world")).isEqualTo("hello_world");
        assertThat(service.toSnakeCase("CamelCase")).isEqualTo("camelcase");
    }

    @Test
    void getToolNamePrefix_호스트에서_추출() {
        // flights-api는 하나의 파트 (skip 세트에 없음), example은 skip되지 않음
        assertThat(service.getToolNamePrefix("https://flights-api.example.com/graphql"))
                .isEqualTo("flights_api_example");
        // internal은 skip됨
        assertThat(service.getToolNamePrefix("https://my-service.internal.example.com"))
                .isEqualTo("my_service_example");
    }

    @Test
    void getToolNamePrefix_null_안전() {
        assertThat(service.getToolNamePrefix(null)).isEqualTo("api");
        assertThat(service.getToolNamePrefix("")).isEqualTo("api");
    }

    @Test
    void getFullHostname_추출() {
        assertThat(service.getFullHostname("https://api.example.com/v1"))
                .isEqualTo("api.example.com");
    }

    @Test
    void extractApiName_명시적_이름_우선() {
        assertThat(service.extractApiName("MyService", "https://api.example.com"))
                .isEqualTo("myservice");
    }

    @Test
    void extractApiName_URL_fallback() {
        assertThat(service.extractApiName(null, "https://flights-api.example.com/graphql"))
                .isEqualTo("flights_api_example");
    }

    @Test
    void injectApiContext_설명_변환() {
        var desc = service.injectApiContext("Query API", "api.example.com", "graphql");
        assertThat(desc).isEqualTo("[api.example.com GraphQL API] Query API");
    }
}
