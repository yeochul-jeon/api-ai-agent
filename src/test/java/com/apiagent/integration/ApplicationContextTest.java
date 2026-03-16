package com.apiagent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.apiagent.agent.GraphqlAgentService;
import com.apiagent.agent.RestAgentService;
import com.apiagent.config.ApiAgentProperties;
import com.apiagent.mcp.McpToolProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ApplicationContextTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void contextLoads() {
        // Spring Context가 정상적으로 로딩되면 이 테스트는 통과
        assertThat(applicationContext).isNotNull();
    }

    @Test
    void 핵심_빈이_주입된다() {
        assertThat(applicationContext.getBean(McpToolProvider.class)).isNotNull();
        assertThat(applicationContext.getBean(GraphqlAgentService.class)).isNotNull();
        assertThat(applicationContext.getBean(RestAgentService.class)).isNotNull();
        assertThat(applicationContext.getBean(ApiAgentProperties.class)).isNotNull();
    }

    @Test
    void 프로퍼티가_정상_바인딩된다() {
        var props = applicationContext.getBean(ApiAgentProperties.class);

        assertThat(props.mcpName()).isNotBlank();
        assertThat(props.serviceName()).isNotBlank();
        assertThat(props.modelName()).isNotBlank();
        assertThat(props.maxAgentTurns()).isGreaterThan(0);
        assertThat(props.maxResponseChars()).isGreaterThan(0);
    }

    @Test
    void actuator_health_응답() {
        var response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("UP");
    }
}
