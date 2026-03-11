package com.apiagent.agent;

import java.util.Map;

import com.apiagent.executor.DuckDbExecutor;

/**
 * SQL 쿼리 도구 (에이전트 내부에서 DuckDB 실행).
 */
public class SqlQueryTool {

    private final DuckDbExecutor executor;

    public SqlQueryTool(DuckDbExecutor executor) {
        this.executor = executor;
    }

    /**
     * 저장된 쿼리 결과에 대해 SQL 실행.
     *
     * @param queryResults 이전 API 호출로 저장된 데이터 (테이블명 → 데이터)
     * @param sql          DuckDB SQL 쿼리
     * @return 결과 맵
     */
    public Map<String, Object> execute(Map<String, Object> queryResults, String sql) {
        if (queryResults == null || queryResults.isEmpty()) {
            return Map.of("success", false, "error", "No data. Call API first.");
        }
        return executor.executeSql(queryResults, sql);
    }
}
