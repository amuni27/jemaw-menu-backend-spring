package com.agafari.com.config;

import com.agafari.com.billing.Market;
import com.agafari.com.billing.PriceAvailability;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * AGF SUB-R2-12: refuses to start with an invalid flag combination; runs before any scheduled work begins. There is no
 * admin toggle for these flags yet; one must call {@link #check}. Provider secrets and the legacy-run reports
 * don't exist yet, so the flags that need them are refused for now.
 */
@Component
@EnableConfigurationProperties(BillingFlags.class)
public class FlagMatrixValidator implements SmartInitializingSingleton {

    private final BillingFlags flags;
    private final DataSource dataSource;
    private final boolean jobsEnabled;
    private final ObjectProvider<PriceAvailability> prices;

    public FlagMatrixValidator(BillingFlags flags, DataSource dataSource, @Value("${app.jobs.enabled:false}") boolean jobsEnabled, ObjectProvider<PriceAvailability> prices) {
        this.prices = prices;
        this.flags = flags;
        this.dataSource = dataSource;
        this.jobsEnabled = jobsEnabled;
    }

    @Override
    public void afterSingletonsInstantiated() {
        check(flags, jobsEnabled);
    }

    /** Throws {@link FlagMatrixViolationException} if this candidate configuration can't run. */
    public void check(BillingFlags candidate, boolean candidateJobsEnabled) {
        List<String> violations = FlagMatrix.violations(candidate, candidateJobsEnabled, new FlagFacts(postgres(),
                candidate.us().pricesVisible() && available(Market.US), candidate.et().pricesVisible() && available(Market.ET), false, false, false, false));
        if (!violations.isEmpty()) throw new FlagMatrixViolationException(violations);
    }

    private boolean available(Market market) {
        PriceAvailability availability = prices.getIfAvailable();
        return availability != null && availability.availableIn(market);
    }

    private boolean postgres() {
        try (Connection c = dataSource.getConnection()) {
            return c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (SQLException e) {
            throw new IllegalStateException("Can't detect the database", e);
        }
    }
}
