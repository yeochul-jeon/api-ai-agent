package com.apiagent.context;

import java.util.List;
import java.util.Map;

/**
 * 요청별 컨텍스트 (HTTP 헤더에서 추출).
 *
 * @param targetUrl        X-Target-URL: GraphQL 엔드포인트 또는 OpenAPI 스펙 URL
 * @param apiType          X-API-Type: "graphql" 또는 "rest"
 * @param targetHeaders    X-Target-Headers: 파싱된 JSON 헤더
 * @param allowUnsafePaths X-Allow-Unsafe-Paths: POST/PUT/DELETE 허용 glob 패턴
 * @param baseUrl          X-Base-URL: REST API base URL 오버라이드 (nullable)
 * @param includeResult    X-Include-Result: 전체 결과 포함 여부
 * @param pollPaths        X-Poll-Paths: 폴링이 필요한 경로들
 */
public record RequestContext(
        String targetUrl,
        String apiType,
        Map<String, String> targetHeaders,
        List<String> allowUnsafePaths,
        String baseUrl,
        boolean includeResult,
        List<String> pollPaths
) {
}
