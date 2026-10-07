package com.apiagent.client.rest;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.apiagent.config.ApiAgentProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import lombok.RequiredArgsConstructor;

/**
 * OpenAPI 3.x 스펙 로딩 및 compact DSL 컨텍스트 빌더.
 */
@Component
@RequiredArgsConstructor
public class OpenApiSchemaLoader {

    private static final Logger log = LoggerFactory.getLogger(OpenApiSchemaLoader.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final ObjectMapper YAML_MAPPER = YAMLMapper.builder().build();

    private final RestClient.Builder restClientBuilder;
    private final ApiAgentProperties properties;

    /**
     * OpenAPI 스펙을 URL에서 로딩.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> loadSpec(String specUrl, Map<String, String> headers) {
        if (specUrl == null || specUrl.isBlank()) {
            return Map.of();
        }

        try {
            var client = restClientBuilder.build();
            var spec = client.get().uri(specUrl);
            if (headers != null) {
                headers.forEach(spec::header);
            }
            var raw = spec.retrieve().body(String.class);
            if (raw == null || raw.isBlank()) {
                return Map.of();
            }

            Map<String, Object> parsed;
            if (raw.trim().startsWith("{")) {
                parsed = JSON_MAPPER.readValue(raw, Map.class);
            } else {
                parsed = YAML_MAPPER.readValue(raw, Map.class);
            }

            // OpenAPI 3.x 검증
            var version = String.valueOf(parsed.getOrDefault("openapi", ""));
            if (!version.startsWith("3.")) {
                log.warn("지원되지 않는 OpenAPI 버전: {}, 3.x 필요", version);
                return Map.of();
            }

            return parsed;
        } catch (Exception e) {
            log.error("OpenAPI 스펙 로딩 실패: {}", specUrl, e);
            return Map.of();
        }
    }

    /**
     * 스펙에서 base URL 추출.
     */
    @SuppressWarnings("unchecked")
    public String getBaseUrlFromSpec(Map<String, Object> spec, String specUrl) {
        var servers = spec.get("servers");
        if (servers instanceof List<?> list && !list.isEmpty()) {
            var first = list.getFirst();
            if (first instanceof Map<?, ?> map) {
                var url = map.get("url");
                if (url instanceof String s) {
                    return s;
                }
            }
        }

        if (specUrl != null && !specUrl.isBlank()) {
            try {
                var uri = URI.create(specUrl);
                return uri.getScheme() + "://" + uri.getAuthority();
            } catch (Exception ignored) {
            }
        }

        return "";
    }

    /**
     * OpenAPI 스펙에서 compact DSL 컨텍스트 빌드.
     */
    @SuppressWarnings("unchecked")
    public String buildSchemaContext(Map<String, Object> spec) {
        if (spec.isEmpty()) return "";

        var lines = new ArrayList<String>();
        lines.add("<endpoints>");

        var paths = asMap(spec.get("paths"));
        for (var entry : paths.entrySet()) {
            var path = entry.getKey();
            if (!path.startsWith("/")) continue;
            var pathItem = asMap(entry.getValue());

            for (var method : List.of("get", "post", "put", "delete", "patch")) {
                var op = asMap(pathItem.get(method));
                if (op.isEmpty()) continue;

                var params = asList(pathItem.get("parameters"));
                params.addAll(asList(op.get("parameters")));

                // Request body
                var bodyType = "";
                if (Set.of("post", "put", "patch").contains(method)) {
                    bodyType = extractBodyType(op);
                }

                var paramStr = formatParams(params);
                if (!bodyType.isEmpty()) {
                    paramStr = bodyType + (paramStr.isEmpty() ? "" : ", " + paramStr);
                }

                var responseType = extractResponseType(asMap(op.get("responses")));
                var summary = firstNonEmpty(op, "description", "summary", "operationId");
                var desc = summary.isEmpty() ? "" : "  # " + summary;

                lines.add("%s %s(%s) -> %s%s".formatted(
                        method.toUpperCase(), path, paramStr, responseType, desc));
            }
        }

        // Schemas
        var components = asMap(spec.get("components"));
        var schemas = asMap(components.get("schemas"));
        if (!schemas.isEmpty()) {
            lines.add("\n<schemas>");
            for (var entry : schemas.entrySet()) {
                lines.add(formatSchema(entry.getKey(), entry.getValue()));
            }
        }

        // Auth
        var securitySchemes = asMap(components.get("securitySchemes"));
        if (!securitySchemes.isEmpty()) {
            lines.add("\n<auth>");
            for (var entry : securitySchemes.entrySet()) {
                var scheme = asMap(entry.getValue());
                var type = str(scheme.get("type"));
                var line = switch (type) {
                    case "http" -> "%s: HTTP %s %s".formatted(
                            entry.getKey(), str(scheme.get("scheme")), str(scheme.get("bearerFormat"))).trim();
                    case "apiKey" -> "%s: API key in %s '%s'".formatted(
                            entry.getKey(), str(scheme.get("in")), str(scheme.get("name")));
                    case "oauth2" -> entry.getKey() + ": OAuth2";
                    default -> entry.getKey() + ": " + type;
                };
                lines.add(line);
            }
        }

        return String.join("\n", lines);
    }

    /**
     * 스펙 fetch → (truncated context, base url, raw spec json) 반환.
     */
    public SchemaContext fetchSchemaContext(String specUrl, Map<String, String> headers) {
        var spec = loadSpec(specUrl, headers);
        if (spec.isEmpty()) {
            return new SchemaContext("", "", "");
        }

        String rawJson;
        try {
            rawJson = JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(spec);
        } catch (JacksonException e) {
            rawJson = "";
        }

        var dslContext = buildSchemaContext(spec);
        var baseUrl = getBaseUrlFromSpec(spec, specUrl);

        var context = dslContext;
        if (context.length() > properties.maxSchemaChars()) {
            context = context.substring(0, properties.maxSchemaChars())
                    + "\n[SCHEMA TRUNCATED - use search_schema() to explore]";
        }

        return new SchemaContext(context, baseUrl, rawJson);
    }

    public record SchemaContext(String context, String baseUrl, String rawSpecJson) {}

    // === 내부 헬퍼 ===

    @SuppressWarnings("unchecked")
    private String extractBodyType(Map<String, Object> op) {
        var reqBody = asMap(op.get("requestBody"));
        if (reqBody.isEmpty()) return "";
        var content = asMap(reqBody.get("content"));
        var jsonContent = asMap(content.get("application/json"));
        var schema = jsonContent.get("schema");
        if (schema == null) return "";
        var required = Boolean.TRUE.equals(reqBody.get("required"));
        return "body: " + schemaToType(schema) + (required ? "!" : "");
    }

    private String extractResponseType(Map<String, Object> responses) {
        for (var code : List.of("200", "201", "default")) {
            var resp = asMap(responses.get(code));
            var content = asMap(resp.get("content"));
            var jsonContent = asMap(content.get("application/json"));
            var schema = jsonContent.get("schema");
            if (schema != null) {
                return schemaToType(schema);
            }
        }
        return "any";
    }

    @SuppressWarnings("unchecked")
    private String formatParams(List<Object> params) {
        var parts = new ArrayList<String>();
        for (var p : params) {
            if (!(p instanceof Map<?, ?> map)) continue;
            var name = str(map.get("name"));
            var required = Boolean.TRUE.equals(map.get("required"))
                    || "path".equals(str(map.get("in")));
            if (!required) continue; // 선택적 파라미터 제외
            var schema = map.get("schema");
            if (schema == null) schema = Map.of();
            parts.add(name + ": " + schemaToType(schema));
        }
        return String.join(", ", parts);
    }

    @SuppressWarnings("unchecked")
    private String formatSchema(String name, Object schema) {
        if (!(schema instanceof Map<?, ?> map)) {
            return name + ": " + schemaToType(schema);
        }

        if ("object".equals(str(map.get("type"))) || map.containsKey("properties")) {
            var props = asMap(map.get("properties"));
            var requiredList = asList(map.get("required"));
            var requiredSet = Set.copyOf(requiredList.stream().map(Object::toString).toList());
            var fields = new ArrayList<String>();
            for (var entry : props.entrySet()) {
                if (!requiredSet.contains(entry.getKey())) continue;
                fields.add(entry.getKey() + ": " + schemaToType(entry.getValue()) + "!");
            }
            return "%s { %s }".formatted(name, String.join(", ", fields));
        }

        if (map.containsKey("enum")) {
            var vals = asList(map.get("enum"));
            return "%s: enum(%s)".formatted(name, vals.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(" | ")));
        }

        return name + ": " + schemaToType(schema);
    }

