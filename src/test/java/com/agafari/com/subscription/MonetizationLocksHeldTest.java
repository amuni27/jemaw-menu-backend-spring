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

/**
 * AGF SUB-R2-02 AC2: the single writer can verify that the transaction took the configuration lock and this restaurant's
 * lock. Unit level: the database is mocked and the transaction is simulated.
 */
class MonetizationLocksHeldTest {

    private MonetizationLocks locks;

    private MonetizationLocks locks(String product) throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection c = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(ds.getConnection()).thenReturn(c);
        when(c.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn(product);
        return new MonetizationLocks(mock(JdbcTemplate.class), ds);
    }

    private static void begin() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private static void complete() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
                s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            }
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @BeforeEach
    void setUp() throws Exception {
        locks = locks("PostgreSQL");
        begin();
    }

    @AfterEach
    void tearDown() {
        complete();
    }

    @Test
    void refusedWithoutAnyLock() {
        assertThatThrownBy(() -> locks.assertHeld("b1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusedWithOnlyTheConfigurationLock() {
        locks.configShared();
        assertThatThrownBy(() -> locks.assertHeld("b1")).isInstanceOf(IllegalStateException.class).hasMessageContaining("restaurant");
    }

    @Test
    void refusedWithOnlyTheRestaurantLock() {
        locks.restaurant("b1");
        assertThatThrownBy(() -> locks.assertHeld("b1")).isInstanceOf(IllegalStateException.class).hasMessageContaining("configuration");
    }

    @Test
    void refusedWhenAnotherRestaurantIsLocked() {
        locks.configShared();
        locks.restaurant("other");
        assertThatThrownBy(() -> locks.assertHeld("b1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptedWithBothLocks() {
        locks.configShared();
        locks.restaurant("b1");
        assertThatCode(() -> locks.assertHeld("b1")).doesNotThrowAnyException();
    }

    @Test
    void theExclusiveConfigurationLockAlsoCounts() {
        locks.configExclusive();
        locks.restaurant("b1");
        assertThatCode(() -> locks.assertHeld("b1")).doesNotThrowAnyException();
    }

    @Test
    void locksDoNotCarryOverToTheNextTransaction() {
        locks.configShared();
        locks.restaurant("b1");
        complete();
        begin();
        assertThatThrownBy(() -> locks.assertHeld("b1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSuspendedTransactionsLocksAreNotSeenByTheInnerOne() {
        locks.configShared();
        locks.restaurant("b1");
        TransactionSynchronization sync = TransactionSynchronizationManager.getSynchronizations().get(0);
        sync.suspend();
        assertThatThrownBy(() -> locks.assertHeld("b1")).isInstanceOf(IllegalStateException.class);
        sync.resume();
        assertThatCode(() -> locks.assertHeld("b1")).doesNotThrowAnyException();
    }

    @Test
    void refusedOutsideATransaction() {
        complete();
        assertThatThrownBy(() -> locks.assertHeld("b1")).isInstanceOf(IllegalStateException.class).hasMessageContaining("transaction");
    }

    @Test
    void theH2DemoSkipsLocksAndTheCheck() throws Exception {
        MonetizationLocks h2 = locks("H2");
        assertThatCode(() -> h2.assertHeld("b1")).doesNotThrowAnyException();
    }
}
