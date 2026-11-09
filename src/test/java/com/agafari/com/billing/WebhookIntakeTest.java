package com.agafari.com.billing;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agafari.com.billing.fake.FakeBillingConfig;
import com.agafari.com.billing.fake.FakeProviderClient;
import com.agafari.com.billing.webhook.WebhookController;
import com.agafari.com.jobs.JobQueue;
import com.agafari.com.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AGF SUB-R4-07: intake against PostgreSQL with the fake provider's HMAC. No authentication header is ever sent. */
@Import(FakeBillingConfig.class)
class WebhookIntakeTest extends AbstractIntegrationTest {

    private static final String ET = "/api/billing/webhooks/FAKE_ET";
    private static final String SIG = FakeProviderClient.SIGNATURE_HEADER;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private WebhookController controller;
    @Autowired private FakeProviderClient fakeEt;
    @Autowired private FakeProviderClient fakeUs;
    @Autowired private DataSource dataSource;
    @MockitoSpyBean private JobQueue jobs;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private String logged() {
        return String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    @BeforeEach
    void on() {
        appender.start();
        ((Logger) LoggerFactory.getLogger(WebhookController.class)).addAppender(appender);
        fakeEt.reset();
        fakeUs.reset();
        controller.setBillingEnabled(Market.ET, true);
        controller.setBillingEnabled(Market.US, true);
    }

    @AfterEach
    void off() {
        ((Logger) LoggerFactory.getLogger(WebhookController.class)).detachAppender(appender);
        controller.setBillingEnabled(Market.ET, false);
        controller.setBillingEnabled(Market.US, false);
    }

    private static byte[] body(String id) {
        return ("{\"id\":\"" + id + "\",\"type\":\"payment.success\",\"secretCard\":\"SENSITIVE-BODY\"}").getBytes(StandardCharsets.UTF_8);
    }

    private ResultActions send(String path, byte[] body, String signature) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (signature != null) request.header(SIG, signature);
        return mockMvc.perform(request);
    }

    private ResultActions sendSigned(byte[] body) throws Exception {
        return send(ET, body, FakeProviderClient.sign(body));
    }

    private int events() {
        return jdbc.queryForObject("select count(*) from provider_event", Integer.class);
    }

    private int jobCount() {
        return jdbc.queryForObject("select count(*) from job where type = 'inbox.process'", Integer.class);
    }

    @Test
    void aSignedEventIsStoredAndAcknowledged() throws Exception {
        sendSigned(body("e1")).andExpect(status().isOk());
        Map<String, Object> row = jdbc.queryForMap("select provider, event_id, type, status, attempts, payload::jsonb ->> 'secretCard' as field, received_at is not null as received, processed_at from provider_event");
        assertThat(row).containsEntry("provider", "FAKE_ET").containsEntry("event_id", "e1").containsEntry("type", "payment.success").containsEntry("status", "PENDING")
                .containsEntry("attempts", 0).containsEntry("field", "SENSITIVE-BODY").containsEntry("received", true).containsEntry("processed_at", null);
    }

    @Test
    void theEventAndItsJobAreLinked() throws Exception {
        sendSigned(body("e1"));
        Map<String, Object> job = jdbc.queryForMap("select key, status, payload ->> 'providerEventId' as event from job where type = 'inbox.process'");
        assertThat(job).containsEntry("key", "FAKE_ET:e1").containsEntry("status", "PENDING").containsEntry("event", jdbc.queryForObject("select id from provider_event", String.class));
    }

    @Test
    void ac1AnInvalidSignatureIsRefusedAndNothingStored() throws Exception {
        send(ET, body("e1"), "forged").andExpect(status().isBadRequest());
        assertThat(events()).isZero();
        assertThat(jobCount()).isZero();
    }

    @Test
    void ac1AMissingSignatureIsRefused() throws Exception {
        send(ET, body("e1"), null).andExpect(status().isBadRequest());
        assertThat(events()).isZero();
    }

    @Test
    void ac1ASignatureForAnotherBodyIsRefused() throws Exception {
        send(ET, body("e2"), FakeProviderClient.sign(body("e1"))).andExpect(status().isBadRequest());
        assertThat(events()).isZero();
    }

    @Test
    void anEmptyBodyIsRefused() throws Exception {
        mockMvc.perform(post(ET).header(SIG, "x")).andExpect(status().isBadRequest());
        assertThat(events()).isZero();
    }

    @Test
    void aRejectionIsLoggedWithoutTheBody() throws Exception {
        send(ET, body("e1"), "forged");
        assertThat(logged()).contains("event=billing.webhook_rejected provider=FAKE_ET reason=signature").doesNotContain("SENSITIVE-BODY");
    }

    @Test
    void aSignedButMalformedBodyIsRefused() throws Exception {
        byte[] body = "not json".getBytes(StandardCharsets.UTF_8);
        sendSigned(body).andExpect(status().isBadRequest());
        byte[] noId = "{\"type\":\"t\"}".getBytes(StandardCharsets.UTF_8);
        sendSigned(noId).andExpect(status().isBadRequest());
        byte[] noType = "{\"id\":\"e\"}".getBytes(StandardCharsets.UTF_8);
        sendSigned(noType).andExpect(status().isBadRequest());
        assertThat(events()).isZero();
        assertThat(logged()).contains("reason=malformed");
    }

    @Test
    void ac2ADuplicateEventIsAcknowledgedWithOneRowAndOneJob() throws Exception {
        sendSigned(body("e1")).andExpect(status().isOk());
        sendSigned(body("e1")).andExpect(status().isOk());
        assertThat(events()).isEqualTo(1);
        assertThat(jobCount()).isEqualTo(1);
    }

