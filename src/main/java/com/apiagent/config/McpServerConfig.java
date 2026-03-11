package com.apiagent.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.apiagent.mcp.McpToolProvider;

/**
 * MCP 서버 설정.
 * McpToolProvider의 @Tool 메서드를 MCP 서버 도구로 등록.
 */
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider mcpToolCallbackProvider(McpToolProvider toolProvider) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(toolProvider)
                .build();
    }
}
