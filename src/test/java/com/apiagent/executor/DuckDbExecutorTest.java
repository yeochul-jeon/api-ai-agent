package com.apiagent.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.apiagent.config.ApiAgentProperties;

class DuckDbExecutorTest {

    private DuckDbExecutor executor;

    @BeforeEach
    void setUp() {
        var props = new ApiAgentProperties(
                null, null, null, null,
                0, 0, 0, 0, 0, 0, 0,
                false, null, false, 0
        );
        executor = new DuckDbExecutor(props);
    }

    @Test
    void list_데이터에_SQL_실행() {
        var data = List.of(
                Map.of("name", "Alice", "age", 30),
                Map.of("name", "Bob", "age", 25),
                Map.of("name", "Charlie", "age", 35)
        );

        var result = executor.executeSql(Map.of("users", data), "SELECT name FROM users WHERE age > 28");

        assertThat(result.get("success")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) result.get("result");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("name")).isEqualTo("Alice");
        assertThat(rows.get(1).get("name")).isEqualTo("Charlie");
    }

    @Test
    void 잘못된_SQL에_에러_반환() {
        var result = executor.executeSql(List.of(Map.of("x", 1)), "SELECT invalid_col FROM data");
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error").toString()).contains("invalid_col");
    }

    @Test
    void extractTables_list_데이터() {
        var data = List.of(Map.of("id", 1), Map.of("id", 2));
        var extraction = executor.extractTables(data, "items");

        assertThat(extraction.tables()).containsKey("items");
        assertThat(extraction.schemaInfo()).isNull();
    }

    @Test
    void extractTables_dict_내_list() {
        var data = Map.of("results", List.of(Map.of("id", 1)));
        var extraction = executor.extractTables(data, "items");

        assertThat(extraction.tables()).containsKey("items");
        assertThat(extraction.schemaInfo()).isNull();
    }

    @Test
    void extractTables_dict_단일행_래핑() {
        var data = Map.of("name", "test", "count", 42);
        var extraction = executor.extractTables(data, "info");

        assertThat(extraction.tables()).containsKey("info");
        assertThat(extraction.schemaInfo()).isNotNull();
        assertThat(extraction.schemaInfo().get("rows")).isEqualTo(1);
    }

    @Test
    void truncateForContext_작은데이터_절삭안함() {
        var data = List.of(Map.of("id", 1), Map.of("id", 2));
        var result = executor.truncateForContext(data, "t", 10000);

        assertThat(result.get("truncated")).isEqualTo(false);
        assertThat(result.get("rows")).isEqualTo(2);
    }

    @Test
    void truncateForContext_큰데이터_절삭() {
        var data = new java.util.ArrayList<Map<String, Object>>();
        for (int i = 0; i < 100; i++) {
            data.add(Map.of("id", i, "name", "user_" + i, "description", "A".repeat(100)));
        }
        var result = executor.truncateForContext(data, "t", 500);

        assertThat(result.get("truncated")).isEqualTo(true);
        assertThat((int) result.get("showing")).isLessThan(100);
    }
}
