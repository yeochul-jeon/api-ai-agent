# 11. API 스펙 획득 방식 분석

> 작성일: 2026-03-24
> 목적: 현재 스키마 로딩 방식 정리 및 대안 검토

---

## 1. 현재 방식

OpenAPI/GraphQL 스펙을 URL에서 가져온 뒤 **커스텀 DSL 텍스트**로 변환하여 LLM에게 전달합니다.
원본 Python 프로젝트(api-agent)와 동일한 설계입니다.

### 1.1 REST API (OpenAPI)

**흐름:**
```
HTTP GET (스펙 URL) → JSON/YAML 파싱 → 커스텀 DSL 변환 → LLM 프롬프트에 포함
```

**변환 결과 예시:**
```
<endpoints>
GET /users(id: str!) -> User  # Get user by ID
POST /users(body: User!) -> User  # Create user

<schemas>
User { id: str!, name: str!, email: str! }

<auth>
Bearer: HTTP bearer
```

**설계 포인트:**
- 선택적(optional) 파라미터는 제외 → LLM이 불필요한 값을 생성하는 것을 방지
- 스키마 크기 초과 시 절삭 후 `[SCHEMA TRUNCATED - use search_schema() to explore]` 안내
- `search_schema` 도구로 원본 JSON에 대한 부분 검색 지원

**관련 파일:**
- `src/main/java/com/apiagent/client/rest/OpenApiSchemaLoader.java`

### 1.2 GraphQL (Introspection)

**흐름:**
```
Introspection Query 실행 → __schema 응답 수신 → SDL 유사 텍스트 변환 → LLM 프롬프트에 포함
```

**변환 결과 예시:**
```
<queries>
users(limit: Int!) -> [User!]!  # List users

<types>
User { id: ID!, name: String!, email: String }

<enums>
Role: ADMIN | USER | GUEST
```

**관련 파일:**
- `src/main/java/com/apiagent/agent/GraphqlAgentService.java`

### 1.3 에이전트 전달 흐름

```
McpToolProvider._query(question)
  ↓ api_type 분기
RestAgentService / GraphqlAgentService
  ↓ 스키마 로딩 + DSL 변환
AgentToolFactory.createXxxTools()
  ↓ 도구 콜백 생성 (rest_call/graphql_query, sql_query, search_schema)
AgentRunner.run(systemPrompt, schemaContext + question, tools)
  ↓ 멀티턴 루프 (최대 30턴)
최종 결과 반환
```

---

## 2. 대안 검토

### 2.1 OpenAPI 스펙 원본 직접 전달

DSL 변환 없이 JSON/YAML 원본을 LLM에게 그대로 전달하는 방식.

| 항목 | 평가 |
|------|------|
| 장점 | 변환 로직 불필요, 정보 손실 없음 |
| 단점 | 토큰 소비 3~5배 증가, 선택적 필드 포함으로 LLM이 불필요한 값 생성 위험 |
| 적합 대상 | 소규모 API (엔드포인트 10개 이하) |

### 2.2 Spring AI Tool 자동 생성 (Function Calling)

OpenAPI 스펙의 각 엔드포인트를 `FunctionToolCallback`으로 자동 등록하여 LLM이 도구로 직접 호출하는 방식.

| 항목 | 평가 |
|------|------|
| 장점 | 프레임워크 통합, 타입 안전, 스키마 전달 불필요 |
| 단점 | 엔드포인트 수 많으면 도구 폭증, Spring AI M3 단계에서 API 불안정 |
| 적합 대상 | 엔드포인트가 적고 명확한 내부 API |

### 2.3 RAG 기반 스키마 검색

전체 스키마를 벡터 DB에 임베딩하고, 질문과 관련된 엔드포인트/타입만 검색하여 전달하는 방식.

| 항목 | 평가 |
|------|------|
| 장점 | 대규모 API(수백 개 엔드포인트)에서 토큰 절약, 관련 컨텍스트만 전달하여 정확도 향상 |
| 단점 | 벡터 DB 인프라 필요, 임베딩 비용, 소규모 API에서는 오버엔지니어링 |
| 적합 대상 | 엔드포인트 50개 이상의 대규모 API |

현재 `search_schema` 도구가 원본 JSON에 대한 텍스트 검색을 지원하고 있어, 이를 벡터 검색으로 강화하는 것이 자연스러운 확장 경로.

### 2.4 API 문서 URL 크롤링

Swagger UI, Redoc 등 API 문서 페이지를 크롤링하여 스키마를 추출하는 방식.

| 항목 | 평가 |
|------|------|
| 장점 | OpenAPI 스펙이 없는 API에도 대응 가능 |
| 단점 | 비정형 데이터 파싱 불안정, 레이아웃 변경 시 깨짐, 유지보수 어려움 |
| 적합 대상 | 스펙 미제공 외부 API (최후 수단) |

### 2.5 LLM 기반 스키마 요약 (2단계)

1단계에서 LLM이 전체 스키마를 읽고 요약본을 생성한 뒤, 2단계에서 요약본 + 질문으로 실제 작업을 수행하는 방식.

| 항목 | 평가 |
|------|------|
| 장점 | 토큰 절약하면서 맥락 유지, 구현 단순 |
| 단점 | 추가 LLM 호출 비용 및 지연 시간 증가, 요약 과정에서 정보 손실 가능 |
| 적합 대상 | 스키마가 크지만 벡터 DB 도입이 어려운 환경 |

---

## 3. 비교 요약

| 방식 | 토큰 효율 | 정확도 | 구현 복잡도 | 인프라 요구 |
|------|-----------|--------|-------------|-------------|
| **현재 (커스텀 DSL)** | 좋음 | 좋음 | 중간 | 없음 |
| 원본 직접 전달 | 나쁨 | 보통 | 낮음 | 없음 |
| Tool 자동 생성 | 좋음 | 좋음 | 높음 | 없음 |
| **RAG 기반 검색** | **매우 좋음** | **매우 좋음** | 높음 | 벡터 DB |
| 문서 크롤링 | 보통 | 나쁨 | 높음 | 없음 |
| LLM 요약 (2단계) | 좋음 | 보통 | 낮음 | 없음 |

---

## 4. 권장 방향

현재 방식(커스텀 DSL + search_schema 도구)은 **토큰 효율성과 정확도의 균형**이 잘 잡혀 있습니다.

향후 API 규모가 커지는 경우 다음 순서로 확장을 검토할 수 있습니다:

1. **단기** — 현재 방식 유지. `search_schema` 도구의 검색 품질 개선 (정규식 → 키워드 가중치 등)
2. **중기** — RAG 기반 스키마 검색 도입. Spring AI의 VectorStore 통합 활용
3. **장기** — Spring AI GA 이후 Tool 자동 생성 방식 검토
