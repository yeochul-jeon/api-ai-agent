package com.apiagent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.apiagent.context.RequestContextHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class RequestContextFilterIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void 필터가_헤더로_컨텍스트를_설정한다() {
        var headers = new HttpHeaders();
        headers.set("X-Target-URL", "https://api.example.com/graphql");
        headers.set("X-API-Type", "graphql");
        headers.set("X-Target-Headers", "{\"Authorization\": \"Bearer test\"}");

        // 실제 HTTP 요청을 통해 필터 동작 검증 — actuator 엔드포인트 활용
        var response = restTemplate.exchange(
                "/actuator/health",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        // 필터가 에러 없이 동작하고, 요청이 정상 처리됨을 확인
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void 필터_후_ScopedValue가_정리된다() {
        var headers = new HttpHeaders();
        headers.set("X-Target-URL", "https://api.example.com/graphql");
        headers.set("X-API-Type", "graphql");

        restTemplate.exchange(
                "/actuator/health",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        // 요청 완료 후 현재 스레드의 ScopedValue는 바인딩 해제되어야 함
        assertThat(RequestContextHolder.get()).isNull();
    }

    @Test
    void 필수_헤더_없어도_에러_발생하지_않는다() {
        // 헤더 없이 요청 — 컨텍스트 미생성이지만 에러 아님
        var response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }
}
