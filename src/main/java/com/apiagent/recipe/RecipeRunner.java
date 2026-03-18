package com.apiagent.recipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.apiagent.client.graphql.GraphqlClient;
import com.apiagent.client.rest.RestApiClient;
import com.apiagent.context.RequestContext;
import com.apiagent.executor.DuckDbExecutor;
import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * 레시피 실행기.
 * 에이전트 없이 레시피 단계를 직접 실행.
 */
@Component
@RequiredArgsConstructor
public class RecipeRunner {

    private static final Logger log = LoggerFactory.getLogger(RecipeRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GraphqlClient graphqlClient;
    private final RestApiClient restClient;
    private final DuckDbExecutor executor;

    /**
     * 레시피 실행 결과.
     */
    public record RunResult(boolean success, Object lastData, List<String> executedSql, String error) {}

    /**
     * 레시피 단계를 직접 실행.
     *
     * @param recipe  레시피 정의
     * @param params  파라미터 값
     * @param ctx     요청 컨텍스트
     * @param baseUrl REST API base URL
     * @return 실행 결과
     */
    @SuppressWarnings("unchecked")
    public RunResult execute(Map<String, Object> recipe, Map<String, Object> params,
                             RequestContext ctx, String baseUrl) {
        var queryResults = new HashMap<String, Object>();
        Object lastData = null;
        var executedSql = new ArrayList<String>();

        // API 단계 실행
        var steps = (List<Map<String, Object>>) recipe.getOrDefault("steps", List.of());
        for (var step : steps) {
            var kind = (String) step.getOrDefault("kind", "");

            if ("graphql".equals(kind)) {
                var template = (String) step.get("query_template");
                if (template == null) {
                    return new RunResult(false, null, executedSql, "missing query_template");
                }
                var query = TemplateRenderer.renderText(template, params);
                var result = graphqlClient.executeQuery(query, null, ctx.targetUrl(), ctx.targetHeaders());
                if (!Boolean.TRUE.equals(result.get("success"))) {
                    return new RunResult(false, null, executedSql, String.valueOf(result.get("error")));
                }
                var name = String.valueOf(step.getOrDefault("name", "data"));
                var extraction = executor.extractTables(result.get("data"), name);
                queryResults.putAll(extraction.tables());
                lastData = extraction.tables().get(name);

            } else if ("rest".equals(kind)) {
                var method = String.valueOf(step.getOrDefault("method", "GET")).toUpperCase();
                var path = String.valueOf(step.getOrDefault("path", ""));
                var name = String.valueOf(step.getOrDefault("name", "data"));

                var pp = renderParamRefs(step.get("path_params"), params);
                var qp = renderParamRefs(step.get("query_params"), params);
                var body = renderParamRefs(step.get("body"), params);

                var result = restClient.executeRequest(
                        method, path,
                        pp instanceof Map<?,?> m ? (Map<String, Object>) m : null,
                        qp instanceof Map<?,?> m ? (Map<String, Object>) m : null,
                        body instanceof Map<?,?> m ? (Map<String, Object>) m : null,
                        baseUrl, ctx.targetHeaders(),
                        false, ctx.allowUnsafePaths()
                );
                if (!Boolean.TRUE.equals(result.get("success"))) {
                    return new RunResult(false, null, executedSql, String.valueOf(result.get("error")));
                }
                var extraction = executor.extractTables(result.get("data"), name);
                queryResults.putAll(extraction.tables());
                lastData = extraction.tables().get(name);

            } else {
                return new RunResult(false, null, executedSql, "unknown step kind: " + kind);
            }
        }

        // SQL 단계 실행
        var sqlSteps = (List<String>) recipe.getOrDefault("sql_steps", List.of());
        for (var sqlTemplate : sqlSteps) {
            var sql = TemplateRenderer.renderText(sqlTemplate, params);
            var result = executor.executeSql(queryResults, sql);
            executedSql.add(sql);
            if (!Boolean.TRUE.equals(result.get("success"))) {
                return new RunResult(false, null, executedSql, String.valueOf(result.get("error")));
            }
            lastData = result.get("result");
        }

        return new RunResult(true, lastData, executedSql, null);
    }

    @SuppressWarnings("unchecked")
    private Object renderParamRefs(Object obj, Map<String, Object> params) {
        if (obj == null) return null;
        return TemplateRenderer.renderParamRefs(obj, params);
    }
}