    @SuppressWarnings("unchecked")
    private String schemaToType(Object schema) {
        if (schema == null || Boolean.TRUE.equals(schema) || Boolean.FALSE.equals(schema)) {
            return "any";
        }
        if (!(schema instanceof Map<?, ?> map)) return "any";

        // $ref
        if (map.containsKey("$ref")) {
            var ref = str(map.get("$ref"));
            var parts = ref.split("/");
            return parts[parts.length - 1];
        }

        var type = map.get("type");
        String typeStr;

        if (type instanceof List<?> list) {
            var nonNull = list.stream().filter(t -> !"null".equals(t)).toList();
            typeStr = nonNull.isEmpty() ? "any" : nonNull.getFirst().toString();
        } else {
            typeStr = type != null ? type.toString() : "any";
        }

        return switch (typeStr) {
            case "array" -> {
                var items = map.get("items");
                yield schemaToType(items != null ? items : Map.of()) + "[]";
            }
            case "object" -> {
                var additional = map.get("additionalProperties");
                if (Boolean.TRUE.equals(additional)) yield "dict[str, any]";
                if (additional instanceof Map) yield "dict[str, " + schemaToType(additional) + "]";
                if (additional != null) yield "dict[str, any]";
                yield "object";
            }
            case "string" -> {
                var fmt = str(map.get("format"));
                yield fmt.isEmpty() ? "str" : "str(" + fmt + ")";
            }
            case "integer" -> "int";
            case "number" -> "float";
            case "boolean" -> "bool";
            default -> typeStr;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object obj) {
        if (obj instanceof List<?> list) {
            return new ArrayList<>((List<Object>) list);
        }
        return new ArrayList<>();
    }

    private static String str(Object obj) {
        return obj != null ? obj.toString() : "";
    }

    private static String firstNonEmpty(Map<String, Object> map, String... keys) {
        for (var key : keys) {
            var val = map.get(key);
            if (val instanceof String s && !s.isBlank()) return s;
        }
        return "";
    }
}
