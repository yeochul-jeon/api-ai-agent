package com.apiagent.recipe;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 레시피 템플릿 렌더링.
 * {{param}} 텍스트 치환 및 {"$param": "name"} 참조 치환.
 */
public final class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}\\}");

    private TemplateRenderer() {}

    /**
     * {{param}} 플레이스홀더를 파라미터 값으로 치환.
     */
    public static String renderText(String template, Map<String, Object> params) {
        var matcher = PLACEHOLDER.matcher(template);
        var sb = new StringBuilder();
        while (matcher.find()) {
            var name = matcher.group(1);
            if (!params.containsKey(name)) {
                throw new IllegalArgumentException("missing param: " + name);
            }
            matcher.appendReplacement(sb, asText(params.get(name)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * {"$param": "name"} 노드를 재귀적으로 파라미터 값으로 치환.
     */
    @SuppressWarnings("unchecked")
    public static Object renderParamRefs(Object obj, Map<String, Object> params) {
        if (obj instanceof Map<?, ?> map) {
            if (map.size() == 1 && map.containsKey("$param")) {
                var pname = map.get("$param").toString();
                if (!params.containsKey(pname)) {
                    throw new IllegalArgumentException("missing param: " + pname);
                }
                return params.get(pname);
            }
            var result = new java.util.HashMap<String, Object>();
            for (var entry : ((Map<String, Object>) map).entrySet()) {
                result.put(entry.getKey(), renderParamRefs(entry.getValue(), params));
            }
            return result;
        }
        if (obj instanceof List<?> list) {
            return list.stream().map(v -> renderParamRefs(v, params)).toList();
        }
        return obj;
    }

    private static String asText(Object value) {
        if (value instanceof Boolean b) return b ? "true" : "false";
        if (value == null) return "null";
        return value.toString();
    }
}
