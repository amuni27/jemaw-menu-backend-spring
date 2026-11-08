package com.agafari.com.migration;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.subscription.LifecycleStatus;
import com.agafari.com.subscription.TransitionRules;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGF SUB-R2-06: V20 adds the lifecycle columns and {@code scheduled_change}, replaces the subscription guard function
 * (V13 is untouched), and refuses PROVIDER rows until the payment-evidence migration exists.
 */
class LifecycleMigrationTest extends AbstractIntegrationTest {

    private static final String DEV = "lifecycle_dev";
    private static final String FRESH = "lifecycle_fresh";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    private User admin;

    @BeforeEach
    void setUp() {
        jdbc.execute("drop schema if exists " + DEV + " cascade");
        jdbc.execute("drop schema if exists " + FRESH + " cascade");
        jdbc.execute("create schema " + DEV);
        admin = data.admin("v20-admin");
        config(jdbc, 1, "SETTING_2", true, true, true, admin.getId());
    }

    @AfterEach
    void dropSchemas() {
        jdbc.execute("drop schema if exists " + DEV + " cascade");
        jdbc.execute("drop schema if exists " + FRESH + " cascade");
    }

    private Flyway flyway(String schema, String target) {
        var configuration = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("1");
        if (target != null) configuration.target(MigrationVersion.fromVersion(target));
        return configuration.load();
    }

