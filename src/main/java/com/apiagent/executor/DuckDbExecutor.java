package com.apiagent.executor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.apiagent.config.ApiAgentProperties;
import io.micrometer.observation.annotation.Observed;
import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * DuckDB SQL 실행기.
 * 인메모리 분석 엔진으로 사용 (요청마다 새 커넥션).
 */
@Component
@RequiredArgsConstructor
public class DuckDbExecutor {

    private static final Logger log = LoggerFactory.getLogger(DuckDbExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApiAgentProperties properties;

    /**
     * API 응답 데이터에서 DuckDB 테이블 추출.
     *
     * @param data API 응답 (dict 또는 list)
     * @param name 테이블명
     * @return 추출 결과 (tables, schemaInfo)
     */
    @SuppressWarnings("unchecked")
    public TableExtraction extractTables(Object data, String name) {
        if (data instanceof List<?> list) {
            return new TableExtraction(Map.of(name, list), null);
        }

        if (data instanceof Map<?, ?> map) {
            // dict에서 최상위 list 찾기
            for (var value : map.values()) {
                if (value instanceof List<?> list) {
                    return new TableExtraction(Map.of(name, list), null);
                }
            }

            // list 없으면 dict를 single-row 테이블로 래핑
            var wrapped = List.<Object>of(data);
            var schemaInfo = extractSchema(wrapped, name);
            return new TableExtraction(Map.of(name, wrapped), schemaInfo);
        }

        return new TableExtraction(Map.of(), null);
    }

    /**
     * 데이터를 LLM 컨텍스트에 맞게 절삭.
     */
    public Map<String, Object> truncateForContext(List<?> data, String tableName, Integer maxChars) {
        var limit = maxChars != null ? maxChars : properties.maxToolResponseChars();
        var totalRows = data.size();

        try {
            var fullJson = MAPPER.writeValueAsString(data);
            if (fullJson.length() <= limit) {
                return Map.of(
                        "table", tableName,
                        "rows", totalRows,
                        "data", data,
                        "truncated", false
                );
            }
        } catch (Exception ignored) {
        }

        // 맞는 행 수 찾기
        var preview = new ArrayList<>();
        int currentSize = 2; // "[]"
        for (var row : data) {
            try {
                var rowJson = MAPPER.writeValueAsString(row);
                int newSize = currentSize + rowJson.length() + (preview.isEmpty() ? 0 : 1);
                if (newSize > limit) break;
                preview.add(row);
                currentSize = newSize;
            } catch (Exception ignored) {
                break;
            }
        }

        var schema = extractSchema(data, tableName);
        return Map.of(
                "table", tableName,
                "rows", totalRows,
                "showing", preview.size(),
                "schema", schema.getOrDefault("schema", ""),
                "data", preview,
                "truncated", true,
                "hint", "Showing %d/%d. Use sql_query to filter.".formatted(preview.size(), totalRows)
        );
    }

    /**
     * JSON 데이터에 대해 SQL 쿼리 실행.
     *
     * @param data  JSON 데이터 (dict 또는 list)
     * @param query DuckDB SQL 쿼리
     * @return 결과 맵 (success/result 또는 error)
     */
    @Observed(name = "executor.sql", contextualName = "execute-sql")
    @SuppressWarnings("unchecked")
    public Map<String, Object> executeSql(Object data, String query) {
        var tempFiles = new ArrayList<Path>();
        try (var conn = DriverManager.getConnection("jdbc:duckdb:")) {

            if (data instanceof Map<?, ?> map) {
                for (var entry : map.entrySet()) {
                    if (entry.getValue() instanceof List<?> list && !list.isEmpty()) {
                        var tempFile = writeTempJson(list);
                        tempFiles.add(tempFile);
                        createTable(conn, entry.getKey().toString(), tempFile);
                    }
                }
            } else if (data instanceof List<?> list) {
                var tempFile = writeTempJson(list);
                tempFiles.add(tempFile);
                createTable(conn, "data", tempFile);
            }

            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery(query)) {
                var rows = resultSetToList(rs);
                return Map.of("success", true, "result", rows);
            }

        } catch (SQLException e) {
            return Map.of("success", false, "error", "SQL error: " + e.getMessage());
        } catch (Exception e) {
            log.error("SQL 실행 오류", e);
            return Map.of("success", false, "error", e.getMessage());
        } finally {
            for (var file : tempFiles) {
                try { Files.deleteIfExists(file); } catch (IOException _) {}
            }
        }
    }

    /**
     * DuckDB 스키마 추출.
     */
    Map<String, Object> extractSchema(List<?> data, String tableName) {
        if (data == null || data.isEmpty()) {
            return Map.of("rows", 0, "schema", "", "hint", "Empty table");
        }

        Path tempFile = null;
        try {
            tempFile = writeTempJson(data);
            try (var conn = DriverManager.getConnection("jdbc:duckdb:")) {
                createTable(conn, tableName, tempFile);
                try (var stmt = conn.createStatement();
                     var rs = stmt.executeQuery("DESCRIBE " + tableName)) {
                    var parts = new ArrayList<String>();
                    while (rs.next()) {
                        parts.add(rs.getString(1) + ": " + rs.getString(2));
                    }
                    var schemaStr = String.join(", ", parts);
                    var firstCol = parts.isEmpty() ? "column" : parts.getFirst().split(":")[0];
                    return Map.of(
                            "rows", data.size(),
                            "schema", schemaStr,
                            "hint", "Use sql_query() to access fields. Example: SELECT %s FROM %s".formatted(firstCol, tableName)
                    );
                }
            }
        } catch (Exception e) {
            log.error("스키마 추출 오류", e);
            return Map.of("rows", data.size(), "schema", "unknown", "hint", e.getMessage());
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException _) {}
            }
        }
    }

    private Path writeTempJson(Object data) throws IOException {
        var tempFile = Files.createTempFile("duckdb-", ".json");
        MAPPER.writeValue(tempFile.toFile(), data);
        return tempFile;
    }

    private void createTable(Connection conn, String tableName, Path jsonFile) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE %s AS SELECT * FROM read_json_auto('%s')".formatted(tableName, jsonFile));
        }
    }

    private List<Map<String, Object>> resultSetToList(ResultSet rs) throws SQLException {
        var meta = rs.getMetaData();
        var colCount = meta.getColumnCount();
        var rows = new ArrayList<Map<String, Object>>();
        while (rs.next()) {
            var row = new LinkedHashMap<String, Object>();
            for (int i = 1; i <= colCount; i++) {
                row.put(meta.getColumnName(i), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }

    public record TableExtraction(Map<String, ?> tables, Map<String, Object> schemaInfo) {}
}
