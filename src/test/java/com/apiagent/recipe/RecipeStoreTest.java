package com.apiagent.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecipeStoreTest {

    private RecipeStore store;

    @BeforeEach
    void setUp() {
        store = new RecipeStore(64);
    }

    @Test
    void 레시피_저장_및_조회() {
        var recipe = Map.<String, Object>of(
                "steps", List.of(Map.of("kind", "graphql", "query_template", "{ users { id } }")),
                "params", Map.of("limit", Map.of("type", "int", "default", 10))
        );

        var id = store.saveRecipe("gql:test", "hash1", "list users", recipe, "list_users");
        assertThat(id).startsWith("r_");

        var retrieved = store.getRecipe(id);
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.get("steps")).isNotNull();
    }

    @Test
    void 유사_레시피_제안() {
        store.saveRecipe("gql:test", "hash1", "list all users", Map.of("steps", List.of()), "list_users");
        store.saveRecipe("gql:test", "hash1", "get user by id", Map.of("steps", List.of()), "get_user");
        store.saveRecipe("gql:test", "hash1", "count total orders", Map.of("steps", List.of()), "count_orders");

        var suggestions = store.suggestRecipes("gql:test", "hash1", "show all users", 3);
        assertThat(suggestions).isNotEmpty();
        // "list all users"가 "show all users"와 가장 유사해야 함
        assertThat(suggestions.getFirst().get("tool_name")).isEqualTo("list_users");
    }

    @Test
    void LRU_퇴출() {
        var smallStore = new RecipeStore(2);
        var id1 = smallStore.saveRecipe("api", "h", "q1", Map.of("steps", List.of()), "t1");
        var id2 = smallStore.saveRecipe("api", "h", "q2", Map.of("steps", List.of()), "t2");
        var id3 = smallStore.saveRecipe("api", "h", "q3", Map.of("steps", List.of()), "t3");

        // id1은 퇴출되어야 함
        assertThat(smallStore.getRecipe(id1)).isNull();
        assertThat(smallStore.getRecipe(id2)).isNotNull();
        assertThat(smallStore.getRecipe(id3)).isNotNull();
    }

    @Test
    void sha256Hex_해싱() {
        var hash = RecipeStore.sha256Hex("test");
        assertThat(hash).hasSize(64);
        // 동일 입력 → 동일 해시
        assertThat(RecipeStore.sha256Hex("test")).isEqualTo(hash);
        // 다른 입력 → 다른 해시
        assertThat(RecipeStore.sha256Hex("test2")).isNotEqualTo(hash);
    }

    @Test
    void 빈_질문_유사도_0() {
        assertThat(store.similarity("", "anything")).isEqualTo(0.0);
        assertThat(store.similarity("anything", "")).isEqualTo(0.0);
    }
}
