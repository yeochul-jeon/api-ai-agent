package com.apiagent.recipe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.apache.commons.text.similarity.LevenshteinDistance;

/**
 * 스레드 안전한 LRU 레시피 캐시 (fuzzy 매칭 포함).
 */
public class RecipeStore {

    private final int maxSize;
    private final Object lock = new Object();
    private final Map<String, RecipeRecord> records = new HashMap<>();
    private final Map<String, Set<String>> byKey = new HashMap<>(); // "apiId|schemaHash" -> recipeIds
    private final LinkedHashMap<String, Void> lru = new LinkedHashMap<>();

    private static final Pattern WORD_PATTERN = Pattern.compile("[a-z0-9]+");
    private static final Pattern WS_PATTERN = Pattern.compile("\\s+");
    private static final LevenshteinDistance LEVENSHTEIN = LevenshteinDistance.getDefaultInstance();
    private static final JaroWinklerSimilarity JARO_WINKLER = new JaroWinklerSimilarity();

    public RecipeStore(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
    }

    /**
     * 레시피 저장.
     */
    public String saveRecipe(String apiId, String schemaHash, String question,
                             Map<String, Object> recipe, String toolName) {
        var recipeId = "r_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var now = System.currentTimeMillis() / 1000.0;

        var record = new RecipeRecord(
                recipeId, apiId, schemaHash, question,
                normalizeQuestion(question), tokens(question),
                new HashMap<>(recipe), toolName,
                now, now
        );

        synchronized (lock) {
            records.put(recipeId, record);
            byKey.computeIfAbsent(key(apiId, schemaHash), k -> new HashSet<>()).add(recipeId);
            touch(recipeId);
            evictIfNeeded();
        }

        return recipeId;
    }

    /**
     * 레시피 조회.
     */
    public Map<String, Object> getRecipe(String recipeId) {
        synchronized (lock) {
            var rec = records.get(recipeId);
            if (rec == null) return null;
            var updated = rec.withLastUsedAt(System.currentTimeMillis() / 1000.0);
            records.put(recipeId, updated);
            touch(recipeId);
            return new HashMap<>(updated.recipe());
        }
    }

    /**
     * 유사 레시피 제안 (fuzzy 매칭).
     */
    public List<Map<String, Object>> suggestRecipes(String apiId, String schemaHash,
                                                     String question, int k) {
        var qSig = normalizeQuestion(question);
        List<RecipeRecord> recs;

        synchronized (lock) {
            var ids = byKey.getOrDefault(key(apiId, schemaHash), Set.of());
            recs = ids.stream()
                    .map(records::get)
                    .filter(r -> r != null)
                    .toList();
        }

        record Scored(double score, RecipeRecord rec) {}
        var scored = new ArrayList<Scored>();
        for (var r : recs) {
            var score = similarity(qSig, r.questionSig());
            if (score > 0) {
                scored.add(new Scored(score, r));
            }
        }

        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(s -> s.rec().lastUsedAt(), Comparator.reverseOrder()));

        var result = new ArrayList<Map<String, Object>>();
        for (var s : scored.subList(0, Math.min(k, scored.size()))) {
            result.add(Map.of(
                    "recipe_id", s.rec().recipeId(),
                    "score", Math.round(s.score() * 10000.0) / 10000.0,
                    "question", s.rec().question(),
                    "tool_name", s.rec().toolName()
            ));
        }

        return result;
    }

    /**
     * 특정 API/스키마의 레시피 목록.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listRecipes(String apiId, String schemaHash) {
        List<RecipeRecord> recs;
        synchronized (lock) {
            var ids = byKey.getOrDefault(key(apiId, schemaHash), Set.of());
            recs = ids.stream()
                    .map(records::get)
                    .filter(r -> r != null)
                    .sorted(Comparator.comparingDouble(RecipeRecord::lastUsedAt).reversed())
                    .toList();
        }

        var result = new ArrayList<Map<String, Object>>();
        for (var r : recs) {
            result.add(Map.of(
                    "recipe_id", r.recipeId(),
                    "question", r.question(),
                    "tool_name", r.toolName(),
                    "params", r.recipe().getOrDefault("params", Map.of()),
                    "steps", r.recipe().getOrDefault("steps", List.of()),
                    "sql_steps", r.recipe().getOrDefault("sql_steps", List.of())
            ));
        }
        return result;
    }

    // === 유사도 계산 ===

    double similarity(String query, String signature) {
        if (query == null || signature == null || query.isBlank() || signature.isBlank()) return 0.0;
        if (query.equals(signature)) return 1.0;

        var qTokens = tokens(query);
        var sTokens = tokens(signature);
        if (qTokens.isEmpty() || sTokens.isEmpty()) return 0.0;

        var qText = String.join(" ", qTokens.stream().sorted().toList());
        var sText = String.join(" ", sTokens.stream().sorted().toList());

        int maxLen = Math.max(qText.length(), sText.length());
        double base = maxLen == 0 ? 100.0
                : (1.0 - (double) LEVENSHTEIN.apply(qText, sText) / maxLen) * 100.0;
        double extra = JARO_WINKLER.apply(qText, sText) * 100.0;

        var intersection = new HashSet<>(qTokens);
        intersection.retainAll(sTokens);
        double overlap = (double) intersection.size() / Math.max(qTokens.size(), 1);

        var intersection2 = new HashSet<>(sTokens);
        intersection2.retainAll(qTokens);
        double coverage = (double) intersection2.size() / Math.max(sTokens.size(), 1);
        double tokenBalance = Math.min(overlap, coverage) * 100.0;

        return (0.55 * base + 0.25 * extra + 0.20 * tokenBalance) / 100.0;
    }

    // === 내부 헬퍼 ===

    static String normalizeQuestion(String q) {
        return WS_PATTERN.matcher((q != null ? q : "").trim().toLowerCase()).replaceAll(" ");
    }

    static Set<String> tokens(String q) {
        var normalized = normalizeQuestion(q);
        var matcher = WORD_PATTERN.matcher(normalized);
        var result = new HashSet<String>();
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    public static String sha256Hex(String text) {
        try {
            // JSON 정규화 시도
            var normalized = text;
            try {
                var mapper = new tools.jackson.databind.ObjectMapper();
                var parsed = mapper.readTree(text);
                normalized = mapper.writer()
                        .withDefaultPrettyPrinter()
                        .writeValueAsString(parsed);
            } catch (Exception _) {
            }

            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String key(String apiId, String schemaHash) {
        return apiId + "|" + schemaHash;
    }

    private void touch(String recipeId) {
        lru.remove(recipeId);
        lru.put(recipeId, null);
    }

    private void evictIfNeeded() {
        while (records.size() > maxSize && !lru.isEmpty()) {
            var oldest = lru.keySet().iterator().next();
            delete(oldest);
        }
    }

    private void delete(String recipeId) {
        var rec = records.remove(recipeId);
        lru.remove(recipeId);
        if (rec != null) {
            var k = key(rec.apiId(), rec.schemaHash());
            var ids = byKey.get(k);
            if (ids != null) {
                ids.remove(recipeId);
                if (ids.isEmpty()) byKey.remove(k);
            }
        }
    }
}
