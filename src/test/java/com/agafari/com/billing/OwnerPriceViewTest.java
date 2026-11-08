package com.agafari.com.billing;

import com.agafari.com.dto.request.PlanPriceCreateRequest;
import com.agafari.com.dto.response.PlanPriceResponse;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.PlanPriceService;
import com.agafari.com.service.PlanRequestService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** AGF SUB-R3-02: every amount is a fixture, not a price (AD-10 is open). */
class OwnerPriceViewTest extends AbstractIntegrationTest {

    private static final List<String> BASELINE_KEYS = List.of("visible", "setting", "configVersion", "plans", "canRequest", "trialAvailable", "featuresUnaffected");

    @Autowired private PlanPriceService prices;
    @Autowired private PlanRequestService planRequests;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;

    private User admin;
    private Tenant us;
    private Tenant et;

    @BeforeEach
    void setUp() {
        admin = data.admin("view-admin");
        et = data.tenant("view-et");
        us = data.tenant("view-us");
        jdbc.update("update business set country = 'US' where id = ?", us.businessId());
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        planRequests.setOwnerPlansVisible(true);
    }

    @AfterEach
    void reset() {
        planRequests.setOwnerPlansVisible(false);
        prices.setPricesVisible(Market.US, false);
        prices.setPricesVisible(Market.ET, false);
    }

    private PlanPriceResponse draft(String plan, String market, String interval, long amount, Instant from) {
        return prices.createVersion(new PlanPriceCreateRequest(plan, market, interval, BigDecimal.valueOf(amount), from, null), admin.getId(), "a@example.test");
    }

    private PlanPriceResponse active(String plan, String market, String interval, long amount) {
        return prices.activate(draft(plan, market, interval, amount, null).id(), admin.getId(), "a@example.test");
    }

    private String body(Tenant t, String query) throws Exception {
        return mockMvc.perform(get("/api/plan" + query).header("Authorization", data.bearer(t))).andReturn().getResponse().getContentAsString();
    }

    private JsonNode view(Tenant t) throws Exception {
        return mapper.readTree(body(t, ""));
    }

    private static List<String> keys(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.properties().forEach(e -> names.add(e.getKey()));
        return names;
    }

    // ---- AC1: flag off

    @Test
    void withTheFlagOffTheResponseHasExactlyTheBaselineFields() throws Exception {
        active("MODEL_4", "ET", "MONTH", 1000);
        assertThat(keys(view(et))).isEqualTo(BASELINE_KEYS);
    }

    @Test
    void withTheFlagOffCreatingPricesDoesNotChangeTheResponseByAByte() throws Exception {
        String before = body(et, "");
        active("MODEL_4", "ET", "MONTH", 1000);
        assertThat(body(et, "")).isEqualTo(before);
    }

    @Test
    void anotherMarketsFlagDoesNotChangeTheResponse() throws Exception {
        String before = body(et, "");
        active("MODEL_4", "US", "MONTH", 1000);
        prices.setPricesVisible(Market.US, true);
        assertThat(body(et, "")).isEqualTo(before);
    }

    // ---- AC2: flag on

