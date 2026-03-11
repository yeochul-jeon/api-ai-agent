package com.apiagent.client.graphql;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * GraphQL HTTP 클라이언트 (읽기 전용, mutation 차단).
 */
@Component
public class GraphqlClient {

    private static final Logger log = LoggerFactory.getLogger(GraphqlClient.class);
    private static final Pattern MUTATION_PATTERN = Pattern.compile("^\\s*mutation\\b",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private final RestClient restClient;

    public GraphqlClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    /**
     * GraphQL 쿼리 실행. Mutation은 차단됨 (읽기 전용 모드).
     *
     * @param query    GraphQL 쿼리 문자열
     * @param variables 쿼리 변수 (nullable)
     * @param endpoint GraphQL 엔드포인트 URL
     * @param headers  추가 헤더 (e.g., Authorization)
     * @return 결과 맵 (success/data 또는 error)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> executeQuery(String query, Map<String, Object> variables,
                                            String endpoint, Map<String, String> headers) {
        if (endpoint == null || endpoint.isBlank()) {
            return Map.of("success", false, "error", "No endpoint provided");
        }

        if (MUTATION_PATTERN.matcher(query).find()) {
            return Map.of("success", false, "error", "Mutations are not allowed (read-only mode)");
        }

        var payload = new HashMap<String, Object>();
        payload.put("query", query);
        if (variables != null && !variables.isEmpty()) {
            payload.put("variables", variables);
        }

        try {
            var spec = restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON);

            if (headers != null) {
                headers.forEach(spec::header);
            }

            var result = spec.body(payload)
                    .retrieve()
                    .body(Map.class);

            if (result == null) {
                return Map.of("success", false, "error", "Empty response");
            }

            if (result.containsKey("errors")) {
                return Map.of("success", false, "error", result.get("errors"));
            }

            return Map.of("success", true, "data", result.getOrDefault("data", Map.of()));

        } catch (HttpClientErrorException e) {
            return Map.of("success", false, "error", "HTTP " + e.getStatusCode().value());
        } catch (Exception e) {
            log.error("GraphQL error", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }
}
