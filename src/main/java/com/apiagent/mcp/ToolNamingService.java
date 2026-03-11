package com.apiagent.mcp;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

/**
 * 동적 도구 이름 변환 서비스.
 * URL에서 의미있는 접두사를 추출하여 도구명에 사용.
 */
@Service
public class ToolNamingService {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-zA-Z0-9_]");
    private static final Pattern WHITESPACE_DASH = Pattern.compile("[\\s\\-]+");
    private static final int MAX_PREFIX_LEN = 32;

    private static final Set<String> SKIP_PARTS = Set.of(
            "com", "io", "is", "net", "org",
            "privatecloud", "qa", "dev", "internal", "api"
    );

    /**
     * 문자열을 snake_case로 변환.
     */
    public String toSnakeCase(String name) {
        if (name == null || name.isBlank()) return "";
        var result = WHITESPACE_DASH.matcher(name).replaceAll("_");
        result = NON_ALNUM.matcher(result).replaceAll("");
        return result.toLowerCase().replaceAll("^_+|_+$", "");
    }

    /**
     * URL에서 전체 호스트명 추출.
     */
    public String getFullHostname(String url) {
        if (url == null || url.isBlank()) return "api";
        try {
            var host = URI.create(url).getHost();
            return host != null ? host : "api";
        } catch (Exception e) {
            return "api";
        }
    }

    /**
     * URL에서 의미있는 접두사 추출 (≤32자).
     * 예: flights-api-qa.internal.example.com → flights_api_example
     */
    public String getToolNamePrefix(String url) {
        if (url == null || url.isBlank()) return "api";

        try {
            var host = URI.create(url).getHost();
            if (host == null || host.isBlank()) return "api";

            var parts = host.split("\\.");
            var meaningful = Arrays.stream(parts)
                    .filter(p -> !p.isBlank() && !SKIP_PARTS.contains(p.toLowerCase()))
                    .map(this::toSnakeCase)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.joining("_"));

            if (meaningful.length() > MAX_PREFIX_LEN) {
                meaningful = meaningful.substring(0, MAX_PREFIX_LEN);
            }

            return meaningful.isEmpty() ? "api" : meaningful;
        } catch (Exception e) {
            return "api";
        }
    }

    /**
     * 헤더에서 API 이름 접두사 추출.
     * 우선순위: X-API-Name 헤더 > X-Target-URL 파싱
     */
    public String extractApiName(String apiName, String targetUrl) {
        if (apiName != null && !apiName.isBlank()) {
            var result = toSnakeCase(apiName);
            return result.length() > MAX_PREFIX_LEN ? result.substring(0, MAX_PREFIX_LEN) : result;
        }
        return getToolNamePrefix(targetUrl);
    }

    /**
     * API 컨텍스트를 도구 설명에 주입.
     */
    public String injectApiContext(String description, String hostname, String apiType) {
        var label = "graphql".equals(apiType) ? "GraphQL" : "REST";
        return "[%s %s API] %s".formatted(hostname, label, description);
    }
}