    private void legacyDatabaseAtV19() throws Exception {
        String ddl = new ClassPathResource("db/legacy/ddl-auto-schema.sql").getContentAsString(StandardCharsets.UTF_8);
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("set search_path to " + DEV);
            for (String statement : ddl.split(";")) {
                String sql = statement.lines().filter(l -> !l.strip().startsWith("--")).reduce("", (a, b) -> a + "\n" + b).strip();
                if (!sql.isEmpty()) c.createStatement().execute(sql);
            }
            c.createStatement().execute("set search_path to public");
        }
        flyway(DEV, "19").migrate();
    }

    private void seedDev(String id, String businessSuffix, String status, String source, String plan, String periodEnd, String endedAt) {
        String business = "biz-" + businessSuffix;
        jdbc.update("insert into " + DEV + ".users (id, email, full_name, password_hash, role, status, \"createdAt\", \"updatedAt\") values (?, ?, 'x', 'x', 'VENUE', 'ACTIVE', now(), now()) on conflict do nothing", "u-" + businessSuffix, businessSuffix + "@example.test");
        jdbc.update("insert into " + DEV + ".business (id, owner_user_id, name, business_phone, street_address, city, state, zipcode, custom_subdomain, currency, open24_7, \"createdAt\", \"updatedAt\") "
                + "values (?, ?, ?, '+251911', 'Road', 'Addis Ababa', 'AA', '', ?, 'ETB', true, now(), now())", business, "u-" + businessSuffix, "B " + businessSuffix, "sub-" + businessSuffix);
        jdbc.update("insert into " + DEV + ".business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, created_by_user_id, "
                + "updated_at, updated_by_user_id, ended_at, row_version) values (?, ?, ?, ?, 1, " + periodEnd + ", ?, now() - interval '10 days', 'seed', now() - interval '10 days', 'seed', " + endedAt + ", 3)",
                id, business, plan, status, source);
    }

    private void seedConfigDev() {
        jdbc.update("insert into " + DEV + ".users (id, email, full_name, password_hash, role, status, \"createdAt\", \"updatedAt\") values ('seed', 'seed@example.test', 'x', 'x', 'ADMIN', 'ACTIVE', now(), now())");
        jdbc.update("insert into " + DEV + ".monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, created_at, created_by_user_id) "
                + "values (1, 'SETTING_2', true, true, true, now(), now(), 'seed')");
    }

    private static final String ORIGINAL_COLUMNS = "id, business_id, plan_code, status, config_version, period_end, transition_date, source, created_at, created_by_user_id, updated_at, updated_by_user_id, ended_at, row_version";

    private List<Map<String, Object>> devRows() {
        return jdbc.queryForList("select " + ORIGINAL_COLUMNS + " from " + DEV + ".business_subscription order by id");
    }

    private List<String> history(String schema) {
        return jdbc.queryForList("select version from " + schema + ".flyway_schema_history where version is not null order by installed_rank", String.class);
    }

    // ---- S1-08B T1-T4

    @Test
    void anExistingDatabaseUpgradesFromV19KeepingEveryRow() throws Exception {
        legacyDatabaseAtV19();
        seedConfigDev();
        seedDev("s-manual", "a", "ACTIVE", "MANUAL", "MODEL_4", "now() + interval '30 days'", "null");
        seedDev("s-open", "b", "ACTIVE", "MANUAL", "MODEL_3", "null", "null");
        seedDev("s-owner", "c", "ACTIVE", "OWNER", "MODEL_2", "null", "null");
        seedDev("s-trial", "d", "TRIAL", "TRIAL", "MODEL_1", "now() + interval '10 days'", "null");
        seedDev("s-ended", "e", "EXPIRED", "MANUAL", "MODEL_4", "now() - interval '1 day'", "now() - interval '1 day'");
        seedDev("s-stored-gf", "f", "GRANDFATHERED", "MANUAL", "MODEL_3", "null", "null");
        List<Map<String, Object>> before = devRows();

        assertThat(flyway(DEV, "20").migrate().migrationsExecuted).isEqualTo(1);

        assertThat(devRows()).isEqualTo(before);
        assertThat(jdbc.queryForList("select distinct price_id, current_period_start, grace_days, past_due_since, open_ended, provider, provider_subscription_ref, ended_reason, replaced_by_id from " + DEV + ".business_subscription"))
                .singleElement().satisfies(row -> {
                    assertThat(row.get("open_ended")).isEqualTo(false);
                    assertThat(row.entrySet().stream().filter(e -> !e.getKey().equals("open_ended")).map(Map.Entry::getValue)).containsOnlyNulls();
                });
        assertThat(history(DEV)).endsWith("19", "20");
        flyway(DEV, "20").validate();
    }

    @Test
    void aFreshDatabaseReachesV20AndAgreesWithTheUpgradedSchema() throws Exception {
        legacyDatabaseAtV19();
        flyway(DEV, "20").migrate();
        jdbc.execute("create schema " + FRESH);
        Flyway.configure().dataSource(dataSource).schemas(FRESH).locations("classpath:db/migration").target(MigrationVersion.fromVersion("20")).load().migrate();
        assertThat(history(FRESH)).hasSize(20).endsWith("20");
        Flyway.configure().dataSource(dataSource).schemas(FRESH).locations("classpath:db/migration").target(MigrationVersion.fromVersion("20")).load().validate();
        String columns = "select column_name, data_type, is_nullable, column_default from information_schema.columns where table_schema = ? and table_name = ? order by column_name";
        for (String table : List.of("business_subscription", "scheduled_change")) {
            assertThat(jdbc.queryForList(columns, FRESH, table)).as(table).isNotEmpty().isEqualTo(jdbc.queryForList(columns, DEV, table));
        }
    }

    @Test
    void aRepeatedStartChangesNothing() throws Exception {
        legacyDatabaseAtV19();
        seedConfigDev();
        seedDev("s-manual", "a", "ACTIVE", "MANUAL", "MODEL_4", "now() + interval '30 days'", "null");
        flyway(DEV, "20").migrate();
        List<Map<String, Object>> after = devRows();
        assertThat(flyway(DEV, "20").migrate().migrationsExecuted).isZero();
        assertThat(devRows()).isEqualTo(after);
        assertThat(history(DEV)).endsWith("19", "20").doesNotHaveDuplicates();
    }

    @Test
    void aSeededProviderRowStopsTheMigrationAndLeavesTheDatabaseAtV19() throws Exception {
        legacyDatabaseAtV19();
        seedConfigDev();
        seedDev("s-provider", "p", "ACTIVE", "PROVIDER", "MODEL_4", "now() + interval '30 days'", "null");
        List<Map<String, Object>> before = devRows();
        assertThatThrownBy(() -> flyway(DEV, "20").migrate()).hasMessageContaining("PROVIDER");
        assertThat(devRows()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema = ? and table_name = 'business_subscription' and column_name = 'ended_reason'", Integer.class, DEV)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema = ? and table_name = 'scheduled_change'", Integer.class, DEV)).isZero();
    }

    // ---- the guard equals the accepted transition table

    private String row(String status, String source, String plan, String extraColumn, String extraValue) {
        Tenant t = data.tenant("v20-" + UUID.randomUUID().toString().substring(0, 8));
        String id = UUID.randomUUID().toString();
        String ended = status.equals("EXPIRED") ? "now()" : "null";
        String pastDue = status.equals("PAST_DUE") ? "now()" : "null";
        String periodEnd = status.equals("TRIAL") ? "now() + interval '10 days'" : "null";
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, created_by_user_id, updated_at, updated_by_user_id, ended_at, past_due_since, row_version"
                + (extraColumn == null ? "" : ", " + extraColumn) + ") values (?, ?, ?, ?, 1, " + periodEnd + ", ?, now(), 'seed', now(), 'seed', " + ended + ", " + pastDue + ", 0" + (extraColumn == null ? "" : ", " + extraValue) + ")",
                id, t.businessId(), plan, status, source);
        return id;
    }

    private String rowFor(LifecycleStatus status) {
        return status == LifecycleStatus.TRIAL ? row("TRIAL", "TRIAL", "MODEL_1", null, null) : row(status.name(), "MANUAL", "MODEL_4", null, null);
    }

    private static String assignment(LifecycleStatus to) {
        return switch (to) {
            case EXPIRED -> "status = 'EXPIRED', ended_at = now(), past_due_since = null";
            case PAST_DUE -> "status = 'PAST_DUE', past_due_since = now()";
            default -> "status = '" + to + "', past_due_since = null";
        };
    }

    static Stream<Arguments> statusChanges() {
        List<Arguments> pairs = new ArrayList<>();
        for (LifecycleStatus from : LifecycleStatus.values()) for (LifecycleStatus to : LifecycleStatus.values()) if (from != to) pairs.add(Arguments.of(from, to));
        return pairs.stream();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("statusChanges")
    void everyStatusChangeIsDecidedExactlyAsTheTransitionTableSays(LifecycleStatus from, LifecycleStatus to) {
        String id = rowFor(from);
        String sql = "update business_subscription set " + assignment(to) + " where id = ?";
        if (TransitionRules.statusChangesOfExistingRows().contains(List.of(from, to))) {
            assertThatCode(() -> jdbc.update(sql, id)).doesNotThrowAnyException();
            assertThat(jdbc.queryForObject("select status from business_subscription where id = ?", String.class, id)).isEqualTo(to.name());
        } else {
            assertThatThrownBy(() -> jdbc.update(sql, id)).hasMessageContaining(from == LifecycleStatus.EXPIRED ? "ended subscriptions can't be changed" : "can only be ended");
            assertThat(jdbc.queryForObject("select status from business_subscription where id = ?", String.class, id)).isEqualTo(from.name());
        }
    }

    @Test
    void theTableStillForbidsATrialBecomingActiveOnTheSameRow() {
        assertThat(TransitionRules.statusChangesOfExistingRows()).doesNotContain(List.of(LifecycleStatus.TRIAL, LifecycleStatus.ACTIVE));
        assertThat(Set.copyOf(TransitionRules.statusChangesOfExistingRows())).contains(List.of(LifecycleStatus.ACTIVE, LifecycleStatus.PAST_DUE), List.of(LifecycleStatus.PAST_DUE, LifecycleStatus.ACTIVE));
    }

    // ---- period edits stay as they are for MANUAL, TRIAL and OWNER (R-4)

    @Test
    void manualTrialAndOwnerEndDatesCanStillMoveEitherWay() {
        for (String[] row : List.of(new String[]{"ACTIVE", "MANUAL", "MODEL_4"}, new String[]{"TRIAL", "TRIAL", "MODEL_1"}, new String[]{"ACTIVE", "OWNER", "MODEL_2"})) {
            String id = row(row[0], row[1], row[2], null, null);
            jdbc.update("update business_subscription set period_end = now() + interval '90 days' where id = ?", id);
            jdbc.update("update business_subscription set period_end = now() + interval '5 days' where id = ?", id);
            assertThat(jdbc.queryForObject("select period_end < now() + interval '6 days' from business_subscription where id = ?", Boolean.class, id)).as(row[1]).isTrue();
        }
    }

    // ---- other guard rules

    @Test
    void pastDueSinceAccompaniesPastDueOnly() {
        String active = row("ACTIVE", "MANUAL", "MODEL_4", null, null);
        assertThatThrownBy(() -> jdbc.update("update business_subscription set status = 'PAST_DUE' where id = ?", active)).hasMessageContaining("ck_business_subscription_past_due");
        assertThatThrownBy(() -> jdbc.update("update business_subscription set past_due_since = now() where id = ?", active)).hasMessageContaining("ck_business_subscription_past_due");
    }

    /** A fixture row, not a price: price_id references plan_price since V22. */
    private String fixturePrice() {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into plan_price (id, plan_code, market, currency, billing_interval, amount_minor, version, valid_from) values (?, 'MODEL_4', 'US', 'USD', 'MONTH', 1000, (select count(*) + 1 from plan_price), now())", id);
        return id;
    }

    @Test
    void billingReferencesCantChangeOnceSet() {
        for (String column : List.of("price_id", "provider", "provider_subscription_ref")) {
            String value = column.equals("price_id") ? fixturePrice() : "first-" + UUID.randomUUID().toString().substring(0, 8); // V22: price_id is a foreign key
            String id = row("ACTIVE", "MANUAL", "MODEL_4", column, "'" + value + "'");
            assertThatThrownBy(() -> jdbc.update("update business_subscription set " + column + " = 'second' where id = ?", id)).as(column).hasMessageContaining("can't be changed");
        }
        String graced = row("ACTIVE", "MANUAL", "MODEL_4", "grace_days", "7");
        assertThatThrownBy(() -> jdbc.update("update business_subscription set grace_days = 3 where id = ?", graced)).hasMessageContaining("can't be changed");
    }

    @Test
    void billingReferencesCanBeSetTheFirstTime() {
        String id = row("ACTIVE", "MANUAL", "MODEL_4", null, null);
        jdbc.update("update business_subscription set price_id = ?, provider = 'x', grace_days = 7 where id = ?", fixturePrice(), id);
        assertThat(jdbc.queryForObject("select grace_days from business_subscription where id = ?", Integer.class, id)).isEqualTo(7);
    }

    @Test
    void aProviderSubscriptionRefIsUnique() {
        row("ACTIVE", "MANUAL", "MODEL_4", "provider_subscription_ref", "'same-ref'");
        assertThatThrownBy(() -> row("ACTIVE", "MANUAL", "MODEL_4", "provider_subscription_ref", "'same-ref'")).hasMessageContaining("uk_business_subscription_provider_ref");
    }

    @Test
    void theOriginalImmutableColumnsAndDeletesStayRefused() {
        String id = row("ACTIVE", "MANUAL", "MODEL_4", null, null);
        assertThatThrownBy(() -> jdbc.update("update business_subscription set plan_code = 'MODEL_3' where id = ?", id)).hasMessageContaining("can't be changed");
        assertThatThrownBy(() -> jdbc.update("delete from business_subscription where id = ?", id)).hasMessageContaining("can't be deleted");
    }

    // ---- PROVIDER rows are refused until the evidence migration

    @Test
    void aProviderRowCantBeInserted() {
        assertThatThrownBy(() -> row("ACTIVE", "PROVIDER", "MODEL_4", null, null)).hasMessageContaining("PROVIDER");
    }

    @Test
    void aRowCantBecomeProvider() {
        String id = row("ACTIVE", "MANUAL", "MODEL_4", null, null);
        assertThatThrownBy(() -> jdbc.update("update business_subscription set source = 'PROVIDER' where id = ?", id)).hasMessageContaining("can't be changed");
    }

    // ---- scheduled_change

    private void change(String businessId, String subscription, String status) {
        jdbc.update("insert into scheduled_change (id, business_id, from_subscription_id, kind, effective_at, status) values (?, ?, ?, 'CANCEL', now() + interval '10 days', ?)",
                UUID.randomUUID().toString(), businessId, subscription, status);
    }

    private String[] subscribed() {
        Tenant t = data.tenant("v20-sc-" + UUID.randomUUID().toString().substring(0, 8));
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, source, created_at, created_by_user_id, updated_at, updated_by_user_id, row_version) values (?, ?, 'MODEL_4', 'ACTIVE', 1, 'MANUAL', now(), 's', now(), 's', 0)", id, t.businessId());
        return new String[]{t.businessId(), id};
    }

    @Test
    void aRestaurantHasAtMostOnePendingScheduledChange() {
        String[] s = subscribed();
        change(s[0], s[1], "PENDING");
        assertThatThrownBy(() -> change(s[0], s[1], "PENDING")).hasMessageContaining("uk_scheduled_change_pending");
        assertThatThrownBy(() -> change(s[0], s[1], "AWAITING_PAYMENT")).hasMessageContaining("uk_scheduled_change_pending");
        assertThatCode(() -> change(s[0], s[1], "APPLIED")).doesNotThrowAnyException();
        assertThatCode(() -> change(s[0], s[1], "SUPERSEDED")).doesNotThrowAnyException();
    }

    @Test
    void scheduledChangeStatusAndKindAreChecked() {
        String[] s = subscribed();
        assertThatThrownBy(() -> change(s[0], s[1], "NONSENSE")).hasMessageContaining("ck_scheduled_change_status");
        assertThatThrownBy(() -> jdbc.update("insert into scheduled_change (id, business_id, from_subscription_id, kind, effective_at, status) values ('x', ?, ?, 'OTHER', now(), 'APPLIED')", s[0], s[1]))
                .hasMessageContaining("ck_scheduled_change_kind");
    }
}
