package com.agafari.com.subscription;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.stream.Stream;

/**
 * AGF H2 regression: needs H2 on the test classpath, so it runs only by name with the demo profile
 * ({@code mvnw -Pdemo test -Dtest=ScheduledChangeInvalidationH2Check}). The class name keeps it out of the default suite.
 */
class ScheduledChangeInvalidationH2Check {

    @TestFactory
    Stream<DynamicTest> onH2() {
        return new ScheduledChangeInvalidationScenarios(new SingleConnectionDataSource("jdbc:h2:mem:invalidation;DB_CLOSE_DELAY=-1", "sa", "", true)).all();
    }
}
