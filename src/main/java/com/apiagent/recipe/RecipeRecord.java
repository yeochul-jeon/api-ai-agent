package com.apiagent.recipe;

import java.util.Map;
import java.util.Set;

/**
 * 레시피 데이터 모델.
 */
public record RecipeRecord(
        String recipeId,
        String apiId,
        String schemaHash,
        String question,
        String questionSig, // 정규화된 질문
        Set<String> questionTokens,
        Map<String, Object> recipe,
        String toolName,
        double createdAt,
        double lastUsedAt
) {

    /**
     * lastUsedAt만 갱신한 새 인스턴스 생성.
     */
    public RecipeRecord withLastUsedAt(double t) {
        return new RecipeRecord(recipeId, apiId, schemaHash, question,
                questionSig, questionTokens, recipe, toolName, createdAt, t);
    }
}
