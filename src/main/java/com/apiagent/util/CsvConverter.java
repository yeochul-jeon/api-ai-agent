package com.apiagent.util;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * DuckDB를 활용한 CSV 변환 유틸리티.
 */
@Component
public class CsvConverter {

    private static final Logger log = LoggerFactory.getLogger(CsvConverter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 데이터를 CSV 문자열로 변환.
     */
    public String toCsv(Object data) {
        if (data == null) return "";

        var list = data instanceof List<?> l ? l : List.of(data);
        if (list.isEmpty()) return "";

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("csv-", ".json");
            MAPPER.writeValue(tempFile.toFile(), list);

            try (var conn = DriverManager.getConnection("jdbc:duckdb:")) {
                try (var stmt = conn.createStatement()) {
                    stmt.execute("CREATE TABLE t AS SELECT * FROM read_json_auto('%s')".formatted(tempFile));
                }
                try (var stmt = conn.createStatement();
                     var rs = stmt.executeQuery("SELECT * FROM t")) {

                    var meta = rs.getMetaData();
                    var colCount = meta.getColumnCount();
                    var writer = new StringWriter();

                    // 헤더
                    for (int i = 1; i <= colCount; i++) {
                        if (i > 1) writer.write(",");
                        writer.write(meta.getColumnName(i));
                    }
                    writer.write("\n");

                    // 데이터
                    while (rs.next()) {
                        for (int i = 1; i <= colCount; i++) {
                            if (i > 1) writer.write(",");
                            var val = rs.getObject(i);
                            writer.write(val != null ? val.toString() : "");
                        }
                        writer.write("\n");
                    }

                    return writer.toString();
                }
            }
        } catch (Exception e) {
            log.error("CSV 변환 오류", e);
            return "";
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
        }
    }
}
