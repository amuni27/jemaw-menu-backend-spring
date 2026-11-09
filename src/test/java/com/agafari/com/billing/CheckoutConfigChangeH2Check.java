package com.agafari.com.billing;

import com.agafari.com.service.MonetizationLocks;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

/** AGF SUB-R4-06: needs H2 on the classpath, so it runs by name with the demo profile. H2 has no checkout_session table here. */
class CheckoutConfigChangeH2Check {

    @Test
    void aConfigurationChangeOnH2DoesNotTouchTheBillingTables() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:h2:mem:checkoutcfg;DB_CLOSE_DELAY=-1", "sa", "", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        TransactionSynchronizationManager.setActualTransactionActive(true); // the configuration change always runs in one
        try {
            assertThatCode(() -> new CheckoutCancellation(jdbc, null, null, new MonetizationLocks(jdbc, ds)).requestForConfiguration(List.of("MODEL_2"), true)).doesNotThrowAnyException();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
