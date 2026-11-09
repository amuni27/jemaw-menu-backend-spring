package com.agafari.com.migration;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AGF V23.4: the verified-payment rule, by SQL only (register §12 D1, D3, §21 R1). Amounts are fixtures, not prices. */
class VerifiedPaymentMigrationTest extends AbstractIntegrationTest {

    private static final String DEV = "v234_dev";
    private static final String WEBHOOK = "'SIGNED_WEBHOOK+API_FETCH'";
    private static final String RECONCILIATION = "'RECONCILIATION_API_FETCH'";
    private static final String CHARGE_RULE = "ck_payment_charge_verified";
    private static final String SUCCESS_RULE = "ck_payment_success_evidence";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    private Tenant owner;

    @BeforeEach
    void setUp() {
        dropSchema();
        owner = data.tenant("verified-owner");
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("drop schema if exists " + DEV + " cascade");
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private String insert(String table, String business, String ref, String kind, String status, String verifiedAt, String verification) {
        String pid = id();
        jdbc.update("insert into " + table + " (id, business_id, provider, provider_payment_ref, amount_minor, currency, kind, status, occurred_at, verified_at, verification) "
                + "values (?, ?, 'P1', ?, 1000, 'ETB', '" + kind + "', '" + status + "', now(), " + verifiedAt + ", " + verification + ")", pid, business, ref);
        return pid;
    }

    private String payment(String kind, String status, String verifiedAt, String verification) {
        return insert("payment", owner.businessId(), id(), kind, status, verifiedAt, verification);
    }

    // ---- every kind and status

    static Stream<Arguments> allowed() {
        return Stream.of(
                Arguments.of("CHARGE", "SUCCEEDED", "now()", WEBHOOK),
                Arguments.of("CHARGE", "SUCCEEDED", "now()", RECONCILIATION),
                Arguments.of("REFUND", "SUCCEEDED", "now()", WEBHOOK),
                Arguments.of("REFUND", "SUCCEEDED", "now()", "'MANUAL'"),
                Arguments.of("REFUND", "PENDING", "null", "null"),
                Arguments.of("REFUND", "FAILED", "null", "null"),
                Arguments.of("REFUND", "PENDING", "now()", WEBHOOK),
                Arguments.of("CHARGEBACK", "SUCCEEDED", "now()", WEBHOOK),
                Arguments.of("CHARGEBACK", "PENDING", "null", "null"),
                Arguments.of("CHARGEBACK", "FAILED", "null", "null"));
    }

    @ParameterizedTest(name = "{0} {1} {2} {3}")
    @MethodSource("allowed")
    void theseRecordsAreAccepted(String kind, String status, String verifiedAt, String verification) {
        String p = payment(kind, status, verifiedAt, verification);
        assertThat(jdbc.queryForObject("select count(*) from payment where id = ?", Integer.class, p)).isEqualTo(1);
    }

    static Stream<Arguments> refused() {
        return Stream.of(
                Arguments.of("CHARGE", "PENDING", "null", "null", CHARGE_RULE),
                Arguments.of("CHARGE", "PENDING", "now()", WEBHOOK, CHARGE_RULE),
                Arguments.of("CHARGE", "FAILED", "null", "null", CHARGE_RULE),
                Arguments.of("CHARGE", "FAILED", "now()", WEBHOOK, CHARGE_RULE),
                Arguments.of("CHARGE", "SUCCEEDED", "null", WEBHOOK, SUCCESS_RULE),
                Arguments.of("CHARGE", "SUCCEEDED", "now()", "null", CHARGE_RULE),
                Arguments.of("CHARGE", "SUCCEEDED", "now()", "''", CHARGE_RULE),
                Arguments.of("CHARGE", "SUCCEEDED", "now()", "'MANUAL'", CHARGE_RULE),
                Arguments.of("CHARGE", "SUCCEEDED", "now()", "'signed_webhook+api_fetch'", CHARGE_RULE),
                Arguments.of("REFUND", "SUCCEEDED", "null", WEBHOOK, SUCCESS_RULE),
                Arguments.of("REFUND", "SUCCEEDED", "now()", "null", SUCCESS_RULE),
                Arguments.of("REFUND", "SUCCEEDED", "now()", "''", SUCCESS_RULE),
                Arguments.of("REFUND", "SUCCEEDED", "now()", "'   '", SUCCESS_RULE),
                Arguments.of("REFUND", "SUCCEEDED", "now()", "E'\\t\\n'", SUCCESS_RULE),
                Arguments.of("CHARGEBACK", "SUCCEEDED", "null", WEBHOOK, SUCCESS_RULE),
                Arguments.of("CHARGEBACK", "SUCCEEDED", "now()", "null", SUCCESS_RULE),
                Arguments.of("CHARGEBACK", "SUCCEEDED", "now()", "' '", SUCCESS_RULE));
    }

    @ParameterizedTest(name = "{0} {1} {2} {3}")
    @MethodSource("refused")
    void theseRecordsAreRefusedByTheDatabase(String kind, String status, String verifiedAt, String verification, String constraint) {
        assertThatThrownBy(() -> payment(kind, status, verifiedAt, verification)).hasMessageContaining(constraint);
    }

    @Test
    void theExistingStatusListIsUnchanged() {
        String def = jdbc.queryForObject("select pg_get_constraintdef(oid) from pg_constraint where conname = 'ck_payment_status'", String.class);
        assertThat(def).contains("PENDING", "SUCCEEDED", "FAILED");
        assertThatThrownBy(() -> payment("REFUND", "REVERSED", "now()", WEBHOOK)).hasMessageContaining("ck_payment_status");
    }

    @Test
    void bothRulesAreValidatedCheckConstraints() {
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conname in (?, ?) and contype = 'c' and convalidated and conrelid = 'payment'::regclass", Integer.class, CHARGE_RULE, SUCCESS_RULE)).isEqualTo(2);
    }

    // ---- what must not have changed

    @Test
    void theLedgerStaysAppendOnly() {
        String p = payment("CHARGE", "SUCCEEDED", "now()", WEBHOOK);
        assertThatThrownBy(() -> jdbc.update("update payment set status = 'FAILED' where id = ?", p)).hasMessageContaining("payments can't be changed or removed");
        assertThatThrownBy(() -> jdbc.update("update payment set verified_at = null where id = ?", p)).hasMessageContaining("payments can't be changed or removed");
        assertThatThrownBy(() -> jdbc.update("delete from payment where id = ?", p)).hasMessageContaining("payments can't be changed or removed");
    }

    @Test
    void oneProviderReferenceIsOneMoneyMovement() {
        String ref = id();
        insert("payment", owner.businessId(), ref, "CHARGE", "SUCCEEDED", "now()", WEBHOOK);
        assertThatThrownBy(() -> insert("payment", owner.businessId(), ref, "CHARGE", "SUCCEEDED", "now()", WEBHOOK)).hasMessageContaining("uk_payment_provider_ref_kind");
        insert("payment", owner.businessId(), ref, "REFUND", "SUCCEEDED", "now()", WEBHOOK); // the identity includes the kind
    }

    private int upsert(String ref, String verification) {
        return jdbc.update("insert into payment (id, business_id, provider, provider_payment_ref, amount_minor, currency, kind, status, occurred_at, verified_at, verification) "
                + "values (?, ?, 'P1', ?, 1000, 'ETB', 'CHARGE', 'SUCCEEDED', now(), now(), " + verification + ") on conflict (provider, provider_payment_ref, kind) do nothing", id(), owner.businessId(), ref);
    }

    @Test
    void aValidReplayIsANoOpAndAnInvalidOneIsStillRefused() {
        String ref = id();
        assertThat(upsert(ref, WEBHOOK)).isEqualTo(1);
        assertThat(upsert(ref, WEBHOOK)).isZero();
        assertThatThrownBy(() -> upsert(ref, "'TRUST_ME'")).hasMessageContaining(CHARGE_RULE);
        assertThat(jdbc.queryForObject("select count(*) from payment where provider_payment_ref = ?", Integer.class, ref)).isEqualTo(1);
    }

    @Test
    void aPaymentMustBelongToARealRestaurant() {
        assertThatThrownBy(() -> insert("payment", "no-such-restaurant", id(), "CHARGE", "SUCCEEDED", "now()", WEBHOOK)).hasMessageContaining("fk_payment_business");
    }

    // ---- migration

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).schemas(DEV).locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("1").target(MigrationVersion.fromVersion(target)).load();
    }

    private void atV23_3() throws Exception {
        jdbc.execute("create schema " + DEV);
        String ddl = new ClassPathResource("db/legacy/ddl-auto-schema.sql").getContentAsString(StandardCharsets.UTF_8);
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("set search_path to " + DEV);
            for (String statement : ddl.split(";")) {
                String sql = statement.lines().filter(l -> !l.strip().startsWith("--")).reduce("", (a, b) -> a + "\n" + b).strip();
                if (!sql.isEmpty()) c.createStatement().execute(sql);
            }
            c.createStatement().execute("set search_path to public");
        }
        flyway("23.3").migrate();
        String d = DEV + ".";
        jdbc.update("insert into " + d + "users (id, email, full_name, password_hash, role, status, \"createdAt\", \"updatedAt\") values ('u1', 'u1@example.test', 'x', 'x', 'VENUE', 'ACTIVE', now(), now())");
        jdbc.update("insert into " + d + "business (id, owner_user_id, name, business_phone, street_address, city, state, zipcode, custom_subdomain, currency, open24_7, \"createdAt\", \"updatedAt\") values ('b1', 'u1', 'B', '1', 'r', 'c', 's', '', 's1', 'ETB', true, now(), now())");
    }

    private void devPayment(String kind, String status, String verifiedAt, String verification) {
        insert(DEV + ".payment", "b1", id(), kind, status, verifiedAt, verification);
    }

    private List<Map<String, Object>> devPayments() {
        return jdbc.queryForList("select * from " + DEV + ".payment order by id");
    }

    private List<String> history() {
        return jdbc.queryForList("select version from " + DEV + ".flyway_schema_history where version is not null and success order by installed_rank", String.class);
    }

    private int devRules() {
        return jdbc.queryForObject("select count(*) from pg_constraint where conname in (?, ?) and conrelid = '" + DEV + ".payment'::regclass", Integer.class, CHARGE_RULE, SUCCESS_RULE);
    }

    @Test
    void aV23_3DatabaseUpgradesKeepingEveryRow() throws Exception {
        atV23_3();
        devPayment("CHARGE", "SUCCEEDED", "now()", WEBHOOK);
        devPayment("CHARGE", "SUCCEEDED", "now()", RECONCILIATION);
        devPayment("REFUND", "SUCCEEDED", "now()", "'MANUAL'");
        devPayment("REFUND", "PENDING", "null", "null");
        devPayment("CHARGEBACK", "FAILED", "null", "null");
        List<Map<String, Object>> before = devPayments();
        assertThat(flyway("23.4").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(devPayments()).isEqualTo(before);
        assertThat(devRules()).isEqualTo(2);
        assertThat(history()).endsWith("23.3", "23.4");
    }

    @Test
    void aProviderSubscriptionWithRealEvidenceSurvivesTheUpgrade() throws Exception {
        atV23_3();
        String d = DEV + ".";
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            var st = c.createStatement();
            st.execute("set search_path to " + DEV); // the V23.2 trigger functions name their tables unqualified
            st.execute("insert into " + d + "monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, created_at, created_by_user_id) values (1, 'SETTING_2', true, true, true, now(), now(), 'u1')");
            st.execute("insert into " + d + "plan_price (id, plan_code, market, currency, billing_interval, amount_minor, version, valid_from) values ('pr1', 'MODEL_4', 'ET', 'ETB', 'MONTH', 1000, 1, now())");
            st.execute("insert into " + d + "billing_invoice (id, business_id, price_id, period_start, period_end, amount_minor, currency, status, provider_invoice_ref) values ('i1', 'b1', 'pr1', '2030-01-01Z', '2030-02-01Z', 1000, 'ETB', 'PAID', 'ref')");
            st.execute("insert into " + d + "payment (id, business_id, invoice_id, provider, provider_payment_ref, amount_minor, currency, kind, status, occurred_at, verified_at, verification) values ('p1', 'b1', 'i1', 'P1', 'r', 1000, 'ETB', 'CHARGE', 'SUCCEEDED', now(), now(), " + WEBHOOK + ")");
            st.execute("insert into " + d + "business_subscription (id, business_id, plan_code, status, config_version, period_end, source, provider, price_id, created_at, created_by_user_id, updated_at, updated_by_user_id, row_version) values ('s1', 'b1', 'MODEL_4', 'ACTIVE', 1, '2030-02-01Z', 'PROVIDER', 'P1', 'pr1', now(), 'u1', now(), 'u1', 0)");
            st.execute("insert into " + d + "subscription_period (id, subscription_id, business_id, period_start, period_end, invoice_id, payment_id) values ('sp1', 's1', 'b1', '2030-01-01Z', '2030-02-01Z', 'i1', 'p1')");
            c.commit();
            c.setAutoCommit(true);
            c.createStatement().execute("set search_path to public");
        }
        List<String> tables = List.of("payment", "billing_invoice", "business_subscription", "subscription_period", "plan_price", "business", "users");
        Map<String, List<Map<String, Object>>> before = new java.util.HashMap<>();
        for (String t : tables) before.put(t, jdbc.queryForList("select * from " + d + t + " order by 1"));
        assertThat(flyway("23.4").migrate().migrationsExecuted).isEqualTo(1);
        for (String t : tables) assertThat(jdbc.queryForList("select * from " + d + t + " order by 1")).as(t).isEqualTo(before.get(t));
        flyway("23.4").validate();
    }

    @Test
    void anEmptyPaymentTableUpgradesAndRepeatedFlywayChangesNothing() throws Exception {
        atV23_3();
        assertThat(flyway("23.4").migrate().migrationsExecuted).isEqualTo(1);
        flyway("23.4").validate();
        assertThat(flyway("23.4").migrate().migrationsExecuted).isZero();
        flyway("23.4").validate();
    }

    static Stream<Arguments> incompatible() {
        return Stream.of(
                Arguments.of("CHARGE", "PENDING", "null", "null"),
                Arguments.of("CHARGE", "FAILED", "now()", WEBHOOK),
                Arguments.of("CHARGE", "SUCCEEDED", "null", WEBHOOK),
                Arguments.of("CHARGE", "SUCCEEDED", "now()", "null"),
                Arguments.of("CHARGE", "SUCCEEDED", "now()", "'TRUST_ME'"),
                Arguments.of("REFUND", "SUCCEEDED", "null", WEBHOOK),
                Arguments.of("REFUND", "SUCCEEDED", "now()", "'  '"),
                Arguments.of("CHARGEBACK", "SUCCEEDED", "now()", "null"));
    }

    @ParameterizedTest(name = "{0} {1} {2} {3}")
    @MethodSource("incompatible")
    void theMigrationRefusesIncompatibleRecordsAndRepairsNothing(String kind, String status, String verifiedAt, String verification) throws Exception {
        atV23_3();
        devPayment("CHARGE", "SUCCEEDED", "now()", WEBHOOK);
        devPayment(kind, status, verifiedAt, verification);
        List<Map<String, Object>> before = devPayments();
        assertThatThrownBy(() -> flyway("23.4").migrate()).hasMessageContaining("V23.4 refused: payments exist that are not verified records");
        assertThat(devPayments()).isEqualTo(before);
        assertThat(devRules()).isZero();
        assertThat(history()).endsWith("23.3").doesNotContain("23.4");
    }

    @Test
    void theMigrationIsAdditive() throws Exception {
        String sql = new ClassPathResource("db/migration/V23_4__payment_verified_records.sql").getContentAsString(StandardCharsets.UTF_8).toLowerCase();
        assertThat(sql).doesNotContain("delete from", "update payment", "drop ", "truncate", "disable trigger", "not valid");
    }

    @Test
    void nothingIsSeeded() {
        assertThat(jdbc.queryForObject("select count(*) from payment", Integer.class)).isZero();
    }
}
