package com.apiagent.recipe;

import java.util.Map;
import java.util.Set;

/**
 * 레시피 데이터 모델.
 */
public class RecipeRecord {

    private final String recipeId;
    private final String apiId;
    private final String schemaHash;
    private final String question;
    private final String questionSig; // 정규화된 질문
    private final Set<String> questionTokens;
    private final Map<String, Object> recipe;
    private final String toolName;
    private final double createdAt;
    private volatile double lastUsedAt;

    public RecipeRecord(String recipeId, String apiId, String schemaHash, String question,
                        String questionSig, Set<String> questionTokens,
                        Map<String, Object> recipe, String toolName,
                        double createdAt, double lastUsedAt) {
        this.recipeId = recipeId;
        this.apiId = apiId;
        this.schemaHash = schemaHash;
        this.question = question;
        this.questionSig = questionSig;
        this.questionTokens = questionTokens;
        this.recipe = recipe;
        this.toolName = toolName;
        this.createdAt = createdAt;
        this.lastUsedAt = lastUsedAt;
    }

    public String recipeId() { return recipeId; }
    public String apiId() { return apiId; }
    public String schemaHash() { return schemaHash; }
    public String question() { return question; }
    public String questionSig() { return questionSig; }
    public Set<String> questionTokens() { return questionTokens; }
    public Map<String, Object> recipe() { return recipe; }
    public String toolName() { return toolName; }
    public double createdAt() { return createdAt; }
    public double lastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(double t) { this.lastUsedAt = t; }
}
