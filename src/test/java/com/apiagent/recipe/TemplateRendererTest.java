package com.apiagent.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TemplateRendererTest {

    @Test
    void 텍스트_템플릿_치환() {
        var result = TemplateRenderer.renderText(
                "{ users(id: \"{{userId}}\") { name } }",
                Map.of("userId", "123")
        );
        assertThat(result).isEqualTo("{ users(id: \"123\") { name } }");
    }

    @Test
    void 다중_파라미터_치환() {
        var result = TemplateRenderer.renderText(
                "SELECT * FROM {{table}} WHERE id = '{{id}}'",
                Map.of("table", "users", "id", "abc")
        );
        assertThat(result).contains("users").contains("abc");
    }

    @Test
    void 누락_파라미터_에러() {
        assertThatThrownBy(() ->
                TemplateRenderer.renderText("{{missing}}", Map.of())
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing param");
    }

    @Test
    void paramRef_치환() {
        var obj = Map.<String, Object>of(
                "path_params", Map.of("$param", "userId"),
                "fixed", "value"
        );
        var result = TemplateRenderer.renderParamRefs(obj, Map.of("userId", "123"));

        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var map = (Map<String, Object>) result;
        assertThat(map.get("path_params")).isEqualTo("123");
        assertThat(map.get("fixed")).isEqualTo("value");
    }

    @Test
    void paramRef_중첩_리스트() {
        var obj = List.of(Map.of("$param", "name"), "literal");
        var result = TemplateRenderer.renderParamRefs(obj, Map.of("name", "Alice"));

        assertThat(result).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        var list = (List<Object>) result;
        assertThat(list.get(0)).isEqualTo("Alice");
        assertThat(list.get(1)).isEqualTo("literal");
    }

    @Test
    void boolean_및_null_변환() {
        var result = TemplateRenderer.renderText(
                "active={{active}} null={{nullable}}",
                Map.of("active", true, "nullable", "null")
        );
        assertThat(result).contains("active=true");
    }
}
