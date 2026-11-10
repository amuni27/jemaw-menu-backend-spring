package com.agafari.com.migration;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AGF SUB-R4-01: V23.1. A violation test for every constraint. Amounts are fixtures, not prices (AD-10 is open). */
class BillingSchemaMigrationTest extends AbstractIntegrationTest {

    private static final String DEV = "v231_dev";
    private static final String FRESH = "v231_fresh";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    private User admin;
    private Tenant owner;
    private String price;

    @BeforeEach
    void setUp() {
        dropSchemas();
        admin = data.admin("billing-admin");
        owner = data.tenant("billing-owner");
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        price = UUID.randomUUID().toString();
        jdbc.update("insert into plan_price (id, plan_code, market, currency, billing_interval, amount_minor, version, valid_from) values (?, 'MODEL_4', 'ET', 'ETB', 'MONTH', 1000, 1, now())", price);
    }

    @AfterEach
    void dropSchemas() {
        jdbc.execute("drop schema if exists " + DEV + " cascade");
        jdbc.execute("drop schema if exists " + FRESH + " cascade");
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    // ---- billing_account

    private String account(String business, String market, String provider) {
        String id = id();
        jdbc.update("insert into billing_account (id, business_id, market, provider) values (?, ?, ?, ?)", id, business, market, provider);
        return id;
    }

    @Test
    void anAccountIsUniquePerRestaurantAndProvider() {
        account(owner.businessId(), "ET", "P1");
        assertThatThrownBy(() -> account(owner.businessId(), "ET", "P1")).hasMessageContaining("uk_billing_account_business_provider");
        assertThatCode(() -> account(owner.businessId(), "ET", "P2")).doesNotThrowAnyException();
        assertThatCode(() -> account(data.tenant("other").businessId(), "ET", "P1")).doesNotThrowAnyException();
    }

    @Test
    void anAccountsMarketMustBeTheRestaurantsCountry() {
        assertThatThrownBy(() -> account(owner.businessId(), "US", "P1")).hasMessageContaining("billing account market must be the restaurant's country");
        jdbc.update("update business set country = 'US' where id = ?", owner.businessId());
        assertThatCode(() -> account(owner.businessId(), "US", "P1")).doesNotThrowAnyException();
    }

    @Test
    void anAccountsMarketMustBeKnown() {
        jdbc.update("update business set country = 'GB' where id = ?", owner.businessId());
        assertThatThrownBy(() -> account(owner.businessId(), "GB", "P1")).hasMessageContaining("ck_billing_account_market");
    }

    @Test
    void anAccountsMarketCantChange() {
        String a = account(owner.businessId(), "ET", "P1");
        assertThatThrownBy(() -> jdbc.update("update billing_account set market = 'US' where id = ?", a)).hasMessageContaining("market and restaurant can't be changed");
    }

    @Test
    void anAccountsRestaurantCantChange() {
        String a = account(owner.businessId(), "ET", "P1");
        String other = data.tenant("other").businessId();
        assertThatThrownBy(() -> jdbc.update("update billing_account set business_id = ? where id = ?", other, a)).hasMessageContaining("market and restaurant can't be changed");
    }

    @Test
    void anAccountsOtherDetailsCanBeRecorded() {
        String a = account(owner.businessId(), "ET", "P1");
        assertThatCode(() -> jdbc.update("update billing_account set provider_customer_ref = 'c', billing_email = 'b@example.test' where id = ?", a)).doesNotThrowAnyException();
    }

    @Test
    void anAccountNeedsARealRestaurantAndAProvider() {
        assertThatThrownBy(() -> account("no-such", "ET", "P1")).hasMessageContaining("fk_billing_account_business");
        assertThatThrownBy(() -> account(owner.businessId(), "ET", null)).hasMessageContaining("provider");
        assertThatThrownBy(() -> jdbc.update("insert into billing_account (id, business_id, provider) values (?, ?, 'P1')", id(), owner.businessId())).hasMessageContaining("market");
    }

    // ---- checkout_session

    private String checkout(String business, String status, String key, String ref, boolean cancelRequested) {
        String id = id();
        jdbc.update("insert into checkout_session (id, business_id, owner_user_id, price_id, config_version, kind, provider, provider_session_ref, status, cancel_requested_at, idempotency_key, request_hash, expires_at, expected_amount_minor, expected_currency, created_at) "
                        + "values (?, ?, ?, ?, 1, 'NEW', 'P1', ?, ?, " + (cancelRequested ? "now()" : "null") + ", ?, 'h', now() + interval '24 hours', 1000, 'ETB', now())",
                id, business, owner.owner().getId(), price, ref, status, key);
        return id;
    }

    private String checkout(String status) {
        boolean cancel = status.equals("CANCEL_REQUESTED") || status.equals("CANCELLED");
        return checkout(owner.businessId(), status, id(), status.equals("OPEN") ? id() : null, cancel);
    }

    private String creating() {
        return checkout("CREATING");
    }

    /** One column set to a value; everything else valid. */
    private void checkoutWith(String column, String value) {
        Map<String, String> v = new java.util.LinkedHashMap<>();
        v.put("id", "'" + id() + "'");
        v.put("business_id", "'" + owner.businessId() + "'");
        v.put("owner_user_id", "'" + owner.owner().getId() + "'");
        v.put("price_id", "'" + price + "'");
        v.put("config_version", "1");
        v.put("kind", "'NEW'");
        v.put("provider", "'P1'");
        v.put("status", "'CREATING'");
        v.put("idempotency_key", "'" + id() + "'");
        v.put("request_hash", "'h'");
        v.put("expires_at", "now() + interval '24 hours'");
        v.put("expected_amount_minor", "1000");
        v.put("expected_currency", "'ETB'");
        v.put("created_at", "now()");
        v.put(column, value);
        jdbc.update("insert into checkout_session (" + String.join(", ", v.keySet()) + ") values (" + String.join(", ", v.values()) + ")");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATING", "OPEN", "CANCEL_REQUESTED", "COMPLETED", "EXPIRED", "CANCELLED", "CREATE_FAILED", "FAILED"})
    void everyAcceptedStateIsAllowed(String status) {
        assertThatCode(() -> checkout(status)).doesNotThrowAnyException();
    }

    @Test
    void anUnknownStateIsRefused() {
        assertThatThrownBy(() -> checkoutWith("status", "'NONSENSE'")).hasMessageContaining("ck_checkout_session_status");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NEW", "CONVERSION", "UPGRADE", "RENEWAL"})
    void everyAcceptedKindIsAllowed(String kind) {
        assertThatCode(() -> checkoutWith("kind", "'" + kind + "'")).doesNotThrowAnyException();
    }

    /**
     * AGF V23.5: DOWNGRADE_RENEWAL is still an accepted kind, but such a checkout must name its scheduled change (PO P2);
     * the accepted case is in {@code ScheduledChangeFundingMigrationTest}.
     */
    @Test
    void aDowngradeRenewalWithoutItsScheduledChangeIsRefused() {
        assertThatThrownBy(() -> checkoutWith("kind", "'DOWNGRADE_RENEWAL'")).hasMessageContaining("ck_checkout_session_change");
    }

    @Test
    void anUnknownKindIsRefused() {
        assertThatThrownBy(() -> checkoutWith("kind", "'NONSENSE'")).hasMessageContaining("ck_checkout_session_kind");
    }

    /** The database proof of the index behind NC-07; the concurrency mutation itself belongs to SUB-R4-05. */
    @Test
    void aRestaurantHasOneActiveCheckout() {
        creating();
        assertThatThrownBy(this::creating).hasMessageContaining("uk_checkout_session_active");
        assertThatThrownBy(() -> checkout("OPEN")).hasMessageContaining("uk_checkout_session_active");
    }

    @Test
    void twoOpenCheckoutsAreRefused() {
        checkout("OPEN");
        assertThatThrownBy(() -> checkout("OPEN")).hasMessageContaining("uk_checkout_session_active");
        assertThatThrownBy(this::creating).hasMessageContaining("uk_checkout_session_active");
    }

    @Test
    void finishedCheckoutsDoNotHoldTheSlot() {
        for (String s : List.of("CANCEL_REQUESTED", "COMPLETED", "EXPIRED", "CANCELLED", "CREATE_FAILED", "FAILED")) checkout(s);
        assertThatCode(this::creating).doesNotThrowAnyException();
    }

    @Test
    void endingACheckoutFreesTheSlot() {
        String first = creating();
        jdbc.update("update checkout_session set status = 'CREATE_FAILED', cancel_requested_at = now() where id = ?", first);
        assertThatCode(this::creating).doesNotThrowAnyException();
    }

    @Test
    void anotherRestaurantHasItsOwnSlotAndKeys() {
        checkout(owner.businessId(), "CREATING", "k", null, false);
        Tenant other = data.tenant("other");
        assertThatCode(() -> checkout(other.businessId(), "CREATING", "k", null, false)).doesNotThrowAnyException();
    }

    @Test
    void concurrentCheckoutsLeaveOneActive() throws Exception {
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        int won = 0;
        try {
            List<Future<String>> all = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                all.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return creating();
                }));
            }
            for (Future<String> f : all) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                    won++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).hasMessageContaining("uk_checkout_session_active");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(won).isEqualTo(1);
    }

    @Test
    void anIdempotencyKeyIsUniquePerRestaurant() {
        checkout(owner.businessId(), "EXPIRED", "same", null, false);
        assertThatThrownBy(() -> checkout(owner.businessId(), "COMPLETED", "same", null, false)).hasMessageContaining("uk_checkout_session_idempotency");
    }

    @Test
    void aRequestHashIsStoredForTheCallerToCompare() {
        String c = checkout(owner.businessId(), "EXPIRED", "k1", null, false);
        assertThat(jdbc.queryForObject("select request_hash from checkout_session where id = ?", String.class, c)).isEqualTo("h");
        assertThatThrownBy(() -> checkoutWith("request_hash", "null")).hasMessageContaining("request_hash");
    }

    @Test
    void anOpenCheckoutNeedsAProviderReference() {
        assertThatThrownBy(() -> checkout(owner.businessId(), "OPEN", "k", null, false)).hasMessageContaining("ck_checkout_session_open_ref");
        String c = creating();
        assertThatThrownBy(() -> jdbc.update("update checkout_session set status = 'OPEN' where id = ?", c)).hasMessageContaining("ck_checkout_session_open_ref");
        assertThatCode(() -> jdbc.update("update checkout_session set status = 'OPEN', provider_session_ref = 'r' where id = ?", c)).doesNotThrowAnyException();
    }

    @Test
    void aProviderReferenceIsUniquePerProvider() {
        checkout(owner.businessId(), "EXPIRED", "k1", "ref", false);
        assertThatThrownBy(() -> checkout(owner.businessId(), "EXPIRED", "k2", "ref", false)).hasMessageContaining("uk_checkout_session_provider_ref");
    }

    @Test
    void theSameReferenceFromAnotherProviderIsAllowed() {
        checkout(owner.businessId(), "EXPIRED", "k1", "ref", false);
        assertThatCode(() -> {
            checkoutWith("provider_session_ref", "'ref'");
        }).isInstanceOf(Exception.class).hasMessageContaining("uk_checkout_session_provider_ref");
        jdbc.update("update checkout_session set provider = 'P2' where idempotency_key = 'k1'");
        assertThatCode(() -> checkoutWith("provider_session_ref", "'ref'")).doesNotThrowAnyException();
    }

    @Test
    void manyCheckoutsCanHaveNoReference() {
        checkout("EXPIRED");
        assertThatCode(() -> checkout("EXPIRED")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCEL_REQUESTED", "CANCELLED"})
    void aCancellationNeedsItsTimestamp(String status) {
        assertThatThrownBy(() -> checkout(owner.businessId(), status, "k", null, false)).hasMessageContaining("ck_checkout_session_cancel");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATING", "COMPLETED", "EXPIRED", "FAILED"})
    void otherStatesCantHaveACancellationTimestamp(String status) {
        assertThatThrownBy(() -> checkout(owner.businessId(), status, "k", null, true)).hasMessageContaining("ck_checkout_session_cancel");
    }

    @Test
    void anOpenCheckoutCantHaveACancellationTimestamp() {
        assertThatThrownBy(() -> checkout(owner.businessId(), "OPEN", "k", "r", true)).hasMessageContaining("ck_checkout_session_cancel");
    }

    @Test
    void aFailedCreationMayCarryTheFailureTime() {
        assertThatCode(() -> checkout(owner.businessId(), "CREATE_FAILED", "k", null, true)).doesNotThrowAnyException();
    }

    @Test
    void aQuoteLastsAtMostTwentyFourHours() {
        assertThatCode(() -> checkoutWith("expires_at", "now() + interval '1 hour'")).doesNotThrowAnyException();
        jdbc.execute("update checkout_session set status = 'EXPIRED'");
        assertThatThrownBy(() -> checkoutWith("expires_at", "now() + interval '24 hours 1 second'")).hasMessageContaining("ck_checkout_session_expiry");
    }

    @Test
    void theExpectedAmountMustBePositive() {
        assertThatThrownBy(() -> checkoutWith("expected_amount_minor", "0")).hasMessageContaining("ck_checkout_session_amount");
        assertThatThrownBy(() -> checkoutWith("expected_amount_minor", "-1")).hasMessageContaining("ck_checkout_session_amount");
    }

    @Test
    void theExpectedCurrencyMustBeApproved() {
        assertThatThrownBy(() -> checkoutWith("expected_currency", "'EUR'")).hasMessageContaining("ck_checkout_session_currency");
    }

    @Test
    void everyReferenceMustExist() {
        assertThatThrownBy(() -> checkoutWith("business_id", "'no-such'")).hasMessageContaining("fk_checkout_session_business");
        assertThatThrownBy(() -> checkoutWith("owner_user_id", "'no-such'")).hasMessageContaining("fk_checkout_session_owner");
        assertThatThrownBy(() -> checkoutWith("price_id", "'no-such'")).hasMessageContaining("fk_checkout_session_price");
        assertThatThrownBy(() -> checkoutWith("config_version", "999")).hasMessageContaining("fk_checkout_session_config");
        assertThatThrownBy(() -> checkoutWith("replaces_subscription_id", "'no-such'")).hasMessageContaining("fk_checkout_session_replaces");
    }

    @ParameterizedTest
    @ValueSource(strings = {"business_id", "owner_user_id", "price_id", "config_version", "kind", "provider", "status", "idempotency_key", "request_hash", "expires_at", "expected_amount_minor", "expected_currency", "created_at"})
    void requiredColumnsAreRequired(String column) {
        assertThatThrownBy(() -> checkoutWith(column, "null")).hasMessageContaining(column);
    }

    @Test
    void aNewCheckoutHasRowVersionZero() {
        String c = creating();
        assertThat(jdbc.queryForObject("select row_version from checkout_session where id = ?", Integer.class, c)).isZero();
    }

    // ---- provider_event

    private String event(String provider, String eventId, String status) {
        String id = id();
        jdbc.update("insert into provider_event (id, provider, event_id, type, payload, status) values (?, ?, ?, 't', '{}'::jsonb, ?)", id, provider, eventId, status);
        return id;
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PROCESSED", "IGNORED", "FAILED", "QUARANTINED"})
    void everyAcceptedInboxStateIsAllowed(String status) {
        assertThatCode(() -> event("P1", id(), status)).doesNotThrowAnyException();
    }

    @Test
    void anUnknownInboxStateIsRefused() {
        assertThatThrownBy(() -> event("P1", "e", "NONSENSE")).hasMessageContaining("ck_provider_event_status");
    }

    @Test
    void anEventIsUniquePerProvider() {
        event("P1", "e1", "PENDING");
        assertThatThrownBy(() -> event("P1", "e1", "PENDING")).hasMessageContaining("uk_provider_event");
        assertThatCode(() -> event("P2", "e1", "PENDING")).doesNotThrowAnyException();
    }

    @Test
    void aReplayedEventIsANoOp() {
        String insert = "insert into provider_event (id, provider, event_id, type, payload) values (?, 'P1', 'e1', 't', '{}'::jsonb) on conflict (provider, event_id) do nothing";
        assertThat(jdbc.update(insert, id())).isEqualTo(1);
        assertThat(jdbc.update(insert, id())).isZero();
    }

    @Test
    void anEventStartsPendingWithNoAttempts() {
        jdbc.update("insert into provider_event (id, provider, event_id, type, payload) values ('x', 'P1', 'e', 't', '{}'::jsonb)");
        assertThat(jdbc.queryForMap("select status, attempts, received_at is not null as r, processed_at from provider_event where id = 'x'"))
                .containsEntry("status", "PENDING").containsEntry("attempts", 0).containsEntry("r", true).containsEntry("processed_at", null);
    }

    @Test
    void attemptsCantBeNegative() {
        String e = event("P1", "e", "PENDING");
        assertThatThrownBy(() -> jdbc.update("update provider_event set attempts = -1 where id = ?", e)).hasMessageContaining("ck_provider_event_attempts");
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider", "event_id", "type", "payload"})
    void anEventsRequiredColumnsAreRequired(String column) {
        Map<String, String> v = new java.util.LinkedHashMap<>(Map.of("id", "'" + id() + "'", "provider", "'P1'", "event_id", "'e'", "type", "'t'", "payload", "'{}'::jsonb"));
        v.put(column, "null");
        assertThatThrownBy(() -> jdbc.update("insert into provider_event (" + String.join(", ", v.keySet()) + ") values (" + String.join(", ", v.values()) + ")")).hasMessageContaining(column);
    }

    // ---- primary keys and the restaurant column

    @Test
    void identifiersAreUnique() {
        String a = account(owner.businessId(), "ET", "P1");
        assertThatThrownBy(() -> jdbc.update("insert into billing_account (id, business_id, market, provider) values (?, ?, 'ET', 'P2')", a, owner.businessId())).hasMessageContaining("pk_billing_account");
        String c = creating();
        jdbc.update("update checkout_session set status = 'EXPIRED' where id = ?", c);
        assertThatThrownBy(() -> checkoutWith("id", "'" + c + "'")).hasMessageContaining("pk_checkout_session");
        String e = event("P1", "e1", "PENDING");
        assertThatThrownBy(() -> jdbc.update("insert into provider_event (id, provider, event_id, type, payload) values (?, 'P1', 'e2', 't', '{}'::jsonb)", e)).hasMessageContaining("pk_provider_event");
    }

    @Test
    void anAccountNeedsARestaurant() {
        assertThatThrownBy(() -> account(null, "ET", "P1")).hasMessageContaining("business_id");
    }

    // ---- S1-08B

    private Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("1").target(MigrationVersion.fromVersion(target)).load();
    }

    private void legacyAtV22() throws Exception {
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
        flyway(DEV, "22").migrate();
    }

    private void seedDev() {
        String d = DEV + ".";
        jdbc.update("insert into " + d + "users (id, email, full_name, password_hash, role, status, \"createdAt\", \"updatedAt\") values ('u1', 'u1@example.test', 'x', 'x', 'VENUE', 'ACTIVE', now(), now())");
        jdbc.update("insert into " + d + "business (id, owner_user_id, name, business_phone, street_address, city, state, zipcode, custom_subdomain, currency, open24_7, \"createdAt\", \"updatedAt\") values ('b1', 'u1', 'B', '1', 'r', 'c', 's', '', 's1', 'ETB', true, now(), now())");
        jdbc.update("insert into " + d + "monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, created_at, created_by_user_id) values (1, 'SETTING_2', true, true, true, now(), now(), 'u1')");
        jdbc.update("insert into " + d + "plan_price (id, plan_code, market, currency, billing_interval, amount_minor, version, valid_from) values ('p1', 'MODEL_4', 'ET', 'ETB', 'MONTH', 1000, 1, now())");
        jdbc.update("insert into " + d + "business_subscription (id, business_id, plan_code, status, config_version, source, created_at, created_by_user_id, updated_at, updated_by_user_id, price_id, row_version) values ('s1', 'b1', 'MODEL_4', 'ACTIVE', 1, 'MANUAL', now(), 'u1', now(), 'u1', 'p1', 3)");
    }

    private List<Map<String, Object>> rows(String table) {
        return jdbc.queryForList("select * from " + DEV + "." + table + " order by 1");
    }

    @Test
    void anExistingDatabaseUpgradesFromV22KeepingEveryRow() throws Exception {
        legacyAtV22();
        seedDev();
        List<Map<String, Object>> subs = rows("business_subscription"), prices = rows("plan_price"), business = rows("business");
        assertThat(flyway(DEV, "23.1").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(rows("business_subscription")).isEqualTo(subs);
        assertThat(rows("plan_price")).isEqualTo(prices);
        assertThat(rows("business")).isEqualTo(business);
        flyway(DEV, "23.1").validate();
        assertThat(flyway(DEV, "23.1").migrate().migrationsExecuted).isZero();
        assertThat(rows("business_subscription")).isEqualTo(subs);
        assertThat(jdbc.queryForList("select version from " + DEV + ".flyway_schema_history where version is not null order by installed_rank", String.class)).endsWith("22", "23.1");
    }

    @Test
    void aFreshDatabaseReachesV23_1AndAgreesWithTheUpgradedSchema() throws Exception {
        legacyAtV22();
        flyway(DEV, "23.1").migrate();
        jdbc.execute("create schema " + FRESH);
        Flyway fresh = Flyway.configure().dataSource(dataSource).schemas(FRESH).locations("classpath:db/migration").target(MigrationVersion.fromVersion("23.1")).load();
        fresh.migrate();
        fresh.validate();
        assertThat(jdbc.queryForList("select version from " + FRESH + ".flyway_schema_history where version is not null order by installed_rank", String.class)).hasSize(23).endsWith("23.1");
        String columns = "select column_name, data_type, is_nullable, column_default, character_maximum_length from information_schema.columns where table_schema = ? and table_name = ? order by column_name";
        for (String table : List.of("billing_account", "checkout_session", "provider_event")) {
            assertThat(jdbc.queryForList(columns, FRESH, table)).as(table).isNotEmpty().isEqualTo(jdbc.queryForList(columns, DEV, table));
        }
    }

    @Test
    void v23_1ReferencesNoLaterTable() throws Exception {
        String sql = new ClassPathResource("db/migration/V23_1__billing_accounts_checkouts_provider_events.sql").getContentAsString(StandardCharsets.UTF_8).toLowerCase();
        assertThat(sql).doesNotContain("billing_invoice", "subscription_period", "billing_exception", "refund_request", "market_calendar", "legacy_publish_right", "references payment");
    }

    @Test
    void noRowsAreSeeded() {
        for (String table : List.of("billing_account", "checkout_session", "provider_event")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class)).as(table).isZero();
        }
    }
}
