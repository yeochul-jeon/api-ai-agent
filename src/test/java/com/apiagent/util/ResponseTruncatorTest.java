package com.apiagent.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.apiagent.config.ApiAgentProperties;

class ResponseTruncatorTest {

    private ResponseTruncator truncator;

    @BeforeEach
    void setUp() {
        var props = new ApiAgentProperties(
                null, null, null, null,
                0, 0, 0, 0, 0, 0, 0,
                false, null, false, 0
        );
        truncator = new ResponseTruncator(props);
    }

    @Test
    void 작은_응답은_그대로_반환() {
        var data = Map.of("key", "value");
        var result = truncator.truncate(data, 1000);

        assertThat(result).contains("key");
        assertThat(result).contains("value");
        assertThat(result).doesNotContain("TRUNCATED");
    }

    @Test
    void 큰_리스트_응답_절삭() {
        var data = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 100; i++) {
            data.add(Map.of("id", i, "text", "A".repeat(50)));
        }

        var result = truncator.truncate(data, 500);
        assertThat(result).contains("SHOWING");
    }

    @Test
    void 큰_문자열_단순_절삭() {
        var longString = "X".repeat(10000);
        var result = truncator.truncate(longString, 100);

        assertThat(result.length()).isLessThanOrEqualTo(120); // 100 + "... [TRUNCATED]"
        assertThat(result).contains("TRUNCATED");
    }
}