    @Test
    void anEthiopianRestaurantSeesOnlyItsMarketsPrices() throws Exception {
        active("MODEL_4", "ET", "MONTH", 1000);
        active("MODEL_4", "US", "MONTH", 2000);
        prices.setPricesVisible(Market.ET, true);
        JsonNode list = view(et).get("prices");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("currency").asString()).isEqualTo("ETB");
        assertThat(list.get(0).get("amountMinor").asLong()).isEqualTo(1000);
    }

    @Test
    void aUsRestaurantSeesOnlyItsMarketsPrices() throws Exception {
        active("MODEL_4", "ET", "MONTH", 1000);
        PlanPriceResponse usd = active("MODEL_4", "US", "MONTH", 2000);
        prices.setPricesVisible(Market.US, true);
        JsonNode list = view(us).get("prices");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("id").asString()).isEqualTo(usd.id());
        assertThat(list.get(0).get("planCode").asString()).isEqualTo("MODEL_4");
        assertThat(list.get(0).get("interval").asString()).isEqualTo("MONTH");
        assertThat(list.get(0).get("currency").asString()).isEqualTo("USD");
    }

    @Test
    void theFlagIsPerMarket() throws Exception {
        active("MODEL_4", "ET", "MONTH", 1000);
        active("MODEL_4", "US", "MONTH", 2000);
        prices.setPricesVisible(Market.US, true);
        assertThat(view(et).has("prices")).isFalse();
        assertThat(view(us).has("prices")).isTrue();
    }

    @Test
    void aClientSuppliedMarketIsIgnored() throws Exception {
        active("MODEL_4", "ET", "MONTH", 1000);
        active("MODEL_4", "US", "MONTH", 2000);
        prices.setPricesVisible(Market.ET, true);
        JsonNode list = mapper.readTree(body(et, "?market=US&country=US&currency=USD")).get("prices");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("currency").asString()).isEqualTo("ETB");
    }

    @Test
    void onlyEnabledModelsArePriced() throws Exception {
        active("MODEL_3", "ET", "MONTH", 1500);
        active("MODEL_4", "ET", "MONTH", 1000);
        prices.setPricesVisible(Market.ET, true);
        JsonNode list = view(et).get("prices");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("planCode").asString()).isEqualTo("MODEL_4");
    }

    @Test
    void aDraftIsNotShown() throws Exception {
        draft("MODEL_4", "ET", "MONTH", 1000, null);
        prices.setPricesVisible(Market.ET, true);
        assertThat(view(et).get("prices")).isEmpty();
    }

    @Test
    void aFutureActiveVersionIsNotShownYet() throws Exception {
        active("MODEL_4", "ET", "MONTH", 1000);
        PlanPriceResponse later = draft("MODEL_4", "ET", "MONTH", 2000, Instant.now().plus(5, ChronoUnit.DAYS));
        prices.activate(later.id(), admin.getId(), "a@example.test");
        prices.setPricesVisible(Market.ET, true);
        JsonNode list = view(et).get("prices");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("amountMinor").asLong()).isEqualTo(1000);
    }

    @Test
    void onlyTheNewestActiveVersionIsShownPerInterval() throws Exception {
        active("MODEL_4", "ET", "MONTH", 1000);
        active("MODEL_4", "ET", "MONTH", 1100);
        active("MODEL_4", "ET", "YEAR", 9000);
        draft("MODEL_4", "ET", "MONTH", 1200, null);
        prices.setPricesVisible(Market.ET, true);
        JsonNode list = view(et).get("prices");
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("interval").asString()).isEqualTo("MONTH");
        assertThat(list.get(0).get("amountMinor").asLong()).isEqualTo(1100);
        assertThat(list.get(1).get("interval").asString()).isEqualTo("YEAR");
    }

    @Test
    void readingDuringNewVersionsAlwaysShowsOnePricePerInterval() throws Exception {
        active("MODEL_4", "ET", "MONTH", 1000);
        prices.setPricesVisible(Market.ET, true);
        int threads = 6;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> all = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                boolean writer = i % 2 == 0;
                int amount = 1100 + i;
                all.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    if (writer) {
                        active("MODEL_4", "ET", "MONTH", amount);
                        return 1;
                    }
                    return view(et).get("prices").size();
                }));
            }
            for (Future<Integer> f : all) assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void existingFieldsAreUnchangedWithTheFlagOn() throws Exception {
        String off = body(et, "");
        prices.setPricesVisible(Market.ET, true);
        JsonNode on = view(et);
        assertThat(on.get("plans")).isEqualTo(mapper.readTree(off).get("plans"));
        assertThat(on.get("canRequest")).isEqualTo(mapper.readTree(off).get("canRequest"));
    }

    @Test
    void aRestaurantWhoseStoredCountryIsNotAMarketSeesNoPrices() throws Exception {
        active("MODEL_4", "ET", "MONTH", 1000);
        active("MODEL_4", "US", "MONTH", 2000);
        prices.setPricesVisible(Market.ET, true);
        prices.setPricesVisible(Market.US, true);
        jdbc.update("update business set country = 'GB' where id = ?", et.businessId());
        assertThat(view(et).has("prices")).isFalse();
    }

    @Test
    void theOwnersFlagAloneDoesNotOpenPrices() throws Exception {
        planRequests.setOwnerPlansVisible(false);
        prices.setPricesVisible(Market.ET, true);
        assertThat(view(et).has("prices")).isFalse();
        assertThat(view(et).get("visible").asBoolean()).isFalse();
    }
}
