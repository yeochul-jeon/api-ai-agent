package com.apiagent.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.apiagent.context.RequestContext;
import com.apiagent.context.RequestContextHolder;
import com.apiagent.mcp.McpToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;

/**
 * 실제 OpenAI API를 호출하는 라이브 E2E 테스트.
 * 공개 GraphQL API(Countries API)를 대상으로 자연어 질의를 수행한다.
 *
 * <p>실행: ./gradlew test -PincludeLive=true</p>
 */
@Tag("live")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class McpLiveQueryE2eTest {

    @Autowired
    private McpToolProvider mcpToolProvider;

    @AfterEach
    void cleanup() {
        RequestContextHolder.clear();
    }

    @Test
    void 공개_GraphQL_API에_자연어_질의() {
        // RequestContext 설정 — Countries GraphQL API
        var ctx = new RequestContext(
                "https://countries.trevorblades.com/graphql",
                "graphql",
                null,   // targetHeaders
                null,   // allowUnsafePaths
                null,   // baseUrl
                false,  // includeResult
                null    // pollPaths
        );
        RequestContextHolder.set(ctx);

        // McpToolProvider를 직접 호출하여 자연어 질의 수행
        var result = mcpToolProvider._query("한국(South Korea)의 수도는?");

        assertThat(result).isNotNull();
        assertThat(result.toLowerCase()).containsAnyOf("seoul", "서울");
    }
}
