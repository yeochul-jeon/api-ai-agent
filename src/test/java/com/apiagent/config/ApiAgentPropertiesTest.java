package com.apiagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiAgentPropertiesTest {

    @Test
    void 기본값이_올바르게_설정된다() {
        var props = new ApiAgentProperties(
                null, null, null, null,
                0, 0, 0, 0, 0,
                0, 0,
                false, null,
                false, 0
        );

        assertThat(props.mcpName()).isEqualTo("API Agent");
        assertThat(props.serviceName()).isEqualTo("api-agent");
        assertThat(props.modelName()).isEqualTo("gpt-4o");
        assertThat(props.maxAgentTurns()).isEqualTo(30);
        assertThat(props.maxResponseChars()).isEqualTo(50000);
        assertThat(props.maxSchemaChars()).isEqualTo(32000);
        assertThat(props.maxPreviewRows()).isEqualTo(10);
        assertThat(props.maxToolResponseChars()).isEqualTo(32000);
        assertThat(props.maxPolls()).isEqualTo(20);
        assertThat(props.defaultPollDelayMs()).isEqualTo(3000);
        assertThat(props.corsAllowedOrigins()).isEqualTo("*");
        assertThat(props.recipeCacheSize()).isEqualTo(64);
    }

    @Test
    void mcpSlug_가_올바르게_변환된다() {
        var props = new ApiAgentProperties(
                "My API Agent", null, null, null,
                0, 0, 0, 0, 0,
                0, 0,
                false, null,
                false, 0
        );

        assertThat(props.mcpSlug()).isEqualTo("my_api_agent");
    }

    @Test
    void 사용자_지정값이_유지된다() {
        var props = new ApiAgentProperties(
                "Custom Agent", "custom-svc", "gpt-5", "high",
                50, 100000, 64000, 20, 64000,
                30, 5000,
                true, "http://localhost:3000",
                true, 128
        );

        assertThat(props.mcpName()).isEqualTo("Custom Agent");
        assertThat(props.serviceName()).isEqualTo("custom-svc");
        assertThat(props.modelName()).isEqualTo("gpt-5");
        assertThat(props.reasoningEffort()).isEqualTo("high");
        assertThat(props.maxAgentTurns()).isEqualTo(50);
        assertThat(props.enableRecipes()).isTrue();
        assertThat(props.recipeCacheSize()).isEqualTo(128);
    }
}
