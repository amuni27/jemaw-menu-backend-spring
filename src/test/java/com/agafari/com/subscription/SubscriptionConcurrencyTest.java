package com.agafari.com.subscription;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF E4-M3 (AC7): concurrent creates for one restaurant (real PostgreSQL). Exactly one current subscription results;
 * every other request gets 409 SUBSCRIPTION_EXISTS. Two paths: a loser that sees the committed winner, and a loser that
 * collides on the partial unique index (forced deterministically, as in E4-M2).
 */
class SubscriptionConcurrencyTest extends AbstractIntegrationTest {

    private static final int THREADS = 8;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    private User configured() {
        User admin = data.admin("sub-racer");
        jdbc.update("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, "
                + "created_at, created_by_user_id) values (1, 'SETTING_2', true, false, true, now(), now(), ?)", admin.getId());
        return admin;
    }

    private String createBody(String plan) {
        return "{\"planCode\":\"" + plan + "\",\"expectedConfigVersion\":1}";
    }

    @Test
    void ac7ConcurrentCreatesForOneRestaurantHaveExactlyOneWinner() throws Exception {
        String admin = data.adminBearer(configured());
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int round = 0; round < 3; round++) {
                Tenant t = data.tenant("race-" + round);
                CyclicBarrier barrier = new CyclicBarrier(THREADS);
                List<Future<Integer>> results = new ArrayList<>();
                for (int i = 0; i < THREADS; i++) {
                    String plan = i % 2 == 0 ? "MODEL_2" : "MODEL_4";
                    results.add(pool.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return mockMvc.perform(post("/api/admin/businesses/{id}/subscription", t.businessId()).header("Authorization", admin)
                                .contentType(MediaType.APPLICATION_JSON).content(createBody(plan))).andReturn().getResponse().getStatus();
                    }));
                }
                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> f : results) statuses.add(f.get(30, TimeUnit.SECONDS));
                assertThat(statuses).as("round " + round).containsOnly(201, 409);
                assertThat(statuses.stream().filter(s -> s == 201).count()).as("round " + round).isEqualTo(1);
                assertThat(jdbc.queryForObject("select count(*) from business_subscription where business_id = ?", Integer.class, t.businessId()))
                        .isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void ac7ALoserThatCollidesOnThePartialUniqueIndexGetsAConflictNotAnError() throws Exception {
        User adminUser = configured();
        String admin = data.adminBearer(adminUser);
        Tenant t = data.tenant("index-loser");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection winner = dataSource.getConnection()) {
            winner.setAutoCommit(false);
            try (PreparedStatement insert = winner.prepareStatement("insert into business_subscription (id, business_id, plan_code, status, "
                    + "config_version, source, created_at, created_by_user_id, updated_at, updated_by_user_id, row_version) "
                    + "values ('winner', ?, 'MODEL_4', 'ACTIVE', 1, 'MANUAL', now(), ?, now(), ?, 0)")) {
                insert.setString(1, t.businessId());
                insert.setString(2, adminUser.getId());
                insert.setString(3, adminUser.getId());
                insert.executeUpdate(); // not committed: the request still sees no current subscription
            }
            Future<String> loser = pool.submit(() -> {
                var response = mockMvc.perform(post("/api/admin/businesses/{id}/subscription", t.businessId()).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("MODEL_2"))).andReturn().getResponse();
                return response.getStatus() + " " + JsonPath.read(response.getContentAsString(), "$.code");
            });
            waitUntilAnInsertWaitsForTheLock();
            winner.commit();

            assertThat(loser.get(30, TimeUnit.SECONDS)).isEqualTo("409 SUBSCRIPTION_EXISTS");
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForList("select plan_code from business_subscription where business_id = ?", String.class, t.businessId()))
                .containsExactly("MODEL_4");
        assertThat(jdbc.queryForList("select detail from admin_audit_event where action = 'SUBSCRIPTION_CHANGE_REJECTED'", String.class))
                .containsExactly("exists");
    }

    private void waitUntilAnInsertWaitsForTheLock() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Integer waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock' "
                    + "and query like 'insert into business_subscription%'", Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(50);
        }
        fail("the request never waited on the uncommitted subscription");
    }
}
