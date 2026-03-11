package com.apiagent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.apiagent.config.ApiAgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * 3단계 응답 절삭기.
 * 1단계: 전체 응답이 제한 내이면 그대로 반환
 * 2단계: 행 수를 줄여서 제한 내로 맞춤
 * 3단계: 문자열 단순 절삭
 */
@Component
@RequiredArgsConstructor
public class ResponseTruncator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApiAgentProperties properties;

    /**
     * 응답을 maxChars 이내로 절삭.
     */
    public String truncate(Object response, Integer maxChars) {
        var limit = maxChars != null ? maxChars : properties.maxResponseChars();

        try {
            var json = MAPPER.writeValueAsString(response);

            // 1단계: 전체 응답이 제한 내
            if (json.length() <= limit) {
                return json;
            }

            // 2단계: list인 경우 행 수 줄이기
            if (response instanceof List<?> list) {
                return truncateList(list, limit);
            }
            if (response instanceof Map<?, ?> map) {
                for (var value : map.values()) {
                    if (value instanceof List<?> list) {
                        return truncateList(list, limit);
                    }
                }
            }

            // 3단계: 단순 절삭
            return json.substring(0, limit) + "... [TRUNCATED]";

        } catch (Exception e) {
            var str = response.toString();
            return str.length() > limit ? str.substring(0, limit) + "... [TRUNCATED]" : str;
        }
    }

    private String truncateList(List<?> list, int limit) {
        var preview = new ArrayList<>();
        int currentSize = 2;
        for (var item : list) {
            try {
                var itemJson = MAPPER.writeValueAsString(item);
                int newSize = currentSize + itemJson.length() + (preview.isEmpty() ? 0 : 1);
                if (newSize > limit) break;
                preview.add(item);
                currentSize = newSize;
            } catch (Exception ignored) {
                break;
            }
        }

        try {
            var result = MAPPER.writeValueAsString(preview);
            if (preview.size() < list.size()) {
                return result + "\n[SHOWING %d/%d rows]".formatted(preview.size(), list.size());
            }
            return result;
        } catch (Exception e) {
            return "[]";
        }
    }
}
