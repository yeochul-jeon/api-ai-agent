package com.apiagent.agent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.apiagent.client.graphql.GraphqlClient;
import com.apiagent.config.ApiAgentProperties;
import com.apiagent.context.RequestContext;
import com.apiagent.executor.DuckDbExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * GraphQL 에이전트 서비스.
 * 인트로스펙션 → 스키마 컨텍스트 → 에이전트 실행.
 */
@Service
@RequiredArgsConstructor
public class GraphqlAgentService {

    private static final Logger log = LoggerFactory.getLogger(GraphqlAgentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GraphqlClient graphqlClient;
    private final DuckDbExecutor executor;
    private final ApiAgentProperties properties;
    private final ChatClient.Builder chatClientBuilder;

    static final String INTROSPECTION_QUERY = """
            {
              __schema {
                queryType {
                  fields { name description args { name type { ...TypeRef } defaultValue } type { ...TypeRef } }
                }
                types {
                  name kind description
                  fields { name description args { name type { ...TypeRef } defaultValue } type { ...TypeRef } }
                  enumValues { name description }
                  inputFields { name type { ...TypeRef } defaultValue }
                  interfaces { name }
                  possibleTypes { name }
                }
              }
            }
            fragment TypeRef on __Type {
              name kind ofType { name kind ofType { name kind ofType { name } } }
            }""";

    /**
     * 자연어 질의를 GraphQL API에 대해 처리.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> processQuery(String question, RequestContext ctx) {
        try {
            // 스키마 가져오기
            var schemaResult = graphqlClient.executeQuery(
                    INTROSPECTION_QUERY, null, ctx.targetUrl(), ctx.targetHeaders());

            if (!Boolean.TRUE.equals(schemaResult.get("success"))) {
                return errorResult("Failed to fetch GraphQL schema: " + schemaResult.get("error"));
            }

            var data = (Map<String, Object>) schemaResult.get("data");
            var schema = (Map<String, Object>) data.get("__schema");
            var rawSchema = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
            var schemaCtx = buildSchemaContext(schema);

            // 스키마 절삭
            if (schemaCtx.length() > properties.maxSchemaChars()) {
                schemaCtx = schemaCtx.substring(0, properties.maxSchemaChars())
                        + "\n[SCHEMA TRUNCATED - use search_schema() to explore]";
            }

            // 에이전트 실행
            var state = new AgentToolFactory.AgentState();
            var tools = AgentToolFactory.createGraphqlTools(
                    graphqlClient, executor, ctx, state, rawSchema,
                    properties.maxToolResponseChars());

            var prompt = AgentPrompts.buildGraphqlPrompt(properties.maxAgentTurns(), "");
            var augmentedQuery = schemaCtx + "\n\nQuestion: " + question;

            var runner = new AgentRunner(chatClientBuilder);
            var result = runner.run(prompt, augmentedQuery, properties.maxAgentTurns(), tools);

            return buildResult(result, state, "queries");

        } catch (Exception e) {
            log.error("GraphQL 에이전트 오류", e);
            return errorResult(e.getMessage());
        }
    }

    /**
     * 인트로스펙션 스키마에서 compact SDL 컨텍스트 빌드.
     */
    @SuppressWarnings("unchecked")
    String buildSchemaContext(Map<String, Object> schema) {
        var lines = new java.util.ArrayList<String>();
        lines.add("<queries>");

        var queryType = (Map<String, Object>) schema.getOrDefault("queryType", Map.of());
        var fields = (List<Map<String, Object>>) queryType.getOrDefault("fields", List.of());

        for (var f : fields) {
            var desc = f.get("description") != null ? " # " + f.get("description") : "";
            var args = (List<Map<String, Object>>) f.getOrDefault("args", List.of());
            var requiredArgs = args.stream()
                    .filter(a -> isRequired((Map<String, Object>) a.get("type")))
                    .toList();
            var argStr = requiredArgs.stream()
                    .map(this::formatArg)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            lines.add("%s(%s) -> %s%s".formatted(f.get("name"), argStr, formatType((Map<String, Object>) f.get("type")), desc));
        }

        var allTypes = (List<Map<String, Object>>) schema.getOrDefault("types", List.of());
        var objects = allTypes.stream()
                .filter(t -> "OBJECT".equals(t.get("kind"))
                        && !t.get("name").toString().startsWith("__")
                        && !List.of("Query", "Mutation", "Subscription").contains(t.get("name")))
                .toList();
        var enums = allTypes.stream()
                .filter(t -> "ENUM".equals(t.get("kind")) && !t.get("name").toString().startsWith("__"))
                .toList();

        lines.add("\n<types>");
        for (var t : objects) {
            var typeFields = (List<Map<String, Object>>) t.getOrDefault("fields", List.of());
            var fieldLines = typeFields.stream()
                    .map(fld -> "  %s: %s".formatted(fld.get("name"), formatType((Map<String, Object>) fld.get("type"))))
                    .toList();
            lines.add("%s {\n%s\n}".formatted(t.get("name"), String.join("\n", fieldLines)));
        }

        lines.add("\n<enums>");
        for (var e : enums) {
            var vals = (List<Map<String, Object>>) e.getOrDefault("enumValues", List.of());
            var valStr = vals.stream().map(v -> v.get("name").toString()).reduce((a, b) -> a + " | " + b).orElse("");
            lines.add("%s: %s".formatted(e.get("name"), valStr));
        }

        return String.join("\n", lines);
    }

    @SuppressWarnings("unchecked")
    private String formatType(Map<String, Object> type) {
        if (type == null) return "?";
        var kind = (String) type.get("kind");
        var name = (String) type.get("name");
        var ofType = (Map<String, Object>) type.get("ofType");

        if ("NON_NULL".equals(kind)) return formatType(ofType) + "!";
        if ("LIST".equals(kind)) return "[" + formatType(ofType) + "]";
        return name != null ? name : "?";
    }

    @SuppressWarnings("unchecked")
    private boolean isRequired(Map<String, Object> type) {
        return type != null && "NON_NULL".equals(type.get("kind"));
    }

    @SuppressWarnings("unchecked")
    private String formatArg(Map<String, Object> arg) {
        var typeStr = formatType((Map<String, Object>) arg.get("type"));
        var defaultVal = arg.get("defaultValue");
        if (defaultVal != null) {
            return "%s: %s = %s".formatted(arg.get("name"), typeStr, defaultVal);
        }
        return "%s: %s".formatted(arg.get("name"), typeStr);
    }

    private Map<String, Object> buildResult(AgentRunner.AgentResult result,
                                             AgentToolFactory.AgentState state, String callsKey) {
        if (result.maxTurnsExceeded()) {
            var response = new HashMap<String, Object>();
            response.put("ok", state.lastResult != null);
            response.put("data", state.lastResult != null ? "[Partial] Data retrieved but max turns exceeded." : null);
            response.put("result", state.lastResult);
            response.put(callsKey, state.executedQueries);
            response.put("error", state.lastResult == null ? "Max turns exceeded" : null);
            return response;
        }

        var response = new HashMap<String, Object>();
        response.put("ok", true);
        response.put("data", result.directReturn() || state.returnDirectly ? null : result.finalOutput());
        response.put("result", state.lastResult);
        response.put(callsKey, state.executedQueries);
        response.put("error", null);
        return response;
    }

    private Map<String, Object> errorResult(String error) {
        var response = new HashMap<String, Object>();
        response.put("ok", false);
        response.put("data", null);
        response.put("queries", List.of());
        response.put("error", error);
        return response;
    }
}
