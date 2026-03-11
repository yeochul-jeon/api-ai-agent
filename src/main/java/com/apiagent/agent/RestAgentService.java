package com.apiagent.agent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.apiagent.client.rest.OpenApiSchemaLoader;
import com.apiagent.client.rest.RestApiClient;
import com.apiagent.config.ApiAgentProperties;
import com.apiagent.context.RequestContext;
import com.apiagent.executor.DuckDbExecutor;

import lombok.RequiredArgsConstructor;

/**
 * REST 에이전트 서비스.
 * OpenAPI → 엔드포인트 DSL → 에이전트 실행.
 */
@Service
@RequiredArgsConstructor
public class RestAgentService {

    private static final Logger log = LoggerFactory.getLogger(RestAgentService.class);

    private final RestApiClient restClient;
    private final OpenApiSchemaLoader schemaLoader;
    private final DuckDbExecutor executor;
    private final ApiAgentProperties properties;
    private final ChatClient.Builder chatClientBuilder;

    /**
     * 자연어 질의를 REST API에 대해 처리.
     */
    public Map<String, Object> processQuery(String question, RequestContext ctx) {
        try {
            // OpenAPI 스펙 가져오기
            var schemaContext = schemaLoader.fetchSchemaContext(ctx.targetUrl(), ctx.targetHeaders());

            if (schemaContext.context().isBlank()) {
                return errorResult("Failed to load OpenAPI schema. Check X-Target-URL and auth headers.");
            }

            // base URL 결정
            var baseUrl = ctx.baseUrl() != null ? ctx.baseUrl() : schemaContext.baseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                return errorResult("Could not determine base URL. Set X-Base-URL header or ensure spec has 'servers' field.");
            }

            // 에이전트 실행
            var state = new AgentToolFactory.AgentState();
            var tools = AgentToolFactory.createRestTools(
                    restClient, executor, ctx, baseUrl, state,
                    schemaContext.rawSpecJson(),
                    properties.maxToolResponseChars());

            var prompt = AgentPrompts.buildRestPrompt(properties.maxAgentTurns(), "");
            var augmentedQuery = schemaContext.context() + "\n\nQuestion: " + question;

            var runner = new AgentRunner(chatClientBuilder);
            var result = runner.run(prompt, augmentedQuery, properties.maxAgentTurns(), tools);

            return buildResult(result, state);

        } catch (Exception e) {
            log.error("REST 에이전트 오류", e);
            return errorResult(e.getMessage());
        }
    }

    private Map<String, Object> buildResult(AgentRunner.AgentResult result,
                                             AgentToolFactory.AgentState state) {
        if (result.maxTurnsExceeded()) {
            var response = new HashMap<String, Object>();
            response.put("ok", state.lastResult != null);
            response.put("data", state.lastResult != null ? "[Partial] Data retrieved but max turns exceeded." : null);
            response.put("result", state.lastResult);
            response.put("api_calls", state.apiCalls);
            response.put("error", state.lastResult == null ? "Max turns exceeded" : null);
            return response;
        }

        var response = new HashMap<String, Object>();
        response.put("ok", true);
        response.put("data", result.directReturn() || state.returnDirectly ? null : result.finalOutput());
        response.put("result", state.lastResult);
        response.put("api_calls", state.apiCalls);
        response.put("error", null);
        return response;
    }

    private Map<String, Object> errorResult(String error) {
        var response = new HashMap<String, Object>();
        response.put("ok", false);
        response.put("data", null);
        response.put("api_calls", List.of());
        response.put("error", error);
        return response;
    }
}
