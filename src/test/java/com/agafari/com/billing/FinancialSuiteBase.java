package com.agafari.com.billing;

import com.agafari.com.billing.fake.FakeProviderClient;
import com.agafari.com.billing.fake.FakeStrategy;
import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.webhook.WebhookController;
import com.agafari.com.jobs.JobHandler;
import com.agafari.com.jobs.JobRunner;
import com.agafari.com.service.MonetizationLocks;
import com.agafari.com.service.PlanRequestService;
import com.agafari.com.subscription.EffectiveStateEvaluator;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF SUB-R4-16: shared fixtures of the financial concurrency verification suite (PO E1–E6, register §35). Tests only.
 *
 * <ul>
 *   <li><b>Gates</b> hold a lock on their own connection so that attempts queue behind it; {@link Gate#awaitBlocked} proves,
 *   from {@code pg_stat_activity}, that the attempts were in flight at the same time. A gate proves overlap only; restaurant
 *   locking, job fencing and atomicity are tested directly (verification requirement 1).</li>
 *   <li><b>Forced orders</b> use their own latches, never the gate (requirement 2).</li>
 *   <li><b>Faults</b> are triggers created on the disposable database and always dropped after each test (E4).</li>
 *   <li><b>Evidence</b> lines go to {@code target/r4-16-evidence/}, copied to {@code docs/evidence/SUB-R4-16/} (E5).</li>
 * </ul>
 */
abstract class FinancialSuiteBase extends PaymentOutcomeTestBase {

    protected static final Path EVIDENCE = Path.of("target", "r4-16-evidence");
    protected static final String WEBHOOK = "/api/billing/webhooks/" + PROVIDER;
    private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9_]+");

    @Autowired protected DataSource dataSource;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected ApplicationContext context;
    @Autowired protected WebhookController webhooks;
    @Autowired protected ReconciliationService reconciliation;
    @Autowired protected PlanRequestService planRequests;
    @Autowired protected EffectiveStateEvaluator evaluator;

    protected String price3;
    private final List<String> faults = new ArrayList<>();
    private final List<Gate> gates = new ArrayList<>();
    private final List<JobRunner> runners = new ArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    @BeforeEach
    void setUpSuite() {
        webhooks.setBillingEnabled(Market.ET, true);
        planRequests.setOwnerPlansVisible(true);
        price3 = price("MODEL_3", "ET", "ETB", 1500);
        // Model 3 enabled as well, from before any payment in these tests (the base configuration enables Models 2 and 4)
        jdbc.update("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, created_at, created_by_user_id) "
                + "values (2, 'SETTING_2', true, true, true, ?, now(), ?)", Timestamp.from(Instant.now().minus(Duration.ofDays(60))), admin.getId());
        jdbc.update("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, created_at, created_by_user_id) "
                + "values (3, 'SETTING_2', true, true, true, now(), now(), ?)", admin.getId());
    }

    /** Runs after every test, failed or not (E4): faults are dropped, gates released, runners stopped. */
    @AfterEach
    void tearDownSuite() {
        gates.forEach(Gate::close);
        gates.clear();
        runners.forEach(JobRunner::shutdown);
        runners.clear();
        pool.shutdownNow();
        dropAllFaults();
        FakeStrategy.nextKind = CheckoutKind.NEW;
        webhooks.setBillingEnabled(Market.ET, false);
        planRequests.setOwnerPlansVisible(false);
        evaluator.setPeriodEndEnabled(false);
        clock.reset();
    }

    // ---- evidence (E5) ----

    protected static synchronized void evidence(String file, String line) {
        try {
            Files.createDirectories(EVIDENCE);
            Files.writeString(EVIDENCE.resolve(file), line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- time ----

    protected static Instant soon() {
        return micros(Instant.now().plus(1, ChronoUnit.MINUTES));
    }

    protected Instant dbNow() {
        return jdbc.queryForObject("select now()", Timestamp.class).toInstant();
    }

    // ---- concurrency helpers ----

    protected <T> Future<T> async(Callable<T> work) {
        return pool.submit(work);
    }

    protected static <T> T join(Future<T> f) throws Exception {
        try {
            return f.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception x) throw x;
            throw e;
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new AssertionError("an attempt did not finish within 60 s", e);
        }
    }

    /** The outcome of an attempt: its value, or the exception it ended with (an attempt may legitimately be refused). */
    protected static <T> Object settle(Future<T> f) {
        try {
            return join(f);
        } catch (Exception e) {
            return e;
        }
    }

    /**
     * Forces a serial order with its own coordination (requirement 2): both attempts are submitted, the second waits on a latch
     * the first releases only after it has returned (and so committed or rolled back).
     */
    protected List<Object> forced(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch firstDone = new CountDownLatch(1);
        Future<?> a = async(() -> {
            try {
                return first.call();
            } finally {
                firstDone.countDown();
            }
        });
        Future<?> b = async(() -> {
            if (!firstDone.await(60, TimeUnit.SECONDS)) throw new AssertionError("the first attempt never finished");
            return second.call();
        });
        return List.of(settle(a), settle(b));
    }

    /**
     * Overlapping attempts: each starts behind {@code gate}; the gate is released only once all {@code attempts.size()}
     * attempts are proven waiting at the same time. Returns their outcomes.
     */
    protected List<Object> contended(Gate gate, String race, List<Callable<?>> attempts) throws Exception {
        List<Future<?>> started = new ArrayList<>();
        for (Callable<?> a : attempts) started.add(async(a));
        gate.awaitBlocked(attempts.size(), race);
        gate.close();
        List<Object> results = new ArrayList<>();
        for (Future<?> f : started) results.add(settle(f));
        return results;
    }

    // ---- gates ----

    /** Holds the configuration lock exclusively, as a configuration change does; every payment or lifecycle write queues behind it. */
    protected Gate configGate() {
        return gate("config", "select pg_advisory_xact_lock(" + MonetizationLocks.CONFIG_LOCK_KEY + ")");
    }

    /** Holds the restaurant row lock, as {@link MonetizationLocks#restaurant} does. */
    protected Gate restaurantGate(Tenant t) {
        return gate("restaurant", "select id from business where id = '" + t.businessId() + "' for no key update");
    }

    private Gate gate(String kind, String sql) {
        Gate g = new Gate(kind, sql);
        gates.add(g);
        return g;
    }

    protected final class Gate implements AutoCloseable {
        private final String kind;
        private final Connection connection;
        private final int pid;
        private boolean open = true;

        private Gate(String kind, String sql) {
            this.kind = kind;
            try {
                connection = dataSource.getConnection();
                connection.setAutoCommit(false);
                try (Statement s = connection.createStatement()) {
                    s.execute(sql);
                }
                try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("select pg_backend_pid()")) {
                    rs.next();
                    pid = rs.getInt(1);
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        public int pid() {
            return pid;
        }

        /** Backends waiting on a lock, other than the gate. */
        public List<Map<String, Object>> blocked() {
            return jdbc.queryForList("""
                    select pid, wait_event_type, wait_event, pg_blocking_pids(pid) as blocking, left(regexp_replace(query, '\\s+', ' ', 'g'), 90) as query
                    from pg_stat_activity where datname = current_database() and pid <> ? and wait_event_type = 'Lock' and cardinality(pg_blocking_pids(pid)) > 0
                    order by pid""", pid);
        }

        /** Waits (deadline 20 s) until {@code n} backends are blocked at the same time; records the proof. */
        public List<Map<String, Object>> awaitBlocked(int n, String race) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            List<Map<String, Object>> seen = blocked();
            while (seen.size() < n) {
                if (System.nanoTime() > deadline) fail("%s: only %d of %d attempts were seen waiting behind the %s gate: %s", race, seen.size(), n, kind, seen);
                sleep(5);
                seen = blocked();
            }
            evidence("lock-evidence.txt", race + " | gate=" + kind + " pid=" + pid + " | waiting=" + seen.size() + " | "
                    + seen.stream().map(r -> "pid=" + r.get("pid") + " " + r.get("wait_event") + " blockedBy=" + blocking(r) + " q=" + r.get("query")).toList());
            return seen;
        }

        private static String blocking(Map<String, Object> r) {
            Object b = r.get("blocking");
            try {
                return b instanceof java.sql.Array a ? java.util.Arrays.toString((Object[]) a.getArray()) : String.valueOf(b);
            } catch (SQLException e) {
                return String.valueOf(b);
            }
        }

        @Override
        public void close() {
            if (!open) return;
            open = false;
            try {
                connection.commit();
                connection.close();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    protected static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- fault injection (E4) ----

    /**
     * A trigger on {@code table} that refuses the matching write (SQLSTATE P0001, so no refusal handler treats it as evidence).
     * Before refusing, it counts the hit on a sequence (non-transactional, so the count survives the rollback) and reports
     * {@code witness}, a query evaluated inside the doomed transaction, in its message: proof that the intended later write
     * was reached after the earlier ones.
     */
    protected Fault fault(String name, String table, String op, String condition, String witness) {
        for (String s : List.of(name, table, op.toLowerCase())) if (!SAFE_NAME.matcher(s).matches()) throw new IllegalArgumentException(s);
        String fn = "r416_fault_" + name, seq = "r416_hits_" + name;
        faults.add(name);
        jdbc.execute("create sequence " + seq);
        jdbc.execute("create function " + fn + "() returns trigger language plpgsql as $f$ begin if " + condition + " then perform nextval('" + seq + "'); "
                + "raise exception 'R4-16 injected fault % witness=%', '" + name + "', (" + witness + "); end if; return new; end $f$");
        jdbc.execute("create trigger " + fn + " before " + op + " on " + table + " for each row execute function " + fn + "()");
        return new Fault(name, seq);
    }

    protected final class Fault {
        private final String name;
        private final String sequence;

        private Fault(String name, String sequence) {
            this.name = name;
            this.sequence = sequence;
        }

        public long hits() {
            Map<String, Object> s = jdbc.queryForMap("select last_value, is_called from " + sequence);
            return Boolean.TRUE.equals(s.get("is_called")) ? ((Number) s.get("last_value")).longValue() : 0;
        }

        public void remove() {
            jdbc.execute("drop trigger if exists r416_fault_" + name + " on " + table());
            jdbc.execute("drop function if exists r416_fault_" + name + "()");
        }

        private String table() {
            return jdbc.queryForObject("select c.relname from pg_trigger t join pg_class c on c.oid = t.tgrelid where t.tgname = ?", String.class, "r416_fault_" + name);
        }

        /** The witness value the fault reported, from the exception chain. */
        public String witness(Throwable t) {
            for (Throwable c = t; c != null; c = c.getCause()) {
                String m = c.getMessage();
                if (m != null && m.contains("R4-16 injected fault " + name)) {
                    int i = m.indexOf("witness=");
                    return m.substring(i + 8).split("[\\s\\n]")[0];
                }
            }
            return null;
        }
    }

    private void dropAllFaults() {
        for (Map<String, Object> t : jdbc.queryForList("select t.tgname, c.relname from pg_trigger t join pg_class c on c.oid = t.tgrelid where t.tgname like 'r416\\_fault\\_%'")) {
            jdbc.execute("drop trigger if exists " + t.get("tgname") + " on " + t.get("relname"));
        }
        for (String f : jdbc.queryForList("select proname from pg_proc where proname like 'r416\\_fault\\_%'", String.class)) jdbc.execute("drop function if exists " + f + "()");
        for (String s : jdbc.queryForList("select sequencename from pg_sequences where sequencename like 'r416\\_hits\\_%'", String.class)) jdbc.execute("drop sequence if exists " + s);
        faults.clear();
    }

    /** No fault trigger, function or sequence remains (E4 cleanup proof). */
    protected int faultObjectsLeft() {
        return count("select (select count(*) from pg_trigger where tgname like 'r416\\_fault\\_%') + (select count(*) from pg_proc where proname like 'r416\\_fault\\_%')"
                + " + (select count(*) from pg_sequences where sequencename like 'r416\\_hits\\_%')");
    }

    // ---- business state ----

    /**
     * The restaurant's business state: subscriptions, ledger, invoices, periods, exceptions, refunds, scheduled changes,
     * checkouts and business audit events. Delivery and job bookkeeping are reported separately ({@link #bookkeeping}).
     */
    protected Map<String, Object> businessState(Tenant t) {
        String b = t.businessId();
        Map<String, Object> s = new TreeMap<>();
        s.put("subscriptions", jdbc.queryForList("select id, status, source, plan_code, ended_reason, replaced_by_id, period_end from business_subscription where business_id = ? order by id", b));
        s.put("payments", jdbc.queryForList("select id, kind, status, provider_payment_ref, invoice_id, checkout_session_id, amount_minor from payment where business_id = ? order by id", b));
        s.put("invoices", jdbc.queryForList("select id, status, period_start, period_end from billing_invoice where business_id = ? order by id", b));
        s.put("periods", jdbc.queryForList("select id, subscription_id, payment_id, invoice_id, period_start, period_end from subscription_period where business_id = ? order by id", b));
        s.put("exceptions", jdbc.queryForList("select id, code, status, payment_id, refund_request_id from billing_exception where business_id = ? order by id", b));
        s.put("refunds", jdbc.queryForList("select r.id, r.status, r.payment_id from refund_request r join payment p on p.id = r.payment_id where p.business_id = ? order by r.id", b));
        s.put("changes", jdbc.queryForList("select id, status, funding_payment_id, applied_subscription_id from scheduled_change where business_id = ? order by id", b));
        s.put("checkouts", jdbc.queryForList("select id, status, kind from checkout_session where business_id = ? order by id", b));
        s.put("audit", auditByAction(t));
        return s;
    }

    /** Business audit events for this restaurant's subscriptions, changes, payments, exceptions and refunds, by action. */
    protected Map<String, Integer> auditByAction(Tenant t) {
        Map<String, Integer> m = new TreeMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                select action, count(*) as n from admin_audit_event a where a.target_id in (
                    select id from business_subscription where business_id = ? union select id from scheduled_change where business_id = ?
                    union select id from payment where business_id = ? union select provider_payment_ref from payment where business_id = ?
                    union select id from billing_exception where business_id = ? union select id from checkout_session where business_id = ?
                    union select r.id from refund_request r join payment p on p.id = r.payment_id where p.business_id = ? union select ?)
                group by action""", t.businessId(), t.businessId(), t.businessId(), t.businessId(), t.businessId(), t.businessId(), t.businessId(), t.businessId())) {
            m.put((String) r.get("action"), ((Number) r.get("n")).intValue());
        }
        return m;
    }

    /** Delivery and job bookkeeping, which redelivery may legitimately change (requirement 5). */
    protected Map<String, Object> bookkeeping() {
        Map<String, Object> m = new TreeMap<>();
        m.put("providerEvents", count("select count(*) from provider_event"));
        m.put("providerEventsByStatus", jdbc.queryForList("select status, count(*) as n from provider_event group by status order by status"));
        m.put("jobs", jdbc.queryForList("select type, status, count(*) as n from job group by type, status order by type, status"));
        m.put("reconciliationAudits", count("select count(*) from admin_audit_event where action = 'BILLING_RECONCILED'"));
        return m;
    }

    // ---- ledger accounting (requirement 4) ----

    /**
     * Every verified charge in the database has exactly one durable account: consumed by one period, linked to one outcome
     * exception (a refund request always with its exception), or held by one open scheduled-change funding association.
     * Returns what remains visible: open reviews and unsettled refunds.
     */
    protected Map<String, Object> assertLedgerAccounted(String label) {
        List<Map<String, Object>> charges = jdbc.queryForList("""
                select p.id, p.business_id,
                       (select count(*) from subscription_period sp where sp.payment_id = p.id) as consumed,
                       (select count(*) from billing_exception e where e.payment_id = p.id and e.code not in ('LEDGER_FACTS_MISMATCH')) as exceptions,
                       (select count(*) from scheduled_change c where c.funding_payment_id = p.id and c.status in ('PENDING', 'AWAITING_PAYMENT')) as held,
                       (select count(*) from refund_request r where r.payment_id = p.id) as refunds,
                       (select count(*) from billing_exception e join refund_request r on r.id = e.refund_request_id where r.payment_id = p.id) as linked_refunds
                from payment p where p.kind = 'CHARGE'""");
        for (Map<String, Object> c : charges) {
            long consumed = n(c, "consumed"), exceptions = n(c, "exceptions"), held = n(c, "held"), refunds = n(c, "refunds"), linked = n(c, "linked_refunds");
            assertThat(consumed + exceptions + held).as("%s: charge %s has exactly one durable account (consumed %d, exception %d, held %d)", label, c.get("id"), consumed, exceptions, held)
                    .isEqualTo(1);
            assertThat(refunds).as("%s: refund requests of %s", label, c.get("id")).isLessThanOrEqualTo(1);
            assertThat(linked).as("%s: a refund of %s has its exception", label, c.get("id")).isEqualTo(refunds);
        }
        Map<String, Object> visible = new LinkedHashMap<>();
        visible.put("charges", charges.size());
        visible.put("openReviews", jdbc.queryForList("select code, status, count(*) as n from billing_exception where status <> 'RESOLVED' group by code, status order by code, status"));
        visible.put("unsettledRefunds", jdbc.queryForList("select status, count(*) as n from refund_request where status not in ('SUCCEEDED') group by status order by status"));
        return visible;
    }

    private static long n(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    /** A reconciliation run that finds nothing new, after which every charge is still accounted for and reviews stay visible. */
    protected Map<String, Object> assertReconciliationClean(String label) {
        Map<String, Object> before = assertLedgerAccounted(label + " before reconciliation");
        int exceptionsBefore = count("select count(*) from billing_exception");
        ReconciliationService.DailyResult r = reconciliation.daily(clock.now());
        drainInternalEvents();
        assertThat(r.exceptionsCreated()).as("%s: reconciliation found no unaccounted charge or contradiction", label).isZero();
        assertThat(count("select count(*) from billing_exception")).as("%s: no new exception", label).isEqualTo(exceptionsBefore);
        Map<String, Object> after = assertLedgerAccounted(label + " after reconciliation");
        assertThat(after.get("openReviews")).as("%s: expected open reviews stay visible", label).isEqualTo(before.get("openReviews"));
        assertThat(after.get("unsettledRefunds")).as("%s: unsettled refunds stay visible", label).isEqualTo(before.get("unsettledRefunds"));
        evidence("reconciliation.txt", label + " | eventsCreated=" + r.eventsCreated() + " exceptionsCreated=" + r.exceptionsCreated() + " unverifiable=" + r.unverifiable() + " | " + after);
        return after;
    }

    /** Internal reconciliation events queued by a daily run, processed as the job runner would (transient outcomes stay pending). */
    protected void drainInternalEvents() {
        for (String id : jdbc.queryForList("select id from provider_event where event_id like ? and status = 'PENDING' order by received_at", String.class,
                com.agafari.com.billing.webhook.InboxService.INTERNAL_PREFIX + "%")) {
            try {
                outcomes.process(id);
            } catch (RuntimeException retriedLater) {
                // transient: left PENDING, as the runner would
            }
        }
    }

    // ---- webhooks through HTTP ----

    protected static byte[] webhookBody(String eventId, String paymentRef) {
        return body(eventId, paymentRef);
    }

    protected MockHttpServletResponse deliver(byte[] body, String signature) throws Exception {
        var request = post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(body);
        if (signature != null) request.header(FakeProviderClient.SIGNATURE_HEADER, signature);
        return mockMvc.perform(request).andReturn().getResponse();
    }

    protected MockHttpServletResponse deliverSigned(String eventId, String paymentRef) throws Exception {
        byte[] b = webhookBody(eventId, paymentRef);
        return deliver(b, FakeProviderClient.sign(b));
    }

    protected String eventRowId(String eventId) {
        List<String> ids = jdbc.queryForList("select id from provider_event where provider = ? and event_id = ?", String.class, PROVIDER, eventId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    // ---- job runners with the real handlers ----

    /** A job runner with every real handler, on its own connection pool if {@code template} is given. */
    protected JobRunner runner(org.springframework.jdbc.core.JdbcTemplate template, Clock runnerClock) {
        List<JobHandler<?>> handlers = new ArrayList<>();
        for (Object h : context.getBeansOfType(JobHandler.class).values()) handlers.add((JobHandler<?>) h);
        JobRunner r = new JobRunner(template == null ? jdbc : template, handlers, mapper, 4, runnerClock == null ? Clock.systemUTC() : runnerClock);
        runners.add(r);
        return r;
    }

    /** Polls until no runnable job of these types is left (deadline 60 s); returns the number of claims made. */
    protected int drain(JobRunner r, String... types) throws Exception {
        int claims = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            List<Future<?>> started = r.pollOnce();
            for (Future<?> f : started) {
                try {
                    f.get(60, TimeUnit.SECONDS);
                } catch (ExecutionException expected) {
                    // a handler failure is recorded on the job row
                }
            }
            claims += started.size();
            if (started.isEmpty() && runnable(types) == 0) return claims;
            // AGF R4-16-D1: a paused provider's due jobs wait out the pause; draining stops instead of timing out
            if (started.isEmpty() && (r.isPaused(PROVIDER) || r.isPaused("FAKE_US"))) return claims;
            if (started.isEmpty()) sleep(20);
        }
        throw new AssertionError("jobs still runnable after 60 s: " + jdbc.queryForList("select type, status, run_at from job where status in ('PENDING', 'RUNNING')"));
    }

    protected int runnable(String... types) {
        return count("select count(*) from job where status = 'PENDING' and run_at <= now() and claims < 8 and type = any (?::text[])",
                (Object) ("{" + String.join(",", types) + "}"));
    }

    // ---- owner views ----

    protected JsonNode planView(Tenant t) throws Exception {
        MockHttpServletResponse r = mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(t))).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        return mapper.readTree(r.getContentAsString());
    }

    // ---- fixtures ----

    /** A PROVIDER Model 4 row whose paid period ends {@code untilEnd} from now; returns its id. */
    protected String paidRow(Tenant t, Duration untilEnd) {
        Instant periodEnd = micros(Instant.now().plus(untilEnd));
        Instant tPay = periodEnd.atZone(java.time.ZoneOffset.UTC).minusMonths(1).toInstant();
        String c = checkout(t, CheckoutKind.NEW, null);
        assertThat(process(event(paid(c, tPay)))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        return jdbc.queryForObject("select id from business_subscription where business_id = ? and status = 'ACTIVE'", String.class, t.businessId());
    }

    protected Instant periodEnd(String sub) {
        return jdbc.queryForObject("select period_end from business_subscription where id = ?", Timestamp.class, sub).toInstant();
    }

    /** A paid downgrade (Model 4 → Model 3) at the row's period end. Fixture, as in SUB-R4-15: no D1-eligible pair exists today. */
    protected String paidDowngrade(Tenant t, String sub) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into scheduled_change (id, business_id, from_subscription_id, kind, target_plan_code, target_price_id, effective_at, status) "
                + "values (?, ?, ?, 'DOWNGRADE', 'MODEL_3', ?, ?, 'PENDING')", id, t.businessId(), sub, price3, Timestamp.from(periodEnd(sub)));
        return id;
    }

    /** The downgrade-renewal checkout made for {@code change}. */
    protected String downgradeCheckout(Tenant t, String change, String sub) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into checkout_session (id, business_id, owner_user_id, price_id, config_version, kind, replaces_subscription_id, provider, provider_session_ref, provider_session_url, "
                        + "status, idempotency_key, request_hash, expires_at, expected_amount_minor, expected_currency, created_at, scheduled_change_id) "
                        + "values (?, ?, ?, ?, 3, 'DOWNGRADE_RENEWAL', ?, ?, ?, 'https://fake.test/pay', 'OPEN', ?, 'hash', ?, 1500, 'ETB', now(), ?)",
                id, t.businessId(), t.owner().getId(), price3, sub, PROVIDER, "sess_" + id, UUID.randomUUID().toString(),
                Timestamp.from(Instant.now().plus(23, ChronoUnit.HOURS)), change);
        return id;
    }

    protected Map<String, Object> changeRow(String id) {
        return jdbc.queryForMap("select * from scheduled_change where id = ?", id);
    }

    protected Map<String, Object> subRow(String id) {
        return jdbc.queryForMap("select * from business_subscription where id = ?", id);
    }

    protected int currentRows(Tenant t) {
        return count("select count(*) from business_subscription where business_id = ? and status <> 'EXPIRED'", t.businessId());
    }

    protected static String outcome(Object result) {
        if (result instanceof Throwable e) return "refused:" + e.getClass().getSimpleName();
        if (result == null || result instanceof CharSequence || result instanceof Enum<?> || result instanceof Number) return String.valueOf(result);
        return result.getClass().getSimpleName();
    }
}
