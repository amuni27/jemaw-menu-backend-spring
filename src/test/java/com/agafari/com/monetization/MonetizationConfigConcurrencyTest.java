package com.agafari.com.monetization;

import com.agafari.com.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * AGF E4-M2 (AC8): concurrent saves from the same base version (real PostgreSQL). Exactly one wins; every other request
 * gets 409 CONFIG_VERSION_CONFLICT and no extra version is written. Two paths: a loser that reads after the winner
 * committed (stale version), and a loser that read before and then collides on the primary key (forced deterministically).
 */
class MonetizationConfigConcurrencyTest extends AbstractIntegrationTest {

    private static final int THREADS = 8;
    private static final int ROUNDS = 5;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @Test
    void ac8ConcurrentSavesFromTheSameVersionHaveExactlyOneWinner() throws Exception {
        String admin = data.adminBearer(data.admin("racer"));
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                int base = round;
                String models = round % 2 == 0 ? "[\"MODEL_2\"]" : "[\"MODEL_3\"]"; // differs from the current one each round
                CyclicBarrier barrier = new CyclicBarrier(THREADS);
                List<Future<Integer>> results = new ArrayList<>();
                for (int t = 0; t < THREADS; t++) {
                    results.add(pool.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return mockMvc.perform(put("/api/admin/monetization-config").header("Authorization", admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"setting\":\"SETTING_2\",\"models\":" + models + ",\"expectedVersion\":" + base + "}"))
                                .andReturn().getResponse().getStatus();
                    }));
                }
                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> f : results) statuses.add(f.get(30, TimeUnit.SECONDS));

                assertThat(statuses).as("round " + round).containsOnly(200, 409);
                assertThat(statuses.stream().filter(s -> s == 200).count()).as("round " + round).isEqualTo(1);
                assertThat(jdbc.queryForObject("select count(*) from monetization_config", Integer.class)).isEqualTo(round + 1);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action = 'MONETIZATION_CONFIG_CHANGED'", Integer.class))
                .isEqualTo(ROUNDS);
    }

    @Test
    void ac8ALoserThatCollidesOnThePrimaryKeyGetsAConflictNotAnError() throws Exception {
        var adminUser = data.admin("pk-loser");
        String admin = data.adminBearer(adminUser);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection winner = dataSource.getConnection()) {
            winner.setAutoCommit(false);
            try (PreparedStatement insert = winner.prepareStatement("insert into monetization_config (version, setting, model_2_enabled, "
                    + "model_3_enabled, model_4_enabled, effective_from, created_at, created_by_user_id) values (1, 'SETTING_1', false, false, false, now(), now(), ?)")) {
                insert.setString(1, adminUser.getId());
                insert.executeUpdate(); // version 1, not committed yet: the request still reads "not configured"
            }
            Future<String> loser = pool.submit(() -> {
                var response = mockMvc.perform(put("/api/admin/monetization-config").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"setting\":\"SETTING_2\",\"models\":[\"MODEL_2\"],\"expectedVersion\":0}"))
                        .andReturn().getResponse();
                return response.getStatus() + " " + JsonPath.read(response.getContentAsString(), "$.code");
            });
            waitUntilAnInsertWaitsForTheLock();
            winner.commit();

            assertThat(loser.get(30, TimeUnit.SECONDS)).isEqualTo("409 CONFIG_VERSION_CONFLICT");
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForList("select setting from monetization_config", String.class)).containsExactly("SETTING_1");
        assertThat(jdbc.queryForList("select detail from admin_audit_event where action = 'MONETIZATION_CONFIG_CHANGE_REJECTED'", String.class))
                .containsExactly("version_conflict");
    }

    private void waitUntilAnInsertWaitsForTheLock() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Integer waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock' "
                    + "and query like 'insert into monetization_config%'", Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(50);
        }
        fail("the request never waited on the uncommitted version");
    }
}
