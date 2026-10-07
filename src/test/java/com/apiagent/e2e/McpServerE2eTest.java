package com.apiagent.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.HttpURLConnection;
import java.net.URI;

import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class McpServerE2eTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    @Test
    void MCP_서버_빈이_등록된다() {
        var server = applicationContext.getBean(McpSyncServer.class);
        assertThat(server).isNotNull();
    }

    @Test
    void MCP_서버_정보가_올바르다() {
        var server = applicationContext.getBean(McpSyncServer.class);
        var serverInfo = server.getServerInfo();

        assertThat(serverInfo).isNotNull();
        assertThat(serverInfo.name()).isEqualTo("API Agent");
        assertThat(serverInfo.version()).isEqualTo("0.1.0");
    }

    @Test
    void MCP_서버_도구_기능이_활성화된다() {
        var server = applicationContext.getBean(McpSyncServer.class);
        var capabilities = server.getServerCapabilities();

        assertThat(capabilities).isNotNull();
        assertThat(capabilities.tools()).isNotNull();
    }

    @Test
    void SSE_엔드포인트가_연결_가능하다() throws Exception {
        // SSE는 스트리밍이라 TestRestTemplate 사용 불가 — 짧은 타임아웃으로 연결만 확인
        var url = URI.create("http://localhost:" + port + "/sse").toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);

        try {
            int responseCode = conn.getResponseCode();
            assertThat(responseCode).isEqualTo(200);
            assertThat(conn.getContentType()).contains("text/event-stream");
        } finally {
            conn.disconnect();
        }
    }

    @Test
    void 잘못된_MCP_메시지_경로에_에러_반환() {
        var response = restTemplate.postForEntity(
                "/nonexistent-mcp-endpoint",
                "{}",
                String.class
        );

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.OK);
    }
}
