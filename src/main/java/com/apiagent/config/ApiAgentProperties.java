package com.apiagent.config;

import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API Agent 설정 (config.py 매핑).
 * 환경변수 접두사: API_AGENT_
 */
@ConfigurationProperties(prefix = "api-agent")
public record ApiAgentProperties(
        // MCP Server
        String mcpName,
        String serviceName,

        // LLM
        String modelName,
        String reasoningEffort,

        // Agent limits
        int maxAgentTurns,
        int maxResponseChars,
        int maxSchemaChars,
        int maxPreviewRows,
        int maxToolResponseChars,

        // Polling limits
        int maxPolls,
        int defaultPollDelayMs,

        // Server
        boolean debug,
        String corsAllowedOrigins,

        // Recipes
        boolean enableRecipes,
        int recipeCacheSize
) {

    private static final Pattern SLUG_PATTERN = Pattern.compile("[^a-z0-9]+");

    public ApiAgentProperties {
        if (mcpName == null) mcpName = "API Agent";
        if (serviceName == null) serviceName = "api-agent";
        if (modelName == null) modelName = "gpt-4o";
        if (reasoningEffort == null) reasoningEffort = "";
        if (maxAgentTurns <= 0) maxAgentTurns = 30;
        if (maxResponseChars <= 0) maxResponseChars = 50000;
        if (maxSchemaChars <= 0) maxSchemaChars = 32000;
        if (maxPreviewRows <= 0) maxPreviewRows = 10;
        if (maxToolResponseChars <= 0) maxToolResponseChars = 32000;
        if (maxPolls <= 0) maxPolls = 20;
        if (defaultPollDelayMs <= 0) defaultPollDelayMs = 3000;
        if (corsAllowedOrigins == null) corsAllowedOrigins = "*";
        if (recipeCacheSize <= 0) recipeCacheSize = 64;
    }

    /**
     * mcpName을 slug화하여 식별자로 사용.
     */
    public String mcpSlug() {
        return SLUG_PATTERN.matcher(mcpName.toLowerCase()).replaceAll("_")
                .replaceAll("^_|_$", "");
    }
}
