package com.agafari.com.billing;

import com.agafari.com.dto.request.PlanPriceCreateRequest;
import com.agafari.com.dto.response.PlanPriceResponse;
import com.agafari.com.exception.FieldValidationException;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.PlanPriceService;
import com.agafari.com.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AGF SUB-R3-01: price versions. Every amount is a fixture, not a price (AD-10 is open). */
class PlanPriceServiceTest extends AbstractIntegrationTest {

    private static final BigDecimal FIXTURE_AMOUNT = new BigDecimal("1000");

    @Autowired private PlanPriceService service;
    @Autowired private JdbcTemplate jdbc;

    private User admin;

    @BeforeEach
    void setUp() {
        admin = data.admin("prices-admin");
    }

    private PlanPriceCreateRequest request(String plan, String market, String interval, BigDecimal amount, Instant from) {
        return new PlanPriceCreateRequest(plan, market, interval, amount, from, null);
    }

    private PlanPriceResponse create(String plan, String market) {
        return service.createVersion(request(plan, market, "MONTH", FIXTURE_AMOUNT, null), admin.getId(), "a@example.test");
    }

    private PlanPriceResponse activate(String id) {
        return service.activate(id, admin.getId(), "a@example.test");
    }

    private int audits(String action, String target) {
        return jdbc.queryForObject("select count(*) from admin_audit_event where action = ? and target_id = ?", Integer.class, action, target);
    }

    private void enable(boolean m2, boolean m3, boolean m4) {
        config(jdbc, 1, "SETTING_2", m2, m3, m4, admin.getId());
    }

    @Test
    void aNewVersionStartsInactiveWithTheMarketsCurrencyAndIsAudited() {
        PlanPriceResponse r = create("MODEL_4", "ET");
        assertThat(r.version()).isEqualTo(1);
        assertThat(r.active()).isFalse();
        assertThat(r.currency()).isEqualTo("ETB");
        assertThat(r.amountMinor()).isEqualTo(1000);
        assertThat(audits("PLAN_PRICE_VERSION_CREATED", r.id())).isEqualTo(1);
    }

    @Test
    void versionsCountUpPerPlanMarketAndInterval() {
        assertThat(create("MODEL_4", "US").version()).isEqualTo(1);
        assertThat(create("MODEL_4", "US").version()).isEqualTo(2);
        assertThat(create("MODEL_4", "ET").version()).isEqualTo(1);
        assertThat(service.createVersion(request("MODEL_4", "US", "YEAR", FIXTURE_AMOUNT, null), admin.getId(), "a@example.test").version()).isEqualTo(1);
    }

    @Test
    void activationAddsAnActiveVersionAndLeavesTheOriginalAlone() {
        PlanPriceResponse draft = create("MODEL_4", "US");
        PlanPriceResponse active = activate(draft.id());
        assertThat(active.version()).isEqualTo(2);
        assertThat(active.active()).isTrue();
        assertThat(active.amountMinor()).isEqualTo(draft.amountMinor());
        assertThat(jdbc.queryForObject("select active from plan_price where id = ?", Boolean.class, draft.id())).isFalse();
        assertThat(audits("PLAN_PRICE_ACTIVATED", active.id())).isEqualTo(1);
    }

    @Test
    void anActiveVersionCantBeActivatedAgain() {
        PlanPriceResponse active = activate(create("MODEL_4", "US").id());
        assertThatThrownBy(() -> activate(active.id())).isInstanceOf(FieldValidationException.class);
    }

    @Test
    void anUnknownPriceIsNotFound() {
        assertThatThrownBy(() -> activate("nope")).isInstanceOf(com.agafari.com.exception.NotFoundException.class);
    }

    @Test
    void aFreePlanHasNoPrice() {
        assertThatThrownBy(() -> create("MODEL_2", "US")).isInstanceOf(FieldValidationException.class);
    }

