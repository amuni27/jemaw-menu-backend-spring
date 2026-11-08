package com.agafari.com.config;

import com.agafari.com.config.BillingFlags.MarketFlags;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** AGF SUB-R2-12: the Migration §6 matrix as a pure rule. */
class FlagMatrixTest {

    private static final MarketFlags OFF = new MarketFlags(false, false, false, false);
    private static final FlagFacts ALL = new FlagFacts(true, true, true, true, true, true);
    private static final FlagFacts TODAY = new FlagFacts(true, false, false, false, false, false);

    private static BillingFlags flags(boolean periodEnd, boolean pd9, boolean ack, MarketFlags us) {
        return new BillingFlags(periodEnd, pd9, ack, us, OFF);
    }

    private static MarketFlags prices() { return new MarketFlags(true, false, false, false); }
    private static MarketFlags billing() { return new MarketFlags(true, true, false, false); }
    private static MarketFlags publish() { return new MarketFlags(false, false, true, false); }
    private static MarketFlags guest() { return new MarketFlags(false, false, true, true); }

    private static List<String> check(BillingFlags f, FlagFacts facts) {
        return FlagMatrix.violations(f, false, facts);
    }

    @Test
    void theDefaultsAreValidEvenOnH2() {
        BillingFlags defaults = new BillingFlags(false, false, false, OFF, OFF);
        assertThat(FlagMatrix.violations(defaults, false, new FlagFacts(false, false, false, false, false, false))).isEmpty();
    }

    @Test
    void thePd9MarkerAloneEnablesNothing() {
        assertThat(check(flags(false, true, true, OFF), TODAY)).isEmpty();
    }

    @Test
    void periodEndNeedsTheReportAcknowledged() {
        assertThat(check(flags(true, false, false, OFF), TODAY)).singleElement().asString().contains("period-end-enabled").contains("acknowledged");
        assertThat(check(flags(true, false, true, OFF), TODAY)).isEmpty();
    }

    @Test
    void periodEndIsRefusedOnH2() {
        assertThat(check(flags(true, false, true, OFF), new FlagFacts(false, false, false, false, false, false))).singleElement().asString().contains("PostgreSQL");
    }

    @Test
    void pricesNeedPrices() {
        assertThat(check(flags(false, false, false, prices()), TODAY)).singleElement().asString().contains("US").contains("prices-visible");
        assertThat(check(flags(false, false, false, prices()), ALL)).isEmpty();
    }

    @Test
    void aValidBillingCombinationPasses() {
        assertThat(check(flags(true, true, true, billing()), ALL)).isEmpty();
    }

    @Test
    void billingNeedsPrices() {
        assertThat(check(flags(true, true, true, new MarketFlags(false, true, false, false)), ALL)).singleElement().asString().contains("billing-enabled").contains("prices-visible");
    }

    @Test
    void billingNeedsPeriodEnd() {
        assertThat(check(flags(false, true, true, billing()), ALL)).singleElement().asString().contains("billing-enabled").contains("period-end-enabled");
    }

    @Test
    void billingNeedsProviderSecrets() {
        assertThat(check(flags(true, true, true, billing()), new FlagFacts(true, true, false, true, true, true))).singleElement().asString().contains("billing-enabled").contains("secrets");
    }

    @Test
    void billingNeedsPd9() {
        assertThat(check(flags(true, false, true, billing()), ALL)).singleElement().asString().contains("billing-enabled").contains("PD-9");
    }

    @Test
    void billingIsRefusedOnH2() {
        List<String> found = check(flags(true, true, true, billing()), new FlagFacts(false, true, true, true, true, true));
        assertThat(found).anyMatch(v -> v.contains("billing-enabled") && v.contains("PostgreSQL"));
    }

    @Test
    void aValidPublishCombinationPasses() {
        assertThat(check(flags(true, false, true, publish()), ALL)).isEmpty();
    }

    @Test
    void publishNeedsPeriodEnd() {
        assertThat(check(flags(false, false, false, publish()), ALL)).singleElement().asString().contains("publish-requires-plan").contains("period-end-enabled");
    }

    @Test
    void publishNeedsASignedDryRun() {
        assertThat(check(flags(true, false, true, publish()), new FlagFacts(true, true, true, false, true, true))).singleElement().asString().contains("publish-requires-plan").contains("dry run");
    }

    @Test
    void publishIsRefusedOnH2() {
        List<String> found = check(flags(true, false, true, publish()), new FlagFacts(false, true, true, true, true, true));
        assertThat(found).anyMatch(v -> v.contains("publish-requires-plan") && v.contains("PostgreSQL"));
    }

    @Test
    void aValidGuestCombinationPasses() {
        assertThat(check(flags(true, false, true, guest()), ALL)).isEmpty();
    }

    @Test
    void guestNeedsPublish() {
        assertThat(check(flags(true, false, true, new MarketFlags(false, false, false, true)), ALL)).singleElement().asString().contains("guest-enforcement").contains("publish-requires-plan");
    }

    /** NC-11. */
    @Test
    void guestNeedsTheLegacyRun() {
        assertThat(check(flags(true, false, true, guest()), new FlagFacts(true, true, true, true, false, true))).singleElement().asString().contains("guest-enforcement").contains("legacy run");
    }

    @Test
    void guestNeedsUnexplainedPublishedCleared() {
        assertThat(check(flags(true, false, true, guest()), new FlagFacts(true, true, true, true, true, false))).singleElement().asString().contains("guest-enforcement").contains("unexplained");
    }

    @Test
    void guestIsRefusedOnH2() {
        List<String> found = check(flags(true, false, true, guest()), new FlagFacts(false, true, true, true, true, true));
        assertThat(found).anyMatch(v -> v.contains("guest-enforcement") && v.contains("PostgreSQL"));
    }

    @Test
    void pricesAreCheckedPerMarket() {
        FlagFacts onlyUs = new FlagFacts(true, true, false, false, false, false, false);
        assertThat(check(new BillingFlags(false, false, false, prices(), OFF), onlyUs)).isEmpty();
        assertThat(check(new BillingFlags(false, false, false, OFF, prices()), onlyUs)).singleElement().asString().contains("ET");
    }

    @Test
    void marketsAreCheckedIndependently() {
        BillingFlags et = new BillingFlags(false, false, false, OFF, prices());
        assertThat(check(et, TODAY)).singleElement().asString().contains("ET");
        assertThat(check(new BillingFlags(false, false, false, prices(), OFF), ALL)).isEmpty();
    }

    @Test
    void jobsAreRefusedOnH2Only() {
        BillingFlags off = new BillingFlags(false, false, false, OFF, OFF);
        assertThat(FlagMatrix.violations(off, true, TODAY)).isEmpty();
        assertThat(FlagMatrix.violations(off, true, new FlagFacts(false, false, false, false, false, false))).singleElement().asString().contains("app.jobs.enabled").contains("PostgreSQL");
    }

    @Test
    void everyViolationIsReportedTogether() {
        assertThat(check(flags(false, false, false, billing()), TODAY)).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void todaysFactsRefuseEveryFlagThatNeedsAFutureStory() {
        assertThat(check(flags(false, false, false, prices()), TODAY)).isNotEmpty();
        assertThat(check(flags(true, true, true, billing()), TODAY)).isNotEmpty();
        assertThat(check(flags(true, false, true, publish()), TODAY)).isNotEmpty();
        assertThat(check(flags(true, false, true, guest()), TODAY)).isNotEmpty();
    }
}
