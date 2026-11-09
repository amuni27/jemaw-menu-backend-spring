package com.agafari.com.migration;

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
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGF SUB-R2-08: V21 creates {@code job} and {@code notification}, once. The job runner isn't part of this delivery, so
 * the claim and fencing statements here are the ones DD §9.2 specifies, proving the schema supports them.
 */
class JobNotificationMigrationTest extends AbstractIntegrationTest {

    private static final String DEV = "v21_dev";
    private static final String FRESH = "v21_fresh";
    private static final String CLAIM = "update job set status = 'RUNNING', claims = claims + 1, claim_token = ?, lease_until = now() + interval '10 min', "
            + "first_attempt_at = coalesce(first_attempt_at, now()) where id = ?";
    private static final String FENCED_DONE = "update job set status = 'DONE', finished_at = now() where id = ? and claim_token = ?";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void dropSchemas() {
        jdbc.execute("drop schema if exists " + DEV + " cascade");
        jdbc.execute("drop schema if exists " + FRESH + " cascade");
    }

    private Flyway flyway(String schema, String target) {
        var c = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("1");
        if (target != null) c.target(MigrationVersion.fromVersion(target));
        return c.load();
    }

    private void legacyAtV20() throws Exception {
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
        flyway(DEV, "20").migrate();
    }

    private void seedDev() {
        String d = DEV + ".";
        jdbc.update("insert into " + d + "users (id, email, full_name, password_hash, role, status, \"createdAt\", \"updatedAt\") values ('u1', 'u1@example.test', 'x', 'x', 'VENUE', 'ACTIVE', now(), now()), ('u2', 'u2@example.test', 'x', 'x', 'VENUE', 'ACTIVE', now(), now())");
        jdbc.update("insert into " + d + "business (id, owner_user_id, name, business_phone, street_address, city, state, zipcode, custom_subdomain, currency, open24_7, \"createdAt\", \"updatedAt\") values "
                + "('b1', 'u1', 'B', '1', 'r', 'c', 's', '', 's1', 'ETB', true, now(), now()), ('b2', 'u2', 'B', '1', 'r', 'c', 's', '', 's2', 'ETB', true, now(), now())");
        jdbc.update("insert into " + d + "monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, created_at, created_by_user_id) values (1, 'SETTING_2', true, true, true, now(), now(), 'u1')");
        jdbc.update("insert into " + d + "business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, created_by_user_id, updated_at, updated_by_user_id, past_due_since, row_version) values "
                + "('s1', 'b1', 'MODEL_4', 'ACTIVE', 1, now() + interval '30 days', 'MANUAL', now(), 'u1', now(), 'u1', null, 3), ('s2', 'b2', 'MODEL_2', 'PAST_DUE', 1, null, 'OWNER', now(), 'u2', now(), 'u2', now(), 1)");
        jdbc.update("insert into " + d + "scheduled_change (id, business_id, from_subscription_id, kind, effective_at, status) values ('c1', 'b1', 's1', 'CANCEL', now() + interval '30 days', 'PENDING')");
    }

    private List<Map<String, Object>> devRows(String table) {
        return jdbc.queryForList("select * from " + DEV + "." + table + " order by 1");
    }

    private List<String> history(String schema) {
        return jdbc.queryForList("select version from " + schema + ".flyway_schema_history where version is not null order by installed_rank", String.class);
    }

