package com.apiagent.agent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.apiagent.client.graphql.GraphqlClient;
import com.apiagent.client.rest.RestApiClient;
import com.apiagent.context.RequestContext;
import com.apiagent.executor.DuckDbExecutor;
import tools.jackson.databind.ObjectMapper;

/**
 * 요청별 도구 콜백 생성 팩토리.
 */
public class AgentToolFactory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 에이전트 실행에 필요한 상태를 관리하는 컨텍스트.
     */
    public static class AgentState {
        final Map<String, Object> queryResults = new HashMap<>();
        Object lastResult = null;
        final java.util.List<String> executedQueries = new java.util.ArrayList<>();
        final java.util.List<Map<String, Object>> apiCalls = new java.util.ArrayList<>();
        final java.util.List<String> sqlSteps = new java.util.ArrayList<>();
        boolean returnDirectly = false;
    }

    /**
     * GraphQL 에이전트 도구 콜백 생성.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Function<Map<String, Object>, String>> createGraphqlTools(
            GraphqlClient graphqlClient,
            DuckDbExecutor executor,
            RequestContext ctx,
            AgentState state,
            String rawSchema,
            int maxToolResponseChars) {

        var tools = new HashMap<String, Function<Map<String, Object>, String>>();
        var searchTool = new SchemaSearchTool(maxToolResponseChars);

        tools.put("graphql_query", args -> {
            var query = (String) args.get("query");
            var name = args.getOrDefault("name", "data").toString();
            var returnDirectly = Boolean.TRUE.equals(args.get("return_directly"));

            var result = graphqlClient.executeQuery(query, null, ctx.targetUrl(), ctx.targetHeaders());

            if (Boolean.TRUE.equals(result.get("success"))) {
                var data = result.get("data");
                var extraction = executor.extractTables(data, name);
                state.queryResults.putAll(extraction.tables());
                var stored = extraction.tables().get(name);
                if (stored != null) {
                    state.lastResult = stored;
                }
                state.executedQueries.add(query);

                if (returnDirectly) {
                    state.returnDirectly = true;
                }

                // 절삭된 컨텍스트 반환
                if (stored instanceof List<?> list) {
                    return toJson(Map.of("success", true,
                            "data", executor.truncateForContext(list, name, maxToolResponseChars)));
                }
            }

            return toJson(result);
        });

        tools.put("sql_query", args -> {
            var sql = (String) args.get("sql");
            var returnDirectly = Boolean.TRUE.equals(args.get("return_directly"));

            if (state.queryResults.isEmpty()) {
                return toJson(Map.of("success", false, "error", "No data. Call graphql_query first."));
            }

            var result = executor.executeSql(state.queryResults, sql);

            if (Boolean.TRUE.equals(result.get("success"))) {
                var rows = result.get("result");
                state.lastResult = rows;
                state.sqlSteps.add(sql);
                if (returnDirectly) {
                    state.returnDirectly = true;
                }
                if (rows instanceof List<?> list) {
                    return toJson(Map.of("success", true,
                            "data", executor.truncateForContext(list, "sql_result", maxToolResponseChars)));
                }
            }

            return toJson(result);
        });

        tools.put("search_schema", args -> {
            var pattern = (String) args.get("pattern");
            int context = args.containsKey("context") ? ((Number) args.get("context")).intValue() : 10;
            int offset = args.containsKey("offset") ? ((Number) args.get("offset")).intValue() : 0;
            return searchTool.search(rawSchema, pattern, context, offset);
        });

        return tools;
    }

    /**
     * REST 에이전트 도구 콜백 생성.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Function<Map<String, Object>, String>> createRestTools(
            RestApiClient restClient,
            DuckDbExecutor executor,
            RequestContext ctx,
            String baseUrl,
            AgentState state,
            String rawSchema,
            int maxToolResponseChars) {

        var tools = new HashMap<String, Function<Map<String, Object>, String>>();
        var searchTool = new SchemaSearchTool(maxToolResponseChars);

        tools.put("rest_call", args -> {
            var method = (String) args.get("method");
            var path = (String) args.get("path");
            var pathParams = parseJsonArg(args.get("path_params"));
            var queryParams = parseJsonArg(args.get("query_params"));
            var body = parseJsonArg(args.get("body"));
            var name = args.getOrDefault("name", "data").toString();
            var returnDirectly = Boolean.TRUE.equals(args.get("return_directly"));

            var result = restClient.executeRequest(
                    method, path,
                    pathParams instanceof Map<?,?> m ? (Map<String,Object>) m : null,
                    queryParams instanceof Map<?,?> m ? (Map<String,Object>) m : null,
                    body instanceof Map<?,?> m ? (Map<String,Object>) m : null,
                    baseUrl, ctx.targetHeaders(),
                    false, ctx.allowUnsafePaths()
            );

            if (Boolean.TRUE.equals(result.get("success"))) {
                var data = result.get("data");
                var extraction = executor.extractTables(data, name);
                state.queryResults.putAll(extraction.tables());
                var stored = extraction.tables().get(name);
                if (stored != null) {
                    state.lastResult = stored;
                }
                state.apiCalls.add(Map.of(
                        "method", method, "path", path, "name", name, "success", true));

                if (returnDirectly) {
                    state.returnDirectly = true;
                }

                if (stored instanceof List<?> list) {
                    return toJson(Map.of("success", true,
                            "data", executor.truncateForContext(list, name, maxToolResponseChars)));
                }
            }

            return toJson(result);
        });

        tools.put("sql_query", args -> {
            var sql = (String) args.get("sql");
            var returnDirectly = Boolean.TRUE.equals(args.get("return_directly"));

            if (state.queryResults.isEmpty()) {
                return toJson(Map.of("success", false, "error", "No data. Call rest_call first."));
            }

            var result = executor.executeSql(state.queryResults, sql);

            if (Boolean.TRUE.equals(result.get("success"))) {
                var rows = result.get("result");
                state.lastResult = rows;
                state.sqlSteps.add(sql);
                if (returnDirectly) {
                    state.returnDirectly = true;
                }
                if (rows instanceof List<?> list) {
                    return toJson(Map.of("success", true,
                            "data", executor.truncateForContext(list, "sql_result", maxToolResponseChars)));
                }
            }

            return toJson(result);
        });

        tools.put("search_schema", args -> {
            var pattern = (String) args.get("pattern");
            int context = args.containsKey("context") ? ((Number) args.get("context")).intValue() : 10;
            int offset = args.containsKey("offset") ? ((Number) args.get("offset")).intValue() : 0;
            return searchTool.search(rawSchema, pattern, context, offset);
        });

        return tools;
    }

    @SuppressWarnings("unchecked")
    private static Object parseJsonArg(Object arg) {
        if (arg == null) return null;
        if (arg instanceof Map || arg instanceof List) return arg;
        if (arg instanceof String s && !s.isBlank()) {
            try {
                return MAPPER.readValue(s, Map.class);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static String toJson(Object obj) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\": \"serialization failed\"}";
        }
    }
}
