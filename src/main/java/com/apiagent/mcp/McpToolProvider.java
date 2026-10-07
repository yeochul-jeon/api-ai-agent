package com.apiagent.mcp;

import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import com.apiagent.agent.GraphqlAgentService;
import com.apiagent.agent.RestAgentService;
import com.apiagent.context.RequestContext;
import com.apiagent.context.RequestContextHolder;
import com.apiagent.util.CsvConverter;
import com.apiagent.util.ResponseTruncator;
import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * MCP 도구 제공자.
 * _query와 _execute 도구를 MCP 서버에 노출.
 */
@Service
@RequiredArgsConstructor
public class McpToolProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GraphqlAgentService graphqlAgent;
    private final RestAgentService restAgent;
    private final ResponseTruncator truncator;
    private final CsvConverter csvConverter;

    @Tool(description = "자연어로 API를 질의합니다. GraphQL 또는 REST API에 대해 자연어 질문을 처리하여 데이터를 반환합니다.")
    public String _query(@ToolParam(description = "자연어 질문") String question) {
        var ctx = RequestContextHolder.require();

        Map<String, Object> result = switch (ctx.apiType()) {
            case "graphql" -> graphqlAgent.processQuery(question, ctx);
            case "rest" -> restAgent.processQuery(question, ctx);
            default -> Map.of("ok", false, "error", "Unsupported API type: " + ctx.apiType());
        };

        return formatResponse(result, ctx);
    }

    private String formatResponse(Map<String, Object> result, RequestContext ctx) {
        try {
            // data가 null이면 (direct return) result를 CSV로 변환
            if (result.get("data") == null && result.get("result") != null) {
                var csv = csvConverter.toCsv(result.get("result"));
                if (!csv.isBlank()) {
                    return csv;
                }
            }

            return truncator.truncate(result, null);
        } catch (Exception e) {
            return "{\"error\": \"Response formatting failed: " + e.getMessage() + "\"}";
        }
    }
}
