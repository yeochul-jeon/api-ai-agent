package com.apiagent.agent;

import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * 스키마 검색 도구 (grep-like 검색).
 */
public class SchemaSearchTool {

    private final int maxChars;

    public SchemaSearchTool(int maxChars) {
        this.maxChars = maxChars;
    }

    /**
     * 스키마 JSON에서 regex 패턴 검색.
     *
     * @param rawSchema 원본 스키마 JSON 문자열
     * @param pattern   정규식 패턴 (대소문자 무시)
     * @param context   매치 주변 줄 수 (기본 10)
     * @param offset    건너뛸 매치 수 (페이지네이션)
     * @return grep 스타일 출력
     */
    public String search(String rawSchema, String pattern, int context, int offset) {
        if (rawSchema == null || rawSchema.isEmpty()) {
            return "error: schema empty";
        }

        if (offset < 0) {
            return "error: offset must be >= 0";
        }

        Pattern regex;
        try {
            regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        } catch (Exception e) {
            return "error: invalid regex - " + e.getMessage();
        }

        var lines = rawSchema.split("\n");
        var matchedIndices = new ArrayList<Integer>();
        for (int i = 0; i < lines.length; i++) {
            if (regex.matcher(lines[i]).find()) {
                matchedIndices.add(i);
            }
        }

        if (matchedIndices.isEmpty()) {
            return "(no matches)";
        }

        int totalMatches = matchedIndices.size();
        if (offset >= totalMatches) {
            return "(%d matches) offset %d is beyond available results.".formatted(totalMatches, offset);
        }

        // 블록 생성
        var blocks = new ArrayList<String>();
        for (int idx = offset; idx < matchedIndices.size(); idx++) {
            int i = matchedIndices.get(idx);
            int start = Math.max(0, i - context);
            int end = Math.min(lines.length, i + context + 1);
            var blockLines = new StringBuilder();
            for (int j = start; j < end; j++) {
                var sep = (j == i) ? ":" : "-";
                blockLines.append("%d%s%s\n".formatted(j + 1, sep, lines[j]));
            }
            blocks.add(blockLines.toString().stripTrailing());
        }

        // 문자 제한에 맞게 절삭
        var selectedBlocks = new ArrayList<>(blocks);
        var output = assemble(selectedBlocks, totalMatches, offset);

        while (output.length() > maxChars && !selectedBlocks.isEmpty()) {
            selectedBlocks.removeLast();
            output = assemble(selectedBlocks, totalMatches, offset);
        }

        if (selectedBlocks.isEmpty()) {
            return "(%d matches) Unable to show results within limit.".formatted(totalMatches);
        }

        return output;
    }

    private String assemble(ArrayList<String> blocks, int totalMatches, int offset) {
        int shown = blocks.size();
        boolean hasMore = (offset + shown) < totalMatches;

        var header = "(%d matches, showing %d%s)".formatted(
                totalMatches, shown, hasMore ? ", truncated" : "");

        var sb = new StringBuilder(header);
        if (!blocks.isEmpty()) {
            sb.append("\n").append(String.join("\n--\n", blocks));
        }
        if (hasMore) {
            sb.append("\n[TRUNCATED - rerun with offset=%d]".formatted(offset + shown));
        }

        return sb.toString();
    }
}
