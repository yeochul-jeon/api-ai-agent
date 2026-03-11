package com.apiagent.client.rest;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * REST API HTTP 클라이언트 (unsafe 메서드 차단, glob 허용 목록).
 */
@Component
public class RestApiClient {

    private static final Logger log = LoggerFactory.getLogger(RestApiClient.class);
    private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    private final RestClient restClient;

    public RestApiClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    /**
     * REST API 요청 실행.
     *
     * @param method           HTTP 메서드
     * @param path             API 경로 (e.g., /users/{id})
     * @param pathParams       경로 파라미터 (e.g., {"id": "123"})
     * @param queryParams      쿼리 파라미터 (e.g., {"limit": 10})
     * @param body             요청 본문 (POST/PUT/PATCH 용)
     * @param baseUrl          API 기본 URL
     * @param headers          추가 헤더
     * @param allowUnsafe      모든 unsafe 메서드 허용 여부
     * @param allowUnsafePaths unsafe 메서드 허용 경로 glob 패턴
     * @return 결과 맵 (success/data 또는 error)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> executeRequest(String method, String path,
                                               Map<String, Object> pathParams,
                                               Map<String, Object> queryParams,
                                               Map<String, Object> body,
                                               String baseUrl,
                                               Map<String, String> headers,
                                               boolean allowUnsafe,
                                               java.util.List<String> allowUnsafePaths) {
        var upperMethod = method.toUpperCase();

        // Unsafe 메서드 차단
        if (UNSAFE_METHODS.contains(upperMethod) && !allowUnsafe) {
            if (allowUnsafePaths == null || !isPathAllowed(path, allowUnsafePaths)) {
                return Map.of("success", false,
                        "error", upperMethod + " method not allowed (read-only mode). Use X-Allow-Unsafe-Paths header.");
            }
        }

        if (baseUrl == null || baseUrl.isBlank()) {
            return Map.of("success", false, "error", "No base URL provided");
        }

        // URL 빌드
        var resolvedPath = resolvePath(path, pathParams);
        var url = buildUrl(baseUrl, resolvedPath, queryParams);

        log.info("REST request: method={} url={} header_keys={}",
                upperMethod, url, headers != null ? headers.keySet().stream().sorted().collect(Collectors.toList()) : "[]");

        try {
            var spec = switch (upperMethod) {
                case "GET" -> restClient.get().uri(url);
                case "DELETE" -> restClient.delete().uri(url);
                default -> {
                    var bodySpec = restClient.method(org.springframework.http.HttpMethod.valueOf(upperMethod))
                            .uri(url)
                            .contentType(MediaType.APPLICATION_JSON);
                    if (body != null) {
                        yield bodySpec.body(body);
                    }
                    yield bodySpec;
                }
            };

            if (headers != null) {
                headers.forEach(spec::header);
            }
            spec.header("Accept", MediaType.APPLICATION_JSON_VALUE);

            var responseBody = spec.retrieve().body(Object.class);
            return Map.of("success", true, "data", responseBody != null ? responseBody : Map.of());

        } catch (HttpClientErrorException e) {
            String errorBody;
            try {
                errorBody = e.getResponseBodyAsString().substring(0, Math.min(500, e.getResponseBodyAsString().length()));
            } catch (Exception ignored) {
                errorBody = e.getStatusText();
            }
            return Map.of("success", false,
                    "error", "HTTP " + e.getStatusCode().value() + ": " + errorBody);
        } catch (Exception e) {
            log.error("REST API error", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    private String resolvePath(String path, Map<String, Object> pathParams) {
        if (pathParams == null || pathParams.isEmpty()) {
            return path;
        }
        var result = path;
        for (var entry : pathParams.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }

    private String buildUrl(String baseUrl, String path, Map<String, Object> queryParams) {
        var base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        var cleanPath = path.startsWith("/") ? path.substring(1) : path;
        var builder = UriComponentsBuilder.fromUriString(base + cleanPath);

        if (queryParams != null) {
            queryParams.forEach((k, v) -> {
                if (v != null) {
                    builder.queryParam(k, v);
                }
            });
        }

        return builder.build().toUriString();
    }

    /**
     * 경로가 glob 패턴 허용 목록에 매칭되는지 확인.
     */
    static boolean isPathAllowed(String path, java.util.List<String> patterns) {
        if (patterns == null) return false;
        for (var pattern : patterns) {
            if (globMatch(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 간단한 glob 매칭 (fnmatch 스타일).
     */
    static boolean globMatch(String pattern, String text) {
        var regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return text.matches(regex);
    }
}
