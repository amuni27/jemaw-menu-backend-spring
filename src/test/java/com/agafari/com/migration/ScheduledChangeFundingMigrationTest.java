package com.agafari.com.migration;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

/**
 * AGF V23.5 (PO D3, P1, P2): a paid downgrade is funded by exactly one verified charge, through a checkout made for it, and is
 * APPLIED only with its exact resulting subscription, price, invoice and period. By SQL only. Amounts are fixtures.
 */
class ScheduledChangeFundingMigrationTest extends AbstractIntegrationTest {

    private static final String DEV = "v235_dev";
    private static final String START = "'2030-01-01Z'", BOUNDARY = "'2030-02-01Z'", NEXT = "'2030-03-01Z'";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactions;

    private Tenant owner, other;
    private String price4, price3, sub;

    private static String id() {
        return UUID.randomUUID().toString();
    }

    @BeforeEach
    void setUp() {
        dropSchema();
        owner = data.tenant("funding-owner");
        other = data.tenant("funding-other");
        String admin = data.admin("funding-admin").getId();
        config(jdbc, 1, "SETTING_2", true, true, true, admin);
        price4 = price("MODEL_4", 2500);
        price3 = price("MODEL_3", 1500);
        sub = paidSubscription(owner);
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("drop schema if exists " + DEV + " cascade");
    }

    private int version;

    private String price(String plan, long amount) {
        String pid = id();
        jdbc.update("insert into plan_price (id, plan_code, market, currency, billing_interval, amount_minor, version, active, valid_from) values (?, ?, 'ET', 'ETB', 'MONTH', ?, ?, true, now())",
                pid, plan, amount, ++version);
        return pid;
    }

