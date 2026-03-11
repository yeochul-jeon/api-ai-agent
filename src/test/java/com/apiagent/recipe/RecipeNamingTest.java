package com.apiagent.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;

import org.junit.jupiter.api.Test;

class RecipeNamingTest {

    @Test
    void sanitize_정규화() {
        assertThat(RecipeNaming.sanitize("Get User Orders")).isEqualTo("get_user_orders");
        assertThat(RecipeNaming.sanitize("hello-world!")).isEqualTo("helloworld");
        assertThat(RecipeNaming.sanitize(null)).isEqualTo("recipe");
        assertThat(RecipeNaming.sanitize("")).isEqualTo("recipe");
    }

    @Test
    void fromQuestion_변환() {
        assertThat(RecipeNaming.fromQuestion("List all users")).isEqualTo("list_all_users");
        assertThat(RecipeNaming.fromQuestion("123 start")).isEqualTo("r_123_start");
    }

    @Test
    void deduplicate_고유화() {
        var seen = new HashSet<String>();
        assertThat(RecipeNaming.deduplicate("list_users", seen, 40)).isEqualTo("list_users");
        assertThat(RecipeNaming.deduplicate("list_users", seen, 40)).isEqualTo("list_users_2");
        assertThat(RecipeNaming.deduplicate("list_users", seen, 40)).isEqualTo("list_users_3");
    }
}
