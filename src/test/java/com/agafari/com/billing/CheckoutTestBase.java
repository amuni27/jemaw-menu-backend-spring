package com.agafari.com.billing;

import com.agafari.com.billing.fake.FakeBillingConfig;
import com.agafari.com.billing.fake.FakeProviderClient;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.agafari.com.support.MonetizationFixtures.config;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Amounts are fixtures, not prices (AD-10 is open). */
@Import(FakeBillingConfig.class)
abstract class CheckoutTestBase extends AbstractIntegrationTest {

    protected static final long ET_AMOUNT = 2500;

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected CheckoutService checkouts;
    @Autowired protected FakeProviderClient fakeEt;
    @Autowired protected FakeProviderClient fakeUs;
    @Autowired protected MonetizationClock clock;

    protected User admin;
    protected Tenant owner;
    protected String price;
    protected String otherPrice;

    @BeforeEach
    void setUpCheckout() {
        fakeEt.reset();
        fakeUs.reset();
        clock.reset();
        admin = data.admin("checkout-admin");
        owner = data.tenant("checkout-owner");
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        price = price("MODEL_4", "ET", "ETB", ET_AMOUNT);
        otherPrice = price("MODEL_2", "ET", "ETB", 700);
        checkouts.setBillingEnabled(Market.ET, true);
        checkouts.setBillingEnabled(Market.US, true);
    }

    @AfterEach
    void tearDownCheckout() {
        checkouts.setBillingEnabled(Market.ET, false);
        checkouts.setBillingEnabled(Market.US, false);
        clock.reset();
    }

    private int version;

    protected String price(String plan, String market, String currency, long amount) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into plan_price (id, plan_code, market, currency, billing_interval, amount_minor, version, active, valid_from) values (?, ?, ?, ?, 'MONTH', ?, ?, true, now() - interval '1 day')", id, plan, market, currency, amount, ++version);
        return id;
    }

    protected MockHttpServletRequestBuilder create(String key, String priceId) {
        MockHttpServletRequestBuilder b = post("/api/billing/checkouts").contentType(MediaType.APPLICATION_JSON).content("{\"priceId\":\"" + priceId + "\"}");
        return key == null ? b : b.header("Idempotency-Key", key);
    }

    protected ResultActions perform(Tenant t, String key, String priceId) throws Exception {
        return mockMvc.perform(create(key, priceId).header("Authorization", data.bearer(t)));
    }

    protected int httpStatus(Tenant t, String key, String priceId) throws Exception {
        return perform(t, key, priceId).andReturn().getResponse().getStatus();
    }

    /** The status, or -1 for a 409 without this code. */
    protected int httpStatusWithConflictCode(Tenant t, String key, String priceId, String code) throws Exception {
        var response = perform(t, key, priceId).andReturn().getResponse();
        return response.getStatus() == 409 && !response.getContentAsString().contains(code) ? -1 : response.getStatus();
    }

    protected List<Map<String, Object>> rows() {
        return jdbc.queryForList("select * from checkout_session order by created_at");
    }

    protected Map<String, Object> only() {
        List<Map<String, Object>> all = rows();
        if (all.size() != 1) throw new AssertionError("expected one checkout, found " + all.size());
        return all.get(0);
    }

    protected int active() {
        return jdbc.queryForObject("select count(*) from checkout_session where status in ('CREATING', 'OPEN')", Integer.class);
    }

    protected List<Integer> race(int threads, java.util.function.IntFunction<Callable<Integer>> work) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> all = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Integer> c = work.apply(i);
                all.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return c.call();
                }));
            }
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> f : all) results.add(f.get(60, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
