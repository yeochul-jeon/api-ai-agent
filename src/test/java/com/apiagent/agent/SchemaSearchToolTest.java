package com.apiagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SchemaSearchToolTest {

    private final SchemaSearchTool tool = new SchemaSearchTool(32000);

    private static final String SAMPLE_SCHEMA = """
            {
              "queryType": {
                "fields": [
                  {"name": "users", "type": {"name": "User"}},
                  {"name": "posts", "type": {"name": "Post"}},
                  {"name": "comments", "type": {"name": "Comment"}}
                ]
              }
            }""";

    @Test
    void 패턴_매칭_결과_반환() {
        var result = tool.search(SAMPLE_SCHEMA, "users", 2, 0);
        assertThat(result).contains("users");
        assertThat(result).contains("matches");
    }

    @Test
    void 매칭_없으면_no_matches() {
        var result = tool.search(SAMPLE_SCHEMA, "nonexistent", 2, 0);
        assertThat(result).isEqualTo("(no matches)");
    }

    @Test
    void 빈_스키마_에러() {
        assertThat(tool.search("", "test", 2, 0)).isEqualTo("error: schema empty");
        assertThat(tool.search(null, "test", 2, 0)).isEqualTo("error: schema empty");
    }

    @Test
    void 잘못된_regex_에러() {
        var result = tool.search(SAMPLE_SCHEMA, "[invalid", 2, 0);
        assertThat(result).startsWith("error: invalid regex");
    }

    @Test
    void offset_페이지네이션() {
        var result = tool.search(SAMPLE_SCHEMA, "name", 1, 100);
        assertThat(result).contains("beyond");
    }
}
