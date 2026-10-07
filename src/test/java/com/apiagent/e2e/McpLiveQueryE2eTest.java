package com.apiagent.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.apiagent.context.RequestContext;
import com.apiagent.context.RequestContextHolder;
import com.apiagent.mcp.McpToolProvider;
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

    @Test
    void 공개_GraphQL_API에_자연어_질의() throws Exception {
        // RequestContext 설정 — ScopedValue.callWhere 블록 내에서 실행
        var ctx = new RequestContext(
                "https://countries.trevorblades.com/graphql",
                "graphql",
                null,   // targetHeaders
                null,   // allowUnsafePaths
                null,   // baseUrl
                false,  // includeResult
                null    // pollPaths
        );

        var result = new String[1];
        ScopedValue.where(RequestContextHolder.SCOPE, ctx).call(() -> {
            result[0] = mcpToolProvider._query("한국(South Korea)의 수도는?");
            return null;
        });

        assertThat(result[0]).isNotNull();
        assertThat(result[0].toLowerCase()).containsAnyOf("seoul", "서울");
    }
}