    @Test
    void invalidRequestsAreRefused() {
        assertThatThrownBy(() -> create("NOPE", "US")).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> create("MODEL_4", "GB")).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.createVersion(request("MODEL_4", "US", "WEEK", FIXTURE_AMOUNT, null), admin.getId(), "a")).isInstanceOf(FieldValidationException.class);
        for (String amount : new String[]{"0", "-1", "12.5", "12.0"}) {
            assertThatThrownBy(() -> service.createVersion(request("MODEL_4", "US", "MONTH", new BigDecimal(amount), null), admin.getId(), "a")).as(amount).isInstanceOf(FieldValidationException.class);
        }
        assertThatThrownBy(() -> service.createVersion(request("MODEL_4", "US", "MONTH", null, null), admin.getId(), "a")).isInstanceOf(FieldValidationException.class);
        assertThat(jdbc.queryForObject("select count(*) from plan_price", Integer.class)).isZero();
    }

    @Test
    void concurrentCreatesGetDistinctVersions() throws Exception {
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Set<Integer> versions = new HashSet<>();
        try {
            List<Future<PlanPriceResponse>> all = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                all.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return create("MODEL_4", "US");
                }));
            }
            for (Future<PlanPriceResponse> f : all) versions.add(f.get(30, TimeUnit.SECONDS).version());
        } finally {
            pool.shutdownNow();
        }
        assertThat(versions).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8);
    }

    // ---- availability (Migration §6)

    @Test
    void noConfigurationMeansNotAvailable() {
        assertThat(service.availableIn(Market.US)).isFalse();
    }

    @Test
    void everyEnabledPaidModelNeedsAnActivePriceInThatMarket() {
        enable(true, true, true);
        activate(create("MODEL_3", "US").id());
        assertThat(service.availableIn(Market.US)).as("MODEL_4 has none").isFalse();
        activate(create("MODEL_4", "US").id());
        assertThat(service.availableIn(Market.US)).isTrue();
        assertThat(service.availableIn(Market.ET)).as("another market").isFalse();
    }

    @Test
    void aFreeOrDisabledModelNeedsNoPrice() {
        enable(true, false, true);
        activate(create("MODEL_4", "US").id());
        assertThat(service.availableIn(Market.US)).isTrue();
    }

    @Test
    void anInactiveVersionDoesNotCount() {
        enable(true, false, true);
        create("MODEL_4", "US");
        assertThat(service.availableIn(Market.US)).isFalse();
    }

    @Test
    void aFutureVersionDoesNotCountYet() {
        enable(true, false, true);
        PlanPriceResponse later = service.createVersion(request("MODEL_4", "US", "MONTH", FIXTURE_AMOUNT, Instant.now().plus(5, ChronoUnit.DAYS)), admin.getId(), "a@example.test");
        activate(later.id());
        assertThat(service.availableIn(Market.US)).isFalse();
    }

    // ---- API

    @Test
    void theApiIsAdminOnlyAndRefusesAFractionalAmount() throws Exception {
        String bearer = data.adminBearer(admin);
        String body = "{\"planCode\":\"MODEL_4\",\"market\":\"US\",\"interval\":\"MONTH\",\"amountMinor\":%s}";
        mockMvc.perform(post("/api/admin/prices").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(body.formatted("12.5"))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/prices").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(body.formatted("1000")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.currency").value("USD")).andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(get("/api/admin/prices").header("Authorization", bearer)).andExpect(status().isOk()).andExpect(jsonPath("$[0].planCode").value("MODEL_4"));
        mockMvc.perform(get("/api/admin/prices").header("Authorization", data.bearer(data.tenant("price-owner")))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/prices").contentType(MediaType.APPLICATION_JSON).content(body.formatted("1000"))).andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(401, 403));
    }

    @Test
    void aClientCantChooseTheCurrency() throws Exception {
        String body = "{\"planCode\":\"MODEL_4\",\"market\":\"ET\",\"interval\":\"MONTH\",\"amountMinor\":1000,\"currency\":\"USD\"}";
        mockMvc.perform(post("/api/admin/prices").header("Authorization", data.adminBearer(admin)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.currency").value("ETB"));
    }

    @Test
    void theOwnerViewShowsNoPrices() throws Exception {
        activate(create("MODEL_4", "US").id());
        String json = mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(data.tenant("price-view")))).andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain("amountMinor");
    }
}
