package com.apiagent.context;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

/**
 * HTTP 헤더에서 RequestContext를 파싱하여 ThreadLocal에 설정하는 필터.
 */
@Component
@Order(1)
public class RequestContextFilter implements Filter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            if (request instanceof HttpServletRequest httpReq) {
                var targetUrl = httpReq.getHeader("X-Target-URL");
                var apiType = httpReq.getHeader("X-API-Type");

                if (targetUrl != null && apiType != null) {
                    var ctx = new RequestContext(
                            targetUrl,
                            apiType,
                            parseJsonMap(httpReq.getHeader("X-Target-Headers")),
                            parseJsonList(httpReq.getHeader("X-Allow-Unsafe-Paths")),
                            httpReq.getHeader("X-Base-URL"),
                            parseBoolean(httpReq.getHeader("X-Include-Result")),
                            parseJsonList(httpReq.getHeader("X-Poll-Paths"))
                    );
                    RequestContextHolder.set(ctx);
                }
            }
            chain.doFilter(request, response);
        } finally {
            RequestContextHolder.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseJsonMap(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<String> parseJsonList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return MAPPER.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private boolean parseBoolean(String raw) {
        if (raw == null) return false;
        return raw.equalsIgnoreCase("true") || raw.equals("1") || raw.equalsIgnoreCase("yes");
    }
}
