package com.apiagent.recipe;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * LLM 기반 레시피 추출기.
 * 성공적인 API 호출 패턴에서 파라미터화된 레시피를 추출.
 */
@Component
@RequiredArgsConstructor
public class RecipeExtractor {

    private static final Logger log = LoggerFactory.getLogger(RecipeExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient.Builder chatClientBuilder;

    private static final String EXTRACTION_PROMPT = """
            You are a recipe extractor. Given a successful API interaction, extract a reusable recipe.

            API Type: %s
            Question: %s
            Steps: %s
            SQL Steps: %s

            Extract a JSON recipe with:
            {
              "tool_name": "short_snake_case_name",
              "params": {"param_name": {"type": "str|int|float|bool", "default": "example_value"}},
              "steps": [<parameterized steps with {{param}} or {"$param": "name"}>],
              "sql_steps": [<parameterized SQL with {{param}}>]
            }

            Rules:
            - Identify variable parts (IDs, names, dates) and make them params
            - Keep constant parts (endpoints, query structure) fixed
            - tool_name should describe the action (e.g., "get_user_orders")
            - Return ONLY valid JSON, no markdown

            If the interaction is too simple (single query, no params), return null.
            """;

    /**
     * 에이전트 실행 기록에서 레시피 추출.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> extract(String apiType, String question,
                                        List<Map<String, Object>> steps,
                                        List<String> sqlSteps) {
        if (steps == null || steps.isEmpty()) return null;

        try {
            var stepsJson = MAPPER.writeValueAsString(steps);
            var sqlJson = MAPPER.writeValueAsString(sqlSteps != null ? sqlSteps : List.of());

            var prompt = EXTRACTION_PROMPT.formatted(apiType, question, stepsJson, sqlJson);

            var chatClient = chatClientBuilder.build();
            var response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (response == null || response.isBlank() || "null".equals(response.trim())) {
                return null;
            }

            // JSON 파싱 (마크다운 코드 블록 제거)
            var cleaned = response.strip();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "");
            }

            var recipe = MAPPER.readValue(cleaned, Map.class);

            // 기본 검증
            if (!recipe.containsKey("steps") || !recipe.containsKey("tool_name")) {
                return null;
            }

            return recipe;

        } catch (Exception e) {
            log.error("레시피 추출 실패", e);
            return null;
        }
    }
}
