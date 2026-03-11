package com.apiagent.recipe;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 레시피 도구명 정규화 및 중복 방지.
 */
public final class RecipeNaming {

    private static final Pattern NON_WORD = Pattern.compile("[^\\w\\s]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern VALID_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");

    private RecipeNaming() {}

    /**
     * 도구명을 안전한 slug로 정규화.
     */
    public static String sanitize(String name) {
        if (name == null || name.isBlank()) return "recipe";
        var slug = NON_WORD.matcher(name.toLowerCase()).replaceAll("");
        slug = WHITESPACE.matcher(slug).replaceAll("_").replaceAll("^_+|_+$", "");
        return slug.isEmpty() ? "recipe" : slug;
    }

    /**
     * 질문을 도구명으로 변환 (최대 40자).
     */
    public static String fromQuestion(String question) {
        var name = NON_WORD.matcher(question.toLowerCase()).replaceAll("");
        name = WHITESPACE.matcher(name).replaceAll("_");
        if (name.length() > 40) name = name.substring(0, 40);
        name = name.replaceAll("^_+|_+$", "");
        if (!name.isEmpty() && Character.isDigit(name.charAt(0))) {
            name = "r_" + name;
        }
        return name.isEmpty() ? "recipe" : name;
    }

    /**
     * 중복 방지를 위한 도구명 고유화.
     */
    public static String deduplicate(String baseName, Set<String> seenNames, int maxLen) {
        var base = NON_WORD.matcher(baseName).replaceAll("");
        if (base.length() > maxLen) base = base.substring(0, maxLen);
        if (base.isEmpty() || !VALID_NAME.matcher(base).matches()) {
            base = "recipe";
        }

        if (!seenNames.contains(base)) {
            seenNames.add(base);
            return base;
        }

        int counter = 2;
        while (true) {
            var suffix = "_" + counter;
            var trimmed = base.substring(0, Math.min(base.length(), maxLen - suffix.length()));
            var candidate = trimmed + suffix;
            if (!seenNames.contains(candidate)) {
                seenNames.add(candidate);
                return candidate;
            }
            counter++;
        }
    }
}