    /** A PROVIDER MODEL_4 row with its evidenced period [START, BOUNDARY). */
    private String paidSubscription(Tenant t) {
        String s = id(), inv = id(), pay = id();
        tx(() -> {
            jdbc.update("insert into billing_invoice (id, business_id, price_id, period_start, period_end, amount_minor, currency, status) values (?, ?, ?, " + START + ", " + BOUNDARY + ", 2500, 'ETB', 'PAID')",
                    inv, t.businessId(), price4);
            jdbc.update("insert into payment (id, business_id, invoice_id, provider, provider_payment_ref, amount_minor, currency, kind, status, occurred_at, verified_at, verification) "
                    + "values (?, ?, ?, 'P1', ?, 2500, 'ETB', 'CHARGE', 'SUCCEEDED', " + START + ", now(), 'SIGNED_WEBHOOK+API_FETCH')", pay, t.businessId(), inv, id());
            jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, provider, price_id, created_at, created_by_user_id, "
                    + "updated_at, updated_by_user_id, row_version) values (?, ?, 'MODEL_4', 'ACTIVE', 1, " + BOUNDARY + ", 'PROVIDER', 'P1', ?, now(), ?, now(), ?, 0)",
                    s, t.businessId(), price4, t.owner().getId(), t.owner().getId());
            jdbc.update("insert into subscription_period (id, subscription_id, business_id, period_start, period_end, invoice_id, payment_id) values (?, ?, ?, " + START + ", " + BOUNDARY + ", ?, ?)",
                    id(), s, t.businessId(), inv, pay);
        });
        return s;
    }

    private void tx(Runnable r) {
        new TransactionTemplate(transactions).executeWithoutResult(st -> r.run());
    }

    private String change(Tenant t, String fromSub, String kind, String targetPlan, String targetPrice) {
        String c = id();
        jdbc.update("insert into scheduled_change (id, business_id, from_subscription_id, kind, target_plan_code, target_price_id, effective_at, status) values (?, ?, ?, ?, ?, ?, " + BOUNDARY + ", 'PENDING')",
                c, t.businessId(), fromSub, kind, targetPlan, targetPrice);
        return c;
    }

    private String paidChange() {
        return change(owner, sub, "DOWNGRADE", "MODEL_3", price3);
    }

    private String checkout(Tenant t, String kind, String changeId, String replaces, String priceId, String status) {
        String c = id();
        jdbc.update("insert into checkout_session (id, business_id, owner_user_id, price_id, config_version, kind, replaces_subscription_id, provider, provider_session_ref, status, "
                        + "cancel_requested_at, idempotency_key, request_hash, expires_at, expected_amount_minor, expected_currency, created_at, scheduled_change_id) "
                        + "values (?, ?, ?, ?, 1, ?, ?, 'P1', ?, ?, " + ("CANCEL_REQUESTED".equals(status) ? "now()" : "null") + ", ?, 'h', now() + interval '1 hour', 1500, 'ETB', now(), ?)",
                c, t.businessId(), t.owner().getId(), priceId, kind, replaces, "sess_" + c, status, id(), changeId);
        return c;
    }

    private String checkoutFor(String changeId) {
        return checkout(owner, "DOWNGRADE_RENEWAL", changeId, sub, price3, "OPEN");
    }

    /** {invoice, payment} for the target's first period, paid through {@code checkoutId}. */
    private String[] funding(String checkoutId, String priceId, long amount, String periodStart, String invoiceStatus, String business, String provider) {
        String inv = id(), pay = id();
        jdbc.update("insert into billing_invoice (id, business_id, checkout_session_id, price_id, period_start, period_end, amount_minor, currency, status) values (?, ?, ?, ?, "
                + periodStart + ", " + NEXT + ", ?, 'ETB', '" + invoiceStatus + "')", inv, business, checkoutId, priceId, amount);
        jdbc.update("insert into payment (id, business_id, invoice_id, checkout_session_id, provider, provider_payment_ref, amount_minor, currency, kind, status, occurred_at, verified_at, verification) "
                + "values (?, ?, ?, ?, ?, ?, ?, 'ETB', 'CHARGE', 'SUCCEEDED', '2030-01-25Z', now(), 'SIGNED_WEBHOOK+API_FETCH')", pay, business, inv, checkoutId, provider, id(), amount);
        return new String[]{inv, pay};
    }

    private String[] funding(String checkoutId) {
        return funding(checkoutId, price3, 1500, BOUNDARY, "PAID", owner.businessId(), "P1");
    }

    private int fund(String changeId, String[] f) {
        return jdbc.update("update scheduled_change set funding_invoice_id = ?, funding_payment_id = ?, funded_at = now() where id = ?", f[0], f[1], changeId);
    }

    // ---- funding (C3–C9) ----

    @Test
    void theExactChargeThroughTheChangesCheckoutFundsIt() {
        String c = paidChange();
        String[] f = funding(checkoutFor(c));

        assertThat(fund(c, f)).isEqualTo(1);

        assertThat(jdbc.queryForMap("select funding_payment_id, funding_invoice_id from scheduled_change where id = ?", c))
                .containsEntry("funding_payment_id", f[1]).containsEntry("funding_invoice_id", f[0]);
    }

    @Test
    void aChangeStartsUnfundedAndUnapplied() {
        String[] f = funding(checkoutFor(paidChange()));
        assertThatThrownBy(() -> jdbc.update("insert into scheduled_change (id, business_id, from_subscription_id, kind, target_plan_code, target_price_id, effective_at, status, "
                + "funding_payment_id, funding_invoice_id, funded_at) values (?, ?, ?, 'DOWNGRADE', 'MODEL_3', ?, " + BOUNDARY + ", 'PENDING', ?, ?, now())", id(), owner.businessId(), sub, price3, f[1], f[0]))
                .hasMessageContaining("starts unfunded");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"FAILED", "CANCELLED", "SUPERSEDED"})
    void onlyAnOpenChangeCanBeFunded(String status) {
        String c = paidChange();
        String[] f = funding(checkoutFor(c));
        jdbc.update("update scheduled_change set status = ? where id = ?", status, c);

        assertThatThrownBy(() -> fund(c, f)).hasMessageContaining("only an open scheduled change can be funded");
    }

    /** C5: a free downgrade or a cancellation can't take a charge, even the restaurant's own (the funding trigger refuses first). */
    @Test
    void anUnpaidChangeCantBeFunded() {
        String c = paidChange();
        String[] f = funding(checkoutFor(c));
        jdbc.update("update scheduled_change set status = 'SUPERSEDED' where id = ?", c);
        String free = change(owner, sub, "DOWNGRADE", "MODEL_2", null);

        assertThatThrownBy(() -> fund(free, f)).isNotNull();
        assertThat(jdbc.queryForObject("select funding_payment_id from scheduled_change where id = ?", String.class, free)).isNull();
        assertThat(jdbc.queryForObject("select pg_get_constraintdef(oid) from pg_constraint where conname = 'ck_scheduled_change_funding_paid'", String.class)).contains("DOWNGRADE");
    }

    @Test
    void fundingIsAllOrNothing() {
        String c = paidChange();
        String[] f = funding(checkoutFor(c));
        assertThatThrownBy(() -> jdbc.update("update scheduled_change set funding_payment_id = ? where id = ?", f[1], c)).hasMessageContaining("ck_scheduled_change_funding_complete");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"another-restaurants-payment", "another-provider", "invoice-not-paid", "other-price", "wrong-amount", "wrong-boundary", "refund-not-charge",
            "payment-of-another-invoice", "checkout-of-another-change"})
    void onlyTheExactVerifiedChargeAndPaidInvoiceFundIt(String variant) {
        String c = paidChange();
        String co = checkoutFor(c);
        String[] f = switch (variant) {
            case "another-restaurants-payment" -> {
                String otherSub = paidSubscription(other);
                String oc = checkout(other, "DOWNGRADE_RENEWAL", change(other, otherSub, "DOWNGRADE", "MODEL_3", price3), otherSub, price3, "OPEN");
                yield funding(oc, price3, 1500, BOUNDARY, "PAID", other.businessId(), "P1");
            }
            case "another-provider" -> funding(co, price3, 1500, BOUNDARY, "PAID", owner.businessId(), "P2");
            case "invoice-not-paid" -> funding(co, price3, 1500, BOUNDARY, "OPEN", owner.businessId(), "P1");
            case "other-price" -> funding(co, price("MODEL_3", 1400), 1400, BOUNDARY, "PAID", owner.businessId(), "P1");
            case "wrong-amount" -> funding(co, price3, 1499, BOUNDARY, "PAID", owner.businessId(), "P1");
            case "wrong-boundary" -> funding(co, price3, 1500, "'2030-01-31Z'", "PAID", owner.businessId(), "P1");
            case "refund-not-charge" -> {
                String[] ok = funding(co);
                String refund = id();
                jdbc.update("insert into payment (id, business_id, invoice_id, checkout_session_id, provider, provider_payment_ref, amount_minor, currency, kind, status, occurred_at, verified_at, verification) "
                        + "values (?, ?, ?, ?, 'P1', ?, 1500, 'ETB', 'REFUND', 'SUCCEEDED', now(), now(), 'MANUAL')", refund, owner.businessId(), ok[0], co, id());
                yield new String[]{ok[0], refund};
            }
            case "payment-of-another-invoice" -> {
                String[] a = funding(co);
                String[] b = funding(co);
                yield new String[]{a[0], b[1]};
            }
            default -> {
                // the checkout belongs to an earlier change that was cancelled; a new change targets the same row and price
                jdbc.update("update checkout_session set status = 'EXPIRED' where id = ?", co);
                String[] old = funding(co);
                jdbc.update("update scheduled_change set status = 'CANCELLED' where id = ?", c);
                String fresh = change(owner, sub, "DOWNGRADE", "MODEL_3", price3);
                assertThatThrownBy(() -> fund(fresh, old)).hasMessageContaining("checkout made for this scheduled change");
                yield null;
            }
        };
        if (f == null) return;

        assertThatThrownBy(() -> fund(c, f)).isNotNull();
        assertThat(jdbc.queryForObject("select funding_payment_id from scheduled_change where id = ?", String.class, c)).isNull();
    }

    @Test
    void aPaymentFundsAtMostOneChange() {
        String c = paidChange();
        String[] f = funding(checkoutFor(c));
        fund(c, f);
        String otherSub = paidSubscription(other);
        String c2 = change(other, otherSub, "DOWNGRADE", "MODEL_3", price3);

        assertThatThrownBy(() -> fund(c2, f)).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from scheduled_change where funding_payment_id = ?", Integer.class, f[1])).isEqualTo(1);
    }

    @Test
    void aFundingAssociationNeverChangesClearsOrDisappears() {
        String c = paidChange();
        String co = checkoutFor(c);
        String[] f = funding(co);
        fund(c, f);

        assertThatThrownBy(() -> jdbc.update("update scheduled_change set funding_payment_id = null, funding_invoice_id = null, funded_at = null where id = ?", c))
                .hasMessageContaining("can't be changed or removed");
        assertThatThrownBy(() -> jdbc.update("update scheduled_change set funded_at = now() + interval '1 day' where id = ?", c)).hasMessageContaining("can't be changed or removed");
        assertThatThrownBy(() -> jdbc.update("update scheduled_change set effective_at = effective_at + interval '1 day' where id = ?", c)).hasMessageContaining("can't be retargeted");
        assertThatThrownBy(() -> jdbc.update("delete from scheduled_change where id = ?", c)).hasMessageContaining("can't be removed");
        jdbc.update("update scheduled_change set status = 'SUPERSEDED' where id = ?", c);
        assertThat(jdbc.queryForObject("select funding_payment_id from scheduled_change where id = ?", String.class, c)).as("kept as evidence").isEqualTo(f[1]);
    }

    // ---- checkouts (P2) ----

    @Test
    void aDowngradeRenewalCheckoutNamesItsChangeAndNoOtherKindDoes() {
        String c = paidChange();
        assertThatThrownBy(() -> checkout(owner, "DOWNGRADE_RENEWAL", null, sub, price3, "OPEN")).hasMessageContaining("ck_checkout_session_change");
        assertThatThrownBy(() -> checkout(owner, "RENEWAL", c, sub, price3, "OPEN")).hasMessageContaining("ck_checkout_session_change");
        assertThatCode(() -> checkoutFor(c)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"other-restaurant", "other-row", "other-price", "funded-change", "closed-change"})
    void aCheckoutMustMatchAnOpenUnfundedChange(String variant) {
        String c = paidChange();
        switch (variant) {
            case "other-restaurant" -> assertThatThrownBy(() -> checkout(other, "DOWNGRADE_RENEWAL", c, sub, price3, "OPEN")).isNotNull();
            case "other-row" -> assertThatThrownBy(() -> checkout(owner, "DOWNGRADE_RENEWAL", c, null, price3, "OPEN")).hasMessageContaining("must match its scheduled change");
            case "other-price" -> assertThatThrownBy(() -> checkout(owner, "DOWNGRADE_RENEWAL", c, sub, price4, "OPEN")).hasMessageContaining("must match its scheduled change");
            case "funded-change" -> {
                String co = checkoutFor(c);
                fund(c, funding(co));
                jdbc.update("update checkout_session set status = 'COMPLETED' where id = ?", co);
                assertThatThrownBy(() -> checkoutFor(c)).hasMessageContaining("open, unfunded");
            }
            default -> {
                jdbc.update("update scheduled_change set status = 'CANCELLED' where id = ?", c);
                assertThatThrownBy(() -> checkoutFor(c)).hasMessageContaining("open, unfunded");
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"CREATING", "OPEN", "CANCEL_REQUESTED"})
    void oneCheckoutPerChangeWhileItCanStillBePaid(String firstStatus) {
        String c = paidChange();
        checkout(owner, "DOWNGRADE_RENEWAL", c, sub, price3, firstStatus);
        assertThatThrownBy(() -> checkout(owner, "DOWNGRADE_RENEWAL", c, sub, price3, "CANCEL_REQUESTED")).hasMessageContaining("uk_checkout_session_change_payable");
    }

    @Test
    void anExpiredOrCancelledCheckoutMakesRoomForANewOne() {
        String c = paidChange();
        String first = checkoutFor(c);
        jdbc.update("update checkout_session set status = 'EXPIRED' where id = ?", first);
        assertThatCode(() -> checkoutFor(c)).doesNotThrowAnyException();
        assertThatThrownBy(() -> jdbc.update("update checkout_session set scheduled_change_id = null, kind = 'RENEWAL' where id = ?", first)).hasMessageContaining("can't be changed");
    }

    // ---- APPLIED (C10, C11) ----

    @Test
    void anAppliedPaidChangeNamesItsExactResultingSubscriptionAndPeriod() {
        String c = paidChange();
        String[] f = funding(checkoutFor(c));
        fund(c, f);
        String target = id();

        tx(() -> applyAs(c, target, f, price3, "MODEL_3"));

        assertThat(jdbc.queryForObject("select applied_subscription_id from scheduled_change where id = ?", String.class, c)).isEqualTo(target);
    }

    private void applyAs(String changeId, String target, String[] f, String targetPrice, String targetPlan) {
        jdbc.update("update business_subscription set status = 'EXPIRED', ended_at = " + BOUNDARY + ", ended_reason = 'CHANGED', replaced_by_id = ?, updated_at = now() where id = ?", target, sub);
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, provider, price_id, created_at, created_by_user_id, "
                + "updated_at, updated_by_user_id, row_version) values (?, ?, ?, 'ACTIVE', 1, " + NEXT + ", 'PROVIDER', 'P1', ?, now(), ?, now(), ?, 0)",
                target, owner.businessId(), targetPlan, targetPrice, owner.owner().getId(), owner.owner().getId());
        jdbc.update("insert into subscription_period (id, subscription_id, business_id, period_start, period_end, invoice_id, payment_id) values (?, ?, ?, " + BOUNDARY + ", " + NEXT + ", ?, ?)",
                id(), target, owner.businessId(), f[0], f[1]);
        jdbc.update("update scheduled_change set status = 'APPLIED', applied_subscription_id = ? where id = ?", target, changeId);
    }

    @Test
    void aPaidChangeCantBeAppliedWithoutFundingOrWithoutItsResultingSubscription() {
        String c = paidChange();
        assertThatThrownBy(() -> jdbc.update("update scheduled_change set status = 'APPLIED' where id = ?", c)).hasMessageContaining("ck_scheduled_change_applied");
        String[] f = funding(checkoutFor(c));
        fund(c, f);
        assertThatThrownBy(() -> jdbc.update("update scheduled_change set status = 'APPLIED' where id = ?", c)).hasMessageContaining("ck_scheduled_change_applied");
        assertThatThrownBy(() -> jdbc.update("update scheduled_change set applied_subscription_id = ? where id = ?", sub, c)).hasMessageContaining("ck_scheduled_change_applied");
    }

    @Test
    void aReplacementChainAloneIsNotEnoughTheExactPeriodIsRequired() {
        String c = paidChange();
        String[] f = funding(checkoutFor(c));
        fund(c, f);

        // the resulting row exists and replaces the old one, but names a different price and has none of the funding
        assertThatThrownBy(() -> tx(() -> {
            String target = id();
            jdbc.update("update business_subscription set status = 'EXPIRED', ended_at = " + BOUNDARY + ", ended_reason = 'CHANGED', replaced_by_id = ?, updated_at = now() where id = ?", target, sub);
            jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, created_by_user_id, updated_at, updated_by_user_id, row_version) "
                    + "values (?, ?, 'MODEL_3', 'ACTIVE', 1, " + NEXT + ", 'MANUAL', now(), ?, now(), ?, 0)", target, owner.businessId(), owner.owner().getId(), owner.owner().getId());
            jdbc.update("update scheduled_change set status = 'APPLIED', applied_subscription_id = ? where id = ?", target, c);
        })).rootCause().hasMessageContaining("exact funded period"); // refused at commit (deferred)
        assertThat(jdbc.queryForObject("select status from scheduled_change where id = ?", String.class, c)).isEqualTo("PENDING");
    }

    @Test
    void anAppliedChangesResultCantBeRedirected() {
        String c = paidChange();
        String[] f = funding(checkoutFor(c));
        fund(c, f);
        String target = id();
        tx(() -> applyAs(c, target, f, price3, "MODEL_3"));

        assertThatThrownBy(() -> jdbc.update("update scheduled_change set applied_subscription_id = ? where id = ?", sub, c)).hasMessageContaining("can't be changed");
    }

    @Test
    void freeChangesAndCancellationsBehaveAsBefore() {
        String freeDowngrade = change(owner, sub, "DOWNGRADE", "MODEL_2", null);
        assertThatCode(() -> jdbc.update("update scheduled_change set status = 'APPLIED' where id = ?", freeDowngrade)).doesNotThrowAnyException();
        String cancel = change(other, paidSubscription(other), "CANCEL", null, null);
        assertThatCode(() -> jdbc.update("update scheduled_change set status = 'CANCELLED' where id = ?", cancel)).doesNotThrowAnyException();
    }

    // ---- migration ----

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).schemas(DEV).locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("1").target(MigrationVersion.fromVersion(target)).load();
    }

    private void atV23_4() throws Exception {
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
        flyway("23.4").migrate();
        String d = DEV + ".";
        jdbc.update("insert into " + d + "users (id, email, full_name, password_hash, role, status, \"createdAt\", \"updatedAt\") values ('u1', 'u1@example.test', 'x', 'x', 'VENUE', 'ACTIVE', now(), now())");
        jdbc.update("insert into " + d + "business (id, owner_user_id, name, business_phone, street_address, city, state, zipcode, custom_subdomain, currency, open24_7, \"createdAt\", \"updatedAt\") values ('b1', 'u1', 'B', '1', 'r', 'c', 's', '', 's1', 'ETB', true, now(), now())");
        jdbc.update("insert into " + d + "monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, created_at, created_by_user_id) values (1, 'SETTING_2', true, true, true, now(), now(), 'u1')");
        jdbc.update("insert into " + d + "plan_price (id, plan_code, market, currency, billing_interval, amount_minor, version, valid_from) values ('pr1', 'MODEL_4', 'ET', 'ETB', 'MONTH', 1000, 1, now())");
        jdbc.update("insert into " + d + "business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, created_by_user_id, updated_at, updated_by_user_id, row_version) "
                + "values ('s1', 'b1', 'MODEL_4', 'ACTIVE', 1, now() + interval '10 days', 'MANUAL', now(), 'u1', now(), 'u1', 0)");
    }

    private void devChange(String id, String kind, String target, String status) {
        jdbc.update("insert into " + DEV + ".scheduled_change (id, business_id, from_subscription_id, kind, target_plan_code, effective_at, status) values (?, 'b1', 's1', ?, ?, now() + interval '10 days', ?)",
                id, kind, target, status);
    }

    private void devCheckout(String id, String kind, String status) {
        jdbc.update("insert into " + DEV + ".checkout_session (id, business_id, owner_user_id, price_id, config_version, kind, replaces_subscription_id, provider, status, idempotency_key, request_hash, "
                + "expires_at, expected_amount_minor, expected_currency, created_at) values (?, 'b1', 'u1', 'pr1', 1, ?, 's1', 'P1', ?, ?, 'h', now() + interval '1 hour', 1000, 'ETB', now())",
                id, kind, status, id);
    }

    private List<Map<String, Object>> devRows(String table, String columns) {
        return jdbc.queryForList("select " + columns + " from " + DEV + "." + table + " order by id");
    }

    private List<String> history() {
        return jdbc.queryForList("select version from " + DEV + ".flyway_schema_history where version is not null and success order by installed_rank", String.class);
    }

    @Test
    void aV23_4DatabaseUpgradesKeepingEveryRow() throws Exception {
        atV23_4();
        devChange("c1", "DOWNGRADE", "MODEL_2", "APPLIED");
        devChange("c2", "CANCEL", null, "CANCELLED");
        devCheckout("k1", "NEW", "EXPIRED");
        devCheckout("k2", "RENEWAL", "COMPLETED");
        String changeCols = "id, business_id, from_subscription_id, kind, target_plan_code, target_price_id, effective_at, status, provider_schedule_ref";
        String checkoutCols = "id, business_id, kind, status, price_id, replaces_subscription_id, provider";
        List<Map<String, Object>> changes = devRows("scheduled_change", changeCols), checkouts = devRows("checkout_session", checkoutCols);

        assertThat(flyway("23.5").migrate().migrationsExecuted).isEqualTo(1);

        assertThat(devRows("scheduled_change", changeCols)).isEqualTo(changes);
        assertThat(devRows("checkout_session", checkoutCols)).isEqualTo(checkouts);
        assertThat(jdbc.queryForObject("select count(*) from " + DEV + ".scheduled_change where funding_payment_id is null and applied_subscription_id is null and created_at is not null", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from " + DEV + ".checkout_session where scheduled_change_id is null", Integer.class)).isEqualTo(2);
        assertThat(history()).endsWith("23.4", "23.5");
    }

    @Test
    void repeatedFlywayChangesNothing() throws Exception {
        atV23_4();
        assertThat(flyway("23.5").migrate().migrationsExecuted).isEqualTo(1);
        flyway("23.5").validate();
        assertThat(flyway("23.5").migrate().migrationsExecuted).isZero();
        flyway("23.5").validate();
    }

    @Test
    void theMigrationRefusesAnUnlinkableDowngradeRenewalCheckoutAndRepairsNothing() throws Exception {
        atV23_4();
        devCheckout("k1", "DOWNGRADE_RENEWAL", "EXPIRED");
        List<Map<String, Object>> before = devRows("checkout_session", "*");

        assertThatThrownBy(() -> flyway("23.5").migrate()).hasMessageContaining("V23.5 refused: downgrade-renewal checkouts exist without a scheduled change");

        assertThat(devRows("checkout_session", "*")).isEqualTo(before);
        assertThat(history()).endsWith("23.4").doesNotContain("23.5");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema = ? and table_name = 'scheduled_change' and column_name = 'funding_payment_id'",
                Integer.class, DEV)).isZero();
    }

    @Test
    void theMigrationIsAdditive() throws Exception {
        String sql = new ClassPathResource("db/migration/V23_5__scheduled_change_funding.sql").getContentAsString(StandardCharsets.UTF_8).toLowerCase();
        assertThat(sql).doesNotContain("delete from", "update checkout_session", "update scheduled_change", "drop ", "truncate", "disable trigger", "not valid");
    }
}
