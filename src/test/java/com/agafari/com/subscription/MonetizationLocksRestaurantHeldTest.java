package com.agafari.com.subscription;

import com.agafari.com.service.MonetizationLocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AGF SUB-R2-04: trial expiry holds only the restaurant lock today, so it has its own narrower check. */
class MonetizationLocksRestaurantHeldTest {

    private MonetizationLocks locks;

    @BeforeEach
    void setUp() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection c = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(ds.getConnection()).thenReturn(c);
        when(c.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
        locks = new MonetizationLocks(mock(JdbcTemplate.class), ds);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void acceptedWithTheRestaurantLockAlone() {
        locks.restaurant("b1");
        assertThatCode(() -> locks.assertRestaurantHeld("b1")).doesNotThrowAnyException();
    }

    @Test
    void refusedWithoutItOrWithAnotherRestaurantsLock() {
        assertThatThrownBy(() -> locks.assertRestaurantHeld("b1")).isInstanceOf(IllegalStateException.class);
        locks.configShared();
        locks.restaurant("other");
        assertThatThrownBy(() -> locks.assertRestaurantHeld("b1")).isInstanceOf(IllegalStateException.class).hasMessageContaining("restaurant");
    }
}
