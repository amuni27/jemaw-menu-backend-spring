package com.agafari.com.migration;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AGF SUB-R3-01: V22. Amounts here are fixtures, not prices (AD-10 is open). */
class PlanPriceMigrationTest extends AbstractIntegrationTest {

    private static final String DEV = "v22_dev";
    private static final String FRESH = "v22_fresh";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    private User admin;

    @BeforeEach
    void setUp() {
        dropSchemas();
        admin = data.admin("price-admin");
    }

    @AfterEach
    void dropSchemas() {
        jdbc.execute("drop schema if exists " + DEV + " cascade");
        jdbc.execute("drop schema if exists " + FRESH + " cascade");
    }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("1").target(MigrationVersion.fromVersion(target)).load();
    }

    private void legacyAtV21() throws Exception {
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
        flyway(DEV, "21").migrate();
    }

    private void seedDev() {
        String d = DEV + ".";
        jdbc.update("insert into " + d + "users (id, email, full_name, password_hash, role, status, \"createdAt\", \"updatedAt\") values ('u1', 'u1@example.test', 'x', 'x', 'VENUE', 'ACTIVE', now(), now())");
        jdbc.update("insert into " + d + "business (id, owner_user_id, name, business_phone, street_address, city, state, zipcode, custom_subdomain, currency, open24_7, \"createdAt\", \"updatedAt\") values ('b1', 'u1', 'B', '1', 'r', 'c', 's', '', 's1', 'ETB', true, now(), now())");
        jdbc.update("insert into " + d + "monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, created_at, created_by_user_id) values (1, 'SETTING_2', true, true, true, now(), now(), 'u1')");
        jdbc.update("insert into " + d + "business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, created_by_user_id, updated_at, updated_by_user_id, row_version) values ('s1', 'b1', 'MODEL_4', 'ACTIVE', 1, now() + interval '30 days', 'MANUAL', now(), 'u1', now(), 'u1', 3)");
        jdbc.update("insert into " + d + "scheduled_change (id, business_id, from_subscription_id, kind, effective_at, status) values ('c1', 'b1', 's1', 'CANCEL', now() + interval '30 days', 'PENDING')");
        jdbc.update("insert into " + d + "job (id, type, key, payload, payload_hash) values ('j1', 't', 'k', '{}'::jsonb, 'h')");
    }

    private List<Map<String, Object>> devRows(String table) {
        return jdbc.queryForList("select * from " + DEV + "." + table + " order by 1");
    }

    private String price(String plan, String market, String currency, String interval, long amount, int version) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into plan_price (id, plan_code, market, currency, billing_interval, amount_minor, version, active, valid_from) values (?, ?, ?, ?, ?, ?, ?, false, now())",
                id, plan, market, currency, interval, amount, version);
        return id;
    }

    private String price() {
        return price("MODEL_4", "US", "USD", "MONTH", 1000, 1);
    }

    @Test
    void anExistingDatabaseUpgradesFromV21KeepingEveryRow() throws Exception {
        legacyAtV21();
        seedDev();
        List<Map<String, Object>> subs = devRows("business_subscription"), changes = devRows("scheduled_change"), jobs = devRows("job");
        assertThat(flyway(DEV, "22").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(devRows("business_subscription")).isEqualTo(subs);
        assertThat(devRows("scheduled_change")).isEqualTo(changes);
        assertThat(devRows("job")).isEqualTo(jobs);
        flyway(DEV, "22").validate();
        assertThat(flyway(DEV, "22").migrate().migrationsExecuted).isZero();
        assertThat(devRows("business_subscription")).isEqualTo(subs);
    }

    @Test
    void aFreshDatabaseReachesV22AndAgreesWithTheUpgradedSchema() throws Exception {
        legacyAtV21();
        flyway(DEV, "22").migrate();
        jdbc.execute("create schema " + FRESH);
        Flyway fresh = Flyway.configure().dataSource(dataSource).schemas(FRESH).locations("classpath:db/migration").target(MigrationVersion.fromVersion("22")).load();
        fresh.migrate();
        fresh.validate();
        assertThat(jdbc.queryForList("select version from " + FRESH + ".flyway_schema_history where version is not null order by installed_rank", String.class)).hasSize(22).endsWith("22");
        String columns = "select column_name, data_type, is_nullable, column_default, character_maximum_length from information_schema.columns where table_schema = ? and table_name = 'plan_price' order by column_name";
        assertThat(jdbc.queryForList(columns, FRESH)).isNotEmpty().isEqualTo(jdbc.queryForList(columns, DEV));
    }

    @Test
    void noPriceIsSeeded() {
        assertThat(jdbc.queryForObject("select count(*) from plan_price", Integer.class)).isZero();
    }

    @Test
    void v22ReferencesNoLaterTable() throws Exception {
        String sql = new ClassPathResource("db/migration/V22__plan_prices.sql").getContentAsString(StandardCharsets.UTF_8).toLowerCase();
        assertThat(sql).doesNotContain("subscription_period", "billing_invoice", "checkout_session", "payment ", "legacy_publish_right");
    }

    // ---- AC1: append-only

    @Test
    void aPriceCantBeUpdated() {
        String id = price();
        assertThatThrownBy(() -> jdbc.update("update plan_price set amount_minor = 1 where id = ?", id)).hasMessageContaining("prices can't be changed");
        assertThatThrownBy(() -> jdbc.update("update plan_price set active = true where id = ?", id)).hasMessageContaining("prices can't be changed");
        assertThat(jdbc.queryForObject("select amount_minor from plan_price where id = ?", Long.class, id)).isEqualTo(1000);
    }

    @Test
    void aPriceCantBeDeleted() {
        String id = price();
        assertThatThrownBy(() -> jdbc.update("delete from plan_price where id = ?", id)).hasMessageContaining("prices can't be changed");
    }

    @Test
    void aNewVersionIsAllowed() {
        price();
        assertThatCode(() -> price("MODEL_4", "US", "USD", "MONTH", 2000, 2)).doesNotThrowAnyException();
    }

    // ---- constraints

    @Test
    void aVersionIsUniquePerPlanMarketAndInterval() {
        price();
        assertThatThrownBy(this::price).hasMessageContaining("uk_plan_price_version");
        assertThatCode(() -> price("MODEL_3", "US", "USD", "MONTH", 1000, 1)).doesNotThrowAnyException();
        assertThatCode(() -> price("MODEL_4", "ET", "ETB", "MONTH", 1000, 1)).doesNotThrowAnyException();
        assertThatCode(() -> price("MODEL_4", "US", "USD", "YEAR", 1000, 1)).doesNotThrowAnyException();
    }

    @Test
    void marketAndCurrencyMustBeTheApprovedPair() {
        assertThatThrownBy(() -> price("MODEL_4", "US", "ETB", "MONTH", 1000, 1)).hasMessageContaining("ck_plan_price_market_currency");
        assertThatThrownBy(() -> price("MODEL_4", "ET", "USD", "MONTH", 1000, 1)).hasMessageContaining("ck_plan_price_market_currency");
        assertThatThrownBy(() -> price("MODEL_4", "GB", "USD", "MONTH", 1000, 1)).hasMessageContaining("ck_plan_price_market_currency");
        assertThatThrownBy(() -> price("MODEL_4", "US", "EUR", "MONTH", 1000, 1)).hasMessageContaining("ck_plan_price_market_currency");
    }

    @Test
    void theIntervalIsChecked() {
        assertThatThrownBy(() -> price("MODEL_4", "US", "USD", "WEEK", 1000, 1)).hasMessageContaining("ck_plan_price_interval");
    }

    @Test
    void anAmountMustBePositive() {
        assertThatThrownBy(() -> price("MODEL_4", "US", "USD", "MONTH", 0, 1)).hasMessageContaining("ck_plan_price_amount");
        assertThatThrownBy(() -> price("MODEL_4", "US", "USD", "MONTH", -1, 1)).hasMessageContaining("ck_plan_price_amount");
    }

    @Test
    void aVersionMustBePositive() {
        assertThatThrownBy(() -> price("MODEL_4", "US", "USD", "MONTH", 1000, 0)).hasMessageContaining("ck_plan_price_version");
    }

    @Test
    void aPlanMustExist() {
        assertThatThrownBy(() -> price("NO_PLAN", "US", "USD", "MONTH", 1000, 1)).hasMessageContaining("fk_plan_price_plan");
    }

    // ---- business_subscription.price_id

    private String subscription(String priceId) {
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        Tenant t = data.tenant("price-sub");
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, source, created_at, created_by_user_id, updated_at, updated_by_user_id, price_id, row_version) values (?, ?, 'MODEL_4', 'ACTIVE', 1, 'MANUAL', now(), 's', now(), 's', ?, 0)", id, t.businessId(), priceId);
        return id;
    }

    @Test
    void aSubscriptionPriceMustExist() {
        assertThatThrownBy(() -> subscription("no-such-price")).hasMessageContaining("fk_business_subscription_price");
    }

    @Test
    void aSubscriptionCanReferenceAPriceOrNone() {
        assertThatCode(() -> subscription(null)).doesNotThrowAnyException();
    }

    @Test
    void aSubscriptionReferencingAPriceIsAccepted() {
        String p = price();
        String id = subscription(p);
        assertThat(jdbc.queryForObject("select price_id from business_subscription where id = ?", String.class, id)).isEqualTo(p);
    }

    @Test
    void theExistingPriceImmutabilityStillHolds() {
        String id = subscription(price());
        String other = price("MODEL_4", "US", "USD", "MONTH", 2000, 2);
        assertThatThrownBy(() -> jdbc.update("update business_subscription set price_id = ? where id = ?", other, id)).hasMessageContaining("can't be changed");
    }
}
