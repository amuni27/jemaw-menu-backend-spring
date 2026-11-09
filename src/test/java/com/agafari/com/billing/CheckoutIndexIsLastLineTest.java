package com.agafari.com.billing;

import com.agafari.com.service.MonetizationLocks;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF SUB-R4-05, NC-07: with the restaurant lock taken away, only the one-active index stands between concurrent creates.
 * A plain concurrency test can't detect the index's removal, because the lock already serializes those requests.
 */
class CheckoutIndexIsLastLineTest extends CheckoutTestBase {

    @MockitoBean private MonetizationLocks locks;

    @RepeatedTest(3)
    void theIndexAloneLeavesOneActiveCheckoutAndACleanConflict() throws Exception {
        var results = race(8, i -> () -> httpStatusWithConflictCode(owner, "key-" + i, otherOrSame(i), "CHECKOUT_ACTIVE"));
        assertThat(active()).isEqualTo(1);
        assertThat(results).doesNotContain(500);
        assertThat(results).allMatch(s -> s == 201 || s == 200 || s == 202 || s == 409);
    }

    private String otherOrSame(int i) {
        return i % 2 == 0 ? price : otherPrice;
    }

    @RepeatedTest(3)
    void theIdempotencyIndexAloneGivesOneRow() throws Exception {
        var results = race(8, i -> () -> httpStatus(owner, "same", price));
        assertThat(rows()).hasSize(1);
        assertThat(results).allMatch(s -> s == 201 || s == 200 || s == 202); // the same request is never refused
    }

    @Test
    void reservationTakesTheConfigurationLockBeforeTheRestaurantLock() throws Exception {
        httpStatus(owner, "k", price);
        var order = org.mockito.Mockito.inOrder(locks);
        order.verify(locks).configShared();
        order.verify(locks, org.mockito.Mockito.atLeastOnce()).restaurant(owner.businessId());
    }
}