    @Test
    void ac2ConcurrentDuplicatesLeaveOneRowAndOneJob() throws Exception {
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> all = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                all.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return sendSigned(body("same")).andReturn().getResponse().getStatus();
                }));
            }
            for (Future<Integer> f : all) assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(events()).isEqualTo(1);
        assertThat(jobCount()).isEqualTo(1);
    }

    @Test
    void differentEventsAreStoredSeparately() throws Exception {
        sendSigned(body("e1"));
        sendSigned(body("e2"));
        assertThat(events()).isEqualTo(2);
        assertThat(jobCount()).isEqualTo(2);
    }

    @Test
    void theSameEventIdFromAnotherProviderIsItsOwnEvent() throws Exception {
        sendSigned(body("e1"));
        send("/api/billing/webhooks/FAKE_US", body("e1"), FakeProviderClient.sign(body("e1"))).andExpect(status().isOk());
        assertThat(events()).isEqualTo(2);
        assertThat(jobCount()).isEqualTo(2);
    }

    @Test
    void anotherProvidersSecretDoesNotVerify() throws Exception {
        fakeUs.secrets("us-secret", null);
        send("/api/billing/webhooks/FAKE_US", body("e1"), FakeProviderClient.sign(body("e1"), "fake-secret")).andExpect(status().isBadRequest());
        send("/api/billing/webhooks/FAKE_ET", body("e1"), FakeProviderClient.sign(body("e1"), "us-secret")).andExpect(status().isBadRequest());
        assertThat(events()).isZero();
    }

    @Test
    void ac3BothSecretsAreAcceptedDuringRotation() throws Exception {
        fakeEt.secrets("new-secret", "old-secret");
        send(ET, body("e1"), FakeProviderClient.sign(body("e1"), "new-secret")).andExpect(status().isOk());
        send(ET, body("e2"), FakeProviderClient.sign(body("e2"), "old-secret")).andExpect(status().isOk());
        send(ET, body("e3"), FakeProviderClient.sign(body("e3"), "other-secret")).andExpect(status().isBadRequest());
        assertThat(events()).isEqualTo(2);
    }

    @Test
    void afterRotationTheOldSecretIsRefused() throws Exception {
        fakeEt.secrets("new-secret", null);
        send(ET, body("e1"), FakeProviderClient.sign(body("e1"), "old-secret")).andExpect(status().isBadRequest());
    }

    @Test
    void ac4NoProcessingHappensInTheRequest() throws Exception {
        sendSigned(body("e1")).andExpect(status().isOk());
        assertThat(fakeEt.verifyCalls()).isZero();
        assertThat(jdbc.queryForObject("select status from provider_event", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select status from job where type = 'inbox.process'", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from checkout_session", Integer.class) + jdbc.queryForObject("select count(*) from payment", Integer.class)).isZero();
    }

    @Test
    void ac4ASlowRunningJobDoesNotSlowTheEndpoint() throws Exception {
        sendSigned(body("slow"));
        try (Connection held = dataSource.getConnection()) {
            held.setAutoCommit(false);
            held.createStatement().executeQuery("select * from job where type = 'inbox.process' for update").close();
            held.createStatement().executeQuery("select * from provider_event for update").close();
            long start = System.nanoTime();
            sendSigned(body("next")).andExpect(status().isOk());
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(5));
            held.rollback();
        }
        assertThat(events()).isEqualTo(2);
    }

    @Test
    void theEventAndItsJobCommitTogether() throws Exception {
        Mockito.doThrow(new IllegalStateException("boom")).when(jobs).enqueue(eq("inbox.process"), anyString(), any());
        try {
            sendSigned(body("e1")).andExpect(status().is5xxServerError());
        } finally {
            Mockito.reset(jobs);
        }
        assertThat(events()).isZero();
        sendSigned(body("e1")).andExpect(status().isOk());
        assertThat(events()).isEqualTo(1);
    }

    @Test
    void withBillingOffTheRouteDoesNotExist() throws Exception {
        off();
        sendSigned(body("e1")).andExpect(status().isNotFound());
        assertThat(events()).isZero();
    }

    @Test
    void billingIsPerMarket() throws Exception {
        controller.setBillingEnabled(Market.ET, false);
        sendSigned(body("e1")).andExpect(status().isNotFound());
        send("/api/billing/webhooks/FAKE_US", body("e1"), FakeProviderClient.sign(body("e1"))).andExpect(status().isOk());
    }

    @Test
    void anUnknownProviderLooksLikeNoRoute() throws Exception {
        send("/api/billing/webhooks/STRIPE", body("e1"), FakeProviderClient.sign(body("e1"))).andExpect(status().isNotFound());
        assertThat(events()).isZero();
    }

    @Test
    void itsRateLimitIsItsOwnBucket() throws Exception {
        for (int i = 0; i < 600; i++) send(ET, body("x"), "forged").andExpect(status().isBadRequest());
        send(ET, body("x"), "forged").andExpect(status().isTooManyRequests());
        assertThat(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@example.test\",\"password\":\"wrong-password\"}")).andReturn().getResponse().getStatus()).isNotEqualTo(429);
    }

    @Test
    void theOtherBillingRoutesStillNeedAnOwner() throws Exception {
        assertThat(mockMvc.perform(post("/api/billing/checkouts").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus()).isIn(401, 403);
    }
}