    private String job(String type, String key, String hash) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into job (id, type, key, payload, payload_hash) values (?, ?, ?, '{}'::jsonb, ?)", id, type, key, hash);
        return id;
    }

    private void notice(String business, String kind, String key) {
        notice(business, kind, key, "IN_APP");
    }

    private String notice(String business, String kind, String key, String channel) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into notification (id, business_id, kind, key, channel, kind_class) values (?, ?, ?, ?, ?, 'ESSENTIAL')", id, business, kind, key, channel);
        return id;
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ---- S1-08B

    @Test
    void anExistingDatabaseUpgradesFromV20KeepingEveryRow() throws Exception {
        legacyAtV20();
        seedDev();
        List<Map<String, Object>> subscriptions = devRows("business_subscription");
        List<Map<String, Object>> changes = devRows("scheduled_change");
        assertThat(flyway(DEV, "21").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(devRows("business_subscription")).isEqualTo(subscriptions);
        assertThat(devRows("scheduled_change")).isEqualTo(changes);
        assertThat(history(DEV)).endsWith("20", "21");
        flyway(DEV, "21").validate();
        assertThat(flyway(DEV, "21").migrate().migrationsExecuted).isZero();
        assertThat(devRows("business_subscription")).isEqualTo(subscriptions);
        assertThat(history(DEV)).doesNotHaveDuplicates();
    }

    @Test
    void aFreshDatabaseReachesV21AndAgreesWithTheUpgradedSchema() throws Exception {
        legacyAtV20();
        flyway(DEV, "21").migrate();
        jdbc.execute("create schema " + FRESH);
        Flyway fresh = Flyway.configure().dataSource(dataSource).schemas(FRESH).locations("classpath:db/migration").target(MigrationVersion.fromVersion("21")).load();
        fresh.migrate();
        fresh.validate();
        assertThat(history(FRESH)).hasSize(21).endsWith("21");
        String columns = "select column_name, data_type, is_nullable, column_default, character_maximum_length from information_schema.columns where table_schema = ? and table_name = ? order by column_name";
        for (String table : List.of("job", "notification")) {
            assertThat(jdbc.queryForList(columns, FRESH, table)).as(table).isNotEmpty().isEqualTo(jdbc.queryForList(columns, DEV, table));
        }
    }

    @Test
    void v21ReferencesNoLaterMigrationAndNoLaterTableExists() throws Exception {
        String sql = new ClassPathResource("db/migration/V21__jobs_and_notifications.sql").getContentAsString(StandardCharsets.UTF_8).toLowerCase();
        assertThat(sql).doesNotContain("subscription_period", "subscription_price", "billing_", "payment_ledger", "legacy_publish_right", "migration_run", "refund_request");
        assertThat(sql.split("references ")).as("the only foreign key target is business").hasSize(2);
        assertThat(sql.split("references ")[1]).startsWith("business");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version::numeric > 23.3", Integer.class)).isZero();
    }

    // ---- job constraints

    @Test
    void aNewJobIsPendingWithNoClaims() {
        String id = job("t", unique(), "h");
        Map<String, Object> row = jdbc.queryForMap("select status, claims, claim_token, lease_until, first_attempt_at, finished_at, run_at is not null as scheduled, created_at is not null as created from job where id = ?", id);
        assertThat(row).containsEntry("status", "PENDING").containsEntry("claims", 0).containsEntry("claim_token", null)
                .containsEntry("lease_until", null).containsEntry("first_attempt_at", null).containsEntry("finished_at", null).containsEntry("scheduled", true).containsEntry("created", true);
    }

    @Test
    void aJobKeyIsUniquePerType() {
        String key = unique();
        job("refund.execute", key, "h");
        assertThatThrownBy(() -> job("refund.execute", key, "h")).hasMessageContaining("uk_job_type_key");
        assertThatCode(() -> job("checkout.create", key, "h")).doesNotThrowAnyException();
    }

    @Test
    void anExistingKeysHashIsVisibleToTheEnqueuer() {
        String key = unique();
        job("t", key, "first");
        String insert = "insert into job (id, type, key, payload, payload_hash) values (?, 't', ?, '{}'::jsonb, ?) on conflict (type, key) do nothing";
        assertThat(jdbc.update(insert, UUID.randomUUID().toString(), key, "second")).isZero();
        assertThat(jdbc.queryForObject("select payload_hash from job where type = 't' and key = ?", String.class, key)).isEqualTo("first");
    }

    @Test
    void aPayloadHashIsRequired() {
        assertThatThrownBy(() -> job("t", unique(), null)).hasMessageContaining("payload_hash");
    }

    @Test
    void jobStatusAndErrorKindAreChecked() {
        String id = job("t", unique(), "h");
        assertThatThrownBy(() -> jdbc.update("update job set status = 'NONSENSE' where id = ?", id)).hasMessageContaining("ck_job_status");
        assertThatThrownBy(() -> jdbc.update("update job set last_error_kind = 'NONSENSE' where id = ?", id)).hasMessageContaining("ck_job_error_kind");
        for (String kind : List.of("TRANSIENT", "PERMANENT")) jdbc.update("update job set last_error_kind = ? where id = ?", kind, id);
        jdbc.update("update job set status = 'DONE', finished_at = now() where id = ?", id);
        jdbc.update("update job set status = 'DEAD' where id = ?", id);
        jdbc.update("update job set status = 'PENDING', finished_at = null where id = ?", id);
    }

    @Test
    void claimsCantBeNegative() {
        String id = job("t", unique(), "h");
        assertThatThrownBy(() -> jdbc.update("update job set claims = -1 where id = ?", id)).hasMessageContaining("ck_job_claims");
    }

    @Test
    void aRunningJobMustHaveATokenAndALease() {
        String id = job("t", unique(), "h");
        assertThatThrownBy(() -> jdbc.update("update job set status = 'RUNNING' where id = ?", id)).hasMessageContaining("ck_job_running_claim");
        assertThatThrownBy(() -> jdbc.update("update job set status = 'RUNNING', claim_token = 'x' where id = ?", id)).hasMessageContaining("ck_job_running_claim");
    }

    @Test
    void finishedAtAccompaniesAFinishedJobOnly() {
        String id = job("t", unique(), "h");
        assertThatThrownBy(() -> jdbc.update("update job set status = 'DONE' where id = ?", id)).hasMessageContaining("ck_job_finished");
        assertThatThrownBy(() -> jdbc.update("update job set finished_at = now() where id = ?", id)).hasMessageContaining("ck_job_finished");
    }

    @Test
    void aJobPayloadIsRequiredJson() {
        assertThatThrownBy(() -> jdbc.update("insert into job (id, type, key, payload_hash) values (?, 't', ?, 'h')", unique(), unique())).hasMessageContaining("payload");
        assertThat(jdbc.queryForObject("select data_type from information_schema.columns where table_schema = 'public' and table_name = 'job' and column_name = 'payload'", String.class)).isEqualTo("jsonb");
    }

    // ---- claim and fencing prerequisites (DD §9.2, MT-03)

    @Test
    void eachClaimCountsAndIssuesANewToken() {
        String id = job("t", unique(), "h");
        jdbc.update(CLAIM, "token-1", id);
        jdbc.update("update job set status = 'PENDING' where id = ?", id);
        jdbc.update(CLAIM, "token-2", id);
        assertThat(jdbc.queryForMap("select claims, claim_token, status from job where id = ?", id))
                .containsEntry("claims", 2).containsEntry("claim_token", "token-2").containsEntry("status", "RUNNING");
    }

    @Test
    void aStaleWorkersCompletionAfterLeaseExpiryChangesNoRows() {
        String id = job("t", unique(), "h");
        jdbc.update(CLAIM, "w1", id);
        jdbc.update("update job set lease_until = now() - interval '1 second' where id = ?", id);
        assertThat(jdbc.update("update job set status = 'PENDING' where status = 'RUNNING' and lease_until < now() and id = ?", id)).isEqualTo(1);
        jdbc.update(CLAIM, "w2", id);
        assertThat(jdbc.update(FENCED_DONE, id, "w1")).isZero();
        assertThat(jdbc.queryForObject("select status from job where id = ?", String.class, id)).isEqualTo("RUNNING");
        assertThat(jdbc.update(FENCED_DONE, id, "w2")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from job where id = ?", String.class, id)).isEqualTo("DONE");
    }

    @Test
    void twoWorkersNeverSelectTheSameJob() throws Exception {
        String type = "skip-" + unique();
        job(type, "a", "h");
        job(type, "b", "h");
        String pick = "select id from job where type = ? and status = 'PENDING' and run_at <= now() order by run_at for update skip locked limit 1";
        try (Connection w1 = dataSource.getConnection(); Connection w2 = dataSource.getConnection()) {
            w1.setAutoCommit(false);
            w2.setAutoCommit(false);
            String first = pickOne(w1, pick, type);
            String second = pickOne(w2, pick, type);
            assertThat(first).isNotNull();
            assertThat(second).isNotNull().isNotEqualTo(first);
            w1.rollback();
            w2.rollback();
        }
    }

    private static String pickOne(Connection c, String sql, String type) throws Exception {
        try (var ps = c.prepareStatement(sql)) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ---- notification

    @Test
    void aNoticeIsUniquePerRestaurantKindKeyAndChannel() {
        Tenant a = data.tenant("n-a");
        Tenant b = data.tenant("n-b");
        notice(a.businessId(), "EXPIRY", "k1");
        assertThatThrownBy(() -> notice(a.businessId(), "EXPIRY", "k1")).hasMessageContaining("uk_notification_restaurant_kind_key_channel");
        assertThatCode(() -> notice(a.businessId(), "EXPIRY", "k1", "EMAIL")).doesNotThrowAnyException();
        assertThatCode(() -> notice(a.businessId(), "OTHER", "k1")).doesNotThrowAnyException();
        assertThatCode(() -> notice(a.businessId(), "EXPIRY", "k2")).doesNotThrowAnyException();
        assertThatCode(() -> notice(b.businessId(), "EXPIRY", "k1")).doesNotThrowAnyException();
    }

    @Test
    void aNoticeStartsPendingWithNoAttemptsAndEmptyData() {
        Tenant a = data.tenant("n-att");
        notice(a.businessId(), "EXPIRY", "k1");
        assertThat(jdbc.queryForMap("select status, template_data::text as data, created_at is not null as c, updated_at is not null as u from notification where business_id = ?", a.businessId()))
                .containsEntry("status", "PENDING").containsEntry("data", "{}").containsEntry("c", true).containsEntry("u", true);
        assertThat(jdbc.queryForObject("select attempts from notification where business_id = ?", Integer.class, a.businessId())).isZero();
        assertThatThrownBy(() -> jdbc.update("update notification set attempts = -1 where business_id = ?", a.businessId())).hasMessageContaining("ck_notification_attempts");
    }

    @Test
    void theKindClassAndChannelAreChecked() {
        Tenant a = data.tenant("n-check");
        String insert = "insert into notification (id, business_id, kind, key, channel, kind_class, status) values (?, ?, 'K', ?, ?, ?, 'PENDING')";
        assertThatThrownBy(() -> jdbc.update(insert, unique(), a.businessId(), "1", "IN_APP", "NONSENSE")).hasMessageContaining("ck_notification_kind_class");
        assertThatThrownBy(() -> jdbc.update(insert, unique(), a.businessId(), "2", "SMS", "ESSENTIAL")).hasMessageContaining("ck_notification_channel");
        assertThatCode(() -> jdbc.update(insert, unique(), a.businessId(), "3", "EMAIL", "PAYMENT_REQUEST")).doesNotThrowAnyException();
        assertThatCode(() -> jdbc.update(insert, unique(), a.businessId(), "4", "IN_APP", "ESSENTIAL")).doesNotThrowAnyException();
    }

    private void status(String id, String to) {
        jdbc.update("update notification set status = ? where id = ?", to, id);
    }

    @Test
    void aNoticeStatusMustBeKnown() {
        String tenant = data.tenant("n-s").businessId();
        assertThatThrownBy(() -> jdbc.update("insert into notification (id, business_id, kind, key, channel, kind_class, status) values (?, ?, 'K', '1', 'IN_APP', 'ESSENTIAL', 'NONSENSE')", unique(), tenant))
                .hasMessageContaining("ck_notification_status");
    }

    @Test
    void theApprovedStatusPathsAreAllowed() {
        String tenant = data.tenant("n-ok").businessId();
        String a = notice(tenant, "K", "a", "IN_APP");
        status(a, "SENT");
        status(a, "READ");
        String b = notice(tenant, "K", "b", "IN_APP");
        status(b, "FAILED");
        status(b, "PENDING");
        status(b, "SENT");
        assertThat(jdbc.queryForObject("select status from notification where id = ?", String.class, a)).isEqualTo("READ");
    }

    @Test
    void otherStatusChangesAreRefused() {
        String tenant = data.tenant("n-no").businessId();
        String pending = notice(tenant, "K", "p", "IN_APP");
        assertThatThrownBy(() -> status(pending, "READ")).hasMessageContaining("notification status can't change");
        String sent = notice(tenant, "K", "s", "IN_APP");
        status(sent, "SENT");
        for (String to : List.of("PENDING", "FAILED")) assertThatThrownBy(() -> status(sent, to)).as(to).hasMessageContaining("notification status can't change");
        String failed = notice(tenant, "K", "f", "IN_APP");
        status(failed, "FAILED");
        assertThatThrownBy(() -> status(failed, "READ")).hasMessageContaining("notification status can't change");
        status(sent, "READ");
        for (String to : List.of("PENDING", "SENT", "FAILED")) assertThatThrownBy(() -> status(sent, to)).as(to).hasMessageContaining("notification status can't change");
    }

    /** Every status pair, from a row brought to that status by the approved path. */
    @Test
    void everyStatusPairIsDecidedExactlyAsApproved() {
        String tenant = data.tenant("n-all").businessId();
        Set<String> allowed = Set.of("PENDING>SENT", "PENDING>FAILED", "FAILED>PENDING", "SENT>READ");
        Map<String, List<String>> path = Map.of("PENDING", List.of(), "SENT", List.of("SENT"), "FAILED", List.of("FAILED"), "READ", List.of("SENT", "READ"));
        int n = 0;
        for (String from : path.keySet()) {
            for (String to : path.keySet()) {
                if (from.equals(to)) continue;
                String id = notice(tenant, "K", "pair" + n++, "IN_APP");
                path.get(from).forEach(s -> status(id, s));
                if (allowed.contains(from + ">" + to)) assertThatCode(() -> status(id, to)).as(from + ">" + to).doesNotThrowAnyException();
                else assertThatThrownBy(() -> status(id, to)).as(from + ">" + to).hasMessageContaining("notification status can't change");
            }
        }
        assertThat(n).isEqualTo(12);
    }

    @Test
    void anEmailCantBeRead() {
        String id = notice(data.tenant("n-mail").businessId(), "K", "1", "EMAIL");
        status(id, "SENT");
        assertThatThrownBy(() -> status(id, "READ")).hasMessageContaining("only an in-app notice");
    }

    private Map<String, Object> notificationRow(String id) {
        return jdbc.queryForMap("select channel, status from notification where id = ?", id);
    }

    @Test
    void anEmailCantBeInsertedAsRead() {
        String tenant = data.tenant("n-ins").businessId();
        assertThatThrownBy(() -> jdbc.update("insert into notification (id, business_id, kind, key, channel, kind_class, status) values (?, ?, 'K', '1', 'EMAIL', 'ESSENTIAL', 'READ')", unique(), tenant))
                .hasMessageContaining("ck_notification_email_not_read");
        assertThat(jdbc.queryForObject("select count(*) from notification where business_id = ?", Integer.class, tenant)).isZero();
    }

    @Test
    void aChannelCantChangeAfterInsertion() {
        String tenant = data.tenant("n-chan").businessId();
        String mail = notice(tenant, "K", "m", "EMAIL");
        String app = notice(tenant, "K", "a", "IN_APP");
        assertThatThrownBy(() -> jdbc.update("update notification set channel = 'IN_APP' where id = ?", mail)).hasMessageContaining("notification channel can't be changed");
        assertThatThrownBy(() -> jdbc.update("update notification set channel = 'EMAIL' where id = ?", app)).hasMessageContaining("notification channel can't be changed");
        assertThat(notificationRow(mail)).containsEntry("channel", "EMAIL");
        assertThat(notificationRow(app)).containsEntry("channel", "IN_APP");
    }

    @Test
    void aCombinedChannelAndStatusUpdateCantReachRead() {
        String id = notice(data.tenant("n-combo").businessId(), "K", "1", "EMAIL");
        status(id, "SENT");
        assertThatThrownBy(() -> jdbc.update("update notification set channel = 'IN_APP', status = 'READ' where id = ?", id)).hasMessageContaining("notification channel can't be changed");
        assertThat(notificationRow(id)).containsEntry("channel", "EMAIL").containsEntry("status", "SENT");
    }

    @Test
    void anEmailFollowsTheApprovedDeliveryPaths() {
        String tenant = data.tenant("n-mail-ok").businessId();
        String sent = notice(tenant, "K", "s", "EMAIL");
        status(sent, "SENT");
        String retried = notice(tenant, "K", "r", "EMAIL");
        status(retried, "FAILED");
        status(retried, "PENDING");
        status(retried, "SENT");
        assertThat(notificationRow(sent)).containsEntry("status", "SENT");
        assertThat(notificationRow(retried)).containsEntry("status", "SENT");
    }

    @Test
    void anInAppNoticeCanBeRead() {
        String id = notice(data.tenant("n-read").businessId(), "K", "1", "IN_APP");
        status(id, "SENT");
        status(id, "READ");
        assertThat(notificationRow(id)).containsEntry("channel", "IN_APP").containsEntry("status", "READ");
    }

    @Test
    void channelsKeepIndependentDeliveryState() {
        String tenant = data.tenant("n-ind").businessId();
        String app = notice(tenant, "K", "1", "IN_APP");
        String mail = notice(tenant, "K", "1", "EMAIL");
        status(app, "SENT");
        status(mail, "FAILED");
        assertThat(jdbc.queryForObject("select status from notification where id = ?", String.class, app)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("select status from notification where id = ?", String.class, mail)).isEqualTo("FAILED");
    }

    @Test
    void aNoticeBelongsToARealRestaurant() {
        assertThatThrownBy(() -> notice("no-such-restaurant", "K", "1")).hasMessageContaining("fk_notification_business");
    }
}
