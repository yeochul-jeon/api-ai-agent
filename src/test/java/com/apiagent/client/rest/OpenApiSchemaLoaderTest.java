package com.apiagent.client.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.apiagent.config.ApiAgentProperties;

class OpenApiSchemaLoaderTest {

    private OpenApiSchemaLoader loader;

    @BeforeEach
    void setUp() {
        var props = new ApiAgentProperties(
                null, null, null, null,
                0, 0, 0, 0, 0, 0, 0,
                false, null, false, 0
        );
        loader = new OpenApiSchemaLoader(RestClient.builder(), props);
    }

    @Test
    void buildSchemaContext_엔드포인트_생성() {
        var spec = Map.<String, Object>of(
                "openapi", "3.0.0",
                "paths", Map.of(
                        "/users", Map.of(
                                "get", Map.of(
                                        "summary", "List users",
                                        "parameters", List.of(),
                                        "responses", Map.of(
                                                "200", Map.of(
                                                        "content", Map.of(
                                                                "application/json", Map.of(
                                                                        "schema", Map.of(
                                                                                "type", "array",
                                                                                "items", Map.of("$ref", "#/components/schemas/User")
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );

        var context = loader.buildSchemaContext(spec);

        assertThat(context).contains("GET /users");
        assertThat(context).contains("User[]");
        assertThat(context).contains("List users");
    }

    @Test
    void buildSchemaContext_스키마_정의() {
        var spec = Map.<String, Object>of(
                "openapi", "3.0.0",
                "paths", Map.of(),
                "components", Map.of(
                        "schemas", Map.of(
                                "User", Map.of(
                                        "type", "object",
                                        "required", List.of("id", "name"),
                                        "properties", Map.of(
                                                "id", Map.of("type", "string"),
                                                "name", Map.of("type", "string"),
                                                "email", Map.of("type", "string")
                                        )
                                )
                        )
                )
        );

        var context = loader.buildSchemaContext(spec);

        assertThat(context).contains("<schemas>");
        assertThat(context).contains("User {");
        assertThat(context).contains("id: str!");
        assertThat(context).contains("name: str!");
        // email은 required가 아니므로 제외
        assertThat(context).doesNotContain("email");
    }

    @Test
    void getBaseUrlFromSpec_서버에서_추출() {
        var spec = Map.<String, Object>of(
                "servers", List.of(Map.of("url", "https://api.example.com/v1"))
        );

        assertThat(loader.getBaseUrlFromSpec(spec, "")).isEqualTo("https://api.example.com/v1");
    }

    @Test
    void getBaseUrlFromSpec_specUrl_fallback() {
        var spec = Map.<String, Object>of();
        var baseUrl = loader.getBaseUrlFromSpec(spec, "https://api.example.com/openapi.json");

        assertThat(baseUrl).isEqualTo("https://api.example.com");
    }

    @Test
    void schemaToType_기본타입() {
        var spec = Map.<String, Object>of(
                "openapi", "3.0.0",
                "paths", Map.of(
                        "/test", Map.of(
                                "get", Map.of(
                                        "parameters", List.of(
                                                Map.of("name", "id", "in", "path", "required", true,
                                                        "schema", Map.of("type", "integer"))
                                        ),
                                        "responses", Map.of(
                                                "200", Map.of(
                                                        "content", Map.of(
                                                                "application/json", Map.of(
                                                                        "schema", Map.of("type", "boolean")
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );

        var context = loader.buildSchemaContext(spec);
        assertThat(context).contains("id: int");
        assertThat(context).contains("-> bool");
    }

    @Test
    void 빈_스펙은_빈문자열_반환() {
        assertThat(loader.buildSchemaContext(Map.of())).isEmpty();
    }
}
