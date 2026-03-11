# API AI Agent

자연어 질의를 GraphQL/REST API 호출로 변환하는 **MCP(Model Context Protocol) 서버**입니다.
Python FastMCP 기반의 [api-agent](../api-agent)를 Spring Boot로 포팅한 프로젝트입니다.

## 기술 스택

| 분류 | 기술 |
|------|------|
| Runtime | Java 21 (Corretto), Virtual Threads |
| Framework | Spring Boot 3.4.4, Spring AI 1.0.3 |
| MCP | Spring AI MCP Server (WebMVC, Streamable HTTP) |
| LLM | OpenAI ChatClient (gpt-4o 기본) |
| SQL 후처리 | DuckDB JDBC 1.2.1 |
| 퍼지 매칭 | FuzzyWuzzy 1.4.0 |
| Observability | Micrometer + OpenTelemetry |
| Build | Gradle 8.12 (Kotlin DSL) |

## 주요 기능

- **MCP 서버** — Streamable HTTP 전송 방식으로 MCP 도구를 제공
- **멀티턴 에이전트** — LLM 기반 최대 30턴 대화 루프 (AgentRunner)
- **GraphQL 지원** — 스키마 인트로스펙션, 쿼리 실행 (mutation 차단)
- **REST 지원** — OpenAPI 스키마 로딩, API 호출 (unsafe 메서드 차단)
- **DuckDB SQL 후처리** — API 응답을 DuckDB에 로드하여 SQL로 가공
- **레시피 캐싱** — 반복 질의를 fuzzy 매칭으로 캐싱/재사용
- **동적 도구 네이밍** — 요청 컨텍스트 기반 MCP 도구 이름 자동 생성

## 패키지 구조

```
src/main/java/com/apiagent/
├── config/          # 설정 (Properties, CORS, RestClient, MCP, GlobalExceptionHandler)
├── context/         # 요청 컨텍스트 (RequestContext record, Filter, ThreadLocal holder)
├── client/
│   ├── graphql/     # GraphQL 클라이언트 (mutation 차단)
│   └── rest/        # REST 클라이언트 + OpenAPI 스키마 로더
├── executor/        # DuckDB SQL 실행기
├── agent/           # 에이전트 시스템 (AgentRunner, GraphQL/REST 서비스, 도구)
├── mcp/             # MCP 도구 제공 (McpToolProvider, ToolNamingService)
├── recipe/          # 레시피 캐싱 (Store, Extractor, Runner, TemplateRenderer)
├── util/            # CSV 변환, 응답 절삭
└── tracing/         # OpenTelemetry 설정
```

## 사전 요구사항

- Java 21+
- Gradle 8.x (또는 포함된 Gradle Wrapper 사용)
- OpenAI API 키

## 빌드 & 실행

```bash
# 빌드
./gradlew build

# 실행
./gradlew bootRun

# JAR 직접 실행
java --enable-preview -jar build/libs/api-ai-agent-0.1.0.jar
```

### Docker

```bash
# 빌드 후 Docker 이미지 생성
./gradlew build
docker compose up --build

# 또는 직접
docker build -t api-ai-agent .
docker run -p 3000:3000 \
  -e OPENAI_API_KEY=sk-... \
  api-ai-agent
```

## 환경 변수

| 환경 변수 | 설명 | 기본값 |
|-----------|------|--------|
| `OPENAI_API_KEY` | OpenAI API 키 (필수) | — |
| `OPENAI_BASE_URL` | OpenAI API 베이스 URL | `https://api.openai.com/v1` |
| `API_AGENT_PORT` | 서버 포트 | `3000` |
| `API_AGENT_MCP_NAME` | MCP 서버 이름 | `API Agent` |
| `API_AGENT_MODEL_NAME` | 사용할 LLM 모델 | `gpt-4o` |
| `API_AGENT_REASONING_EFFORT` | 추론 노력 수준 | — |
| `API_AGENT_MAX_AGENT_TURNS` | 최대 에이전트 턴 수 | `30` |
| `API_AGENT_MAX_RESPONSE_CHARS` | 최대 응답 문자 수 | `50000` |
| `API_AGENT_MAX_SCHEMA_CHARS` | 최대 스키마 문자 수 | `32000` |
| `API_AGENT_MAX_PREVIEW_ROWS` | 미리보기 최대 행 수 | `10` |
| `API_AGENT_MAX_TOOL_RESPONSE_CHARS` | 도구 응답 최대 문자 수 | `32000` |
| `API_AGENT_MAX_POLLS` | 최대 폴링 횟수 | `20` |
| `API_AGENT_DEFAULT_POLL_DELAY_MS` | 폴링 지연(ms) | `3000` |
| `API_AGENT_DEBUG` | 디버그 모드 | `false` |
| `API_AGENT_CORS_ALLOWED_ORIGINS` | CORS 허용 오리진 | `*` |
| `API_AGENT_ENABLE_RECIPES` | 레시피 캐싱 활성화 | `true` |
| `API_AGENT_RECIPE_CACHE_SIZE` | 레시피 캐시 크기 | `64` |
| `API_AGENT_LOG_LEVEL` | 로그 레벨 | `INFO` |

## MCP 클라이언트 연결

### Claude Desktop 설정 예시

`claude_desktop_config.json`에 추가:

```json
{
  "mcpServers": {
    "api-agent": {
      "url": "http://localhost:3000/mcp"
    }
  }
}
```

### 요청 헤더

MCP 요청 시 다음 HTTP 헤더를 통해 대상 API를 지정합니다:

| 헤더 | 필수 | 설명 |
|------|------|------|
| `X-Target-URL` | O | 대상 API 엔드포인트 URL |
| `X-API-Type` | O | API 유형 (`graphql` 또는 `rest`) |
| `X-Target-Headers` | | 대상 API로 전달할 헤더 (JSON) |
| `X-Allow-Unsafe-Paths` | | REST unsafe 메서드 허용 경로 목록 (JSON 배열) |
| `X-Base-URL` | | REST API 베이스 URL |
| `X-Include-Result` | | 결과 포함 여부 (`true`/`false`) |
| `X-Poll-Paths` | | 폴링 대상 경로 목록 (JSON 배열) |

## 테스트

```bash
./gradlew test
```

테스트 파일 11개, 총 52개 테스트 케이스가 포함되어 있습니다.

## 라이선스

Private
