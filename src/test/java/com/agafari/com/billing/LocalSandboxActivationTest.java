package com.agafari.com.billing;

import com.agafari.com.config.BillingFlags;
import com.agafari.com.config.BillingFlags.MarketFlags;
import com.agafari.com.config.FlagFacts;
import com.agafari.com.config.FlagMatrixValidator;
import com.agafari.com.service.MonetizationClock;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGF B-L1 (register §93): the local-only Stripe sandbox exception. It never activates unless every one of its nine conditions
 * holds; if it is opted into and any condition fails, startup is refused (fail-closed). When active it lets US billing start
 * despite the open activation gates, for a limited time only; the gates themselves never change and stay reported.
 * Synthetic values only; no key-shaped literal in the source.
 */
class LocalSandboxActivationTest {

    private static final String TEST_KEY = "sk_" + "test_" + "SyntheticBL1Value00000000001";
    private static final String RESTRICTED_TEST_KEY = "rk_" + "test_" + "SyntheticBL1Value00000000002";
    private static final String LIVE_KEY = "sk_" + "live_" + "SyntheticBL1Value00000000003";
    private static final String RESTRICTED_LIVE_KEY = "rk_" + "live_" + "SyntheticBL1Value00000000004";
    private static final String PUBLISHABLE_KEY = "pk_" + "test_" + "SyntheticBL1Value00000000005";
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");

    /** A complete, valid local sandbox setup: command-line opt-in, loopback URLs, a sandbox database, US only. */
    private static final class Setup {
        final Map<String, Object> commandLine = new LinkedHashMap<>();
        final Map<String, Object> config = new LinkedHashMap<>();
        final Map<String, String> os = new HashMap<>();
        String[] profiles = {"local-sandbox"};

        Setup() {
            commandLine.put("app.billing.local-sandbox.enabled", "true");
            commandLine.put("app.billing.local-sandbox.acknowledged-until", NOW.plus(Duration.ofDays(3)).toString());
            config.put("app.billing.stripe.enabled", "true");
            config.put("app.billing.stripe.expected-account-id", "acct_" + "SyntheticBL1");
            config.put("app.billing.stripe.success-url", "http://127.0.0.1:5212/billing/return?session={CHECKOUT_SESSION_ID}");
            config.put("app.billing.stripe.cancel-url", "http://127.0.0.1:5212/billing/cancelled");
            config.put("app.billing.stripe.trusted-origins", "http://127.0.0.1:5212, http://localhost:5212");
            config.put("app.billing.stripe.portal-return-url", "http://localhost:5212/plans");
            config.put("app.public-menu-base-url", "http://127.0.0.1:5212");
            config.put("spring.datasource.url", "jdbc:postgresql://127.0.0.1:54409/agafari_sandbox_t1");
            config.put("app.billing.us.billing-enabled", "true");
            os.put("STRIPE_SECRET_KEY", TEST_KEY);
        }

        Setup with(Consumer<Setup> change) {
            change.accept(this);
            return this;
        }

        LocalSandboxActivation.Inputs inputs() {
            StandardEnvironment env = new StandardEnvironment();
            env.getPropertySources().addFirst(new MapPropertySource("applicationConfig: [test]", config));
            List<String> args = commandLine.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toList();
            env.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args.toArray(String[]::new)));
            env.setActiveProfiles(profiles);
            return LocalSandboxActivation.Inputs.from(env, os::get);
        }

        LocalSandboxActivation build() {
            return new LocalSandboxActivation(inputs(), clockAt(NOW));
        }
    }

    private static MonetizationClock clockAt(Instant at) {
        MonetizationClock clock = new MonetizationClock();
        clock.setOffset(Duration.between(Instant.now(), at));
        return clock;
    }

    private static void refused(Setup setup, String why) {
        assertThatThrownBy(setup::build).as(why).isInstanceOf(IllegalStateException.class).hasMessageContaining("local sandbox");
    }

    // ---- off unless explicitly opted in ----

    @Test
    void offByDefaultAndWithTheProfileAloneNothingChanges() {
        LocalSandboxActivation none = new Setup().with(s -> { s.commandLine.clear(); s.profiles = new String[0]; }).build();
        assertThat(none.active()).isFalse();
        assertThat(none.blocksNewCheckouts(Market.US)).as("an inactive sandbox never changes anything").isFalse();
        assertThat(none.blocksEvidence(Market.ET)).isFalse();
        assertThat(new Setup().with(s -> s.commandLine.clear()).build().active()).as("the profile without the opt-in").isFalse();
        assertThat(new Setup().with(s -> s.commandLine.put("app.billing.local-sandbox.enabled", "false")).build().active()).isFalse();
    }

    @Test
    void aCompleteLocalSetupActivatesForTheUsMarketOnlyAndTheGatesStayOpen() {
        LocalSandboxActivation sandbox = new Setup().build();
        assertThat(sandbox.active()).isTrue();
        assertThat(sandbox.blocksNewCheckouts(Market.US)).isFalse();
        assertThat(sandbox.blocksEvidence(Market.US)).isFalse();
        assertThat(sandbox.blocksNewCheckouts(Market.ET)).as("US only").isTrue();
        assertThat(sandbox.blocksEvidence(Market.ET)).isTrue();
        assertThat(ProviderActivationReadiness.ready()).as("the gates never change").isFalse();
        assertThat(ProviderActivationReadiness.openGates()).hasSize(8);
        assertThat(new Setup().with(s -> s.os.put("STRIPE_SECRET_KEY", RESTRICTED_TEST_KEY)).build().active()).as("a restricted test key").isTrue();
    }

    // ---- expiry (condition 9) ----

    @Test
    void theExceptionEndsAtItsAcknowledgedTimeWithoutARestart() {
        MonetizationClock clock = clockAt(NOW);
        LocalSandboxActivation sandbox = new LocalSandboxActivation(new Setup().inputs(), clock);
        Instant until = NOW.plus(Duration.ofDays(3));
        clock.setOffset(Duration.between(Instant.now(), until.minusSeconds(1)));
        assertThat(sandbox.paidOutsideWindow(clock.now())).isFalse();
        clock.setOffset(Duration.between(Instant.now(), until.plus(LocalSandboxActivation.DRAIN)));
        assertThat(sandbox.blocksNewCheckouts(Market.US)).as("expired").isTrue();
        assertThat(sandbox.blocksEvidence(Market.US)).isTrue();
    }

    // ---- expiry in phases (register §94): new checkouts stop first; evidence of earlier payments is still applied ----

    @Test
    void newCheckoutsStopAtTheCutoffBeforeTheAcknowledgedTime() {
        MonetizationClock clock = clockAt(NOW);
        LocalSandboxActivation sandbox = new LocalSandboxActivation(new Setup().inputs(), clock);
        Instant until = NOW.plus(Duration.ofDays(3));
        clock.setOffset(Duration.between(Instant.now(), until.minus(LocalSandboxActivation.CHECKOUT_CUTOFF).minusSeconds(1)));
        assertThat(sandbox.blocksNewCheckouts(Market.US)).isFalse();
        clock.setOffset(Duration.between(Instant.now(), until.minus(LocalSandboxActivation.CHECKOUT_CUTOFF)));
        assertThat(sandbox.blocksNewCheckouts(Market.US)).as("no checkout can be started that could be paid after the window").isTrue();
        assertThat(sandbox.blocksEvidence(Market.US)).as("evidence still flows").isFalse();
        assertThat(sandbox.blocksNewCheckouts(Market.ET)).isTrue();
        assertThat(sandbox.blocksEvidence(Market.ET)).isTrue();
    }

    @Test
    void aCheckoutNeverOutlivesTheWindow() {
        LocalSandboxActivation sandbox = new Setup().build();
        Instant until = NOW.plus(Duration.ofDays(3));
        assertThat(sandbox.capCheckoutExpiry(until.plus(Duration.ofHours(5)))).isEqualTo(until);
        assertThat(sandbox.capCheckoutExpiry(until.minus(Duration.ofHours(5)))).isEqualTo(until.minus(Duration.ofHours(5)));
        LocalSandboxActivation off = new Setup().with(s -> s.commandLine.clear()).build();
        assertThat(off.capCheckoutExpiry(until.plus(Duration.ofHours(5)))).as("inactive: unchanged").isEqualTo(until.plus(Duration.ofHours(5)));
    }

    @Test
    void evidenceOfPaymentsMadeInsideTheWindowIsAppliedDuringTheDrainOnly() {
        MonetizationClock clock = clockAt(NOW);
        LocalSandboxActivation sandbox = new LocalSandboxActivation(new Setup().inputs(), clock);
        Instant until = NOW.plus(Duration.ofDays(3));
        assertThat(sandbox.paidOutsideWindow(until.minusSeconds(1))).isFalse();
        assertThat(sandbox.paidOutsideWindow(until)).as("paid at or after the end").isTrue();
        assertThat(sandbox.paidOutsideWindow(null)).as("no payment time: not provably inside").isTrue();
        clock.setOffset(Duration.between(Instant.now(), until.plus(LocalSandboxActivation.DRAIN).minusSeconds(1)));
        assertThat(sandbox.blocksEvidence(Market.US)).isFalse();
        clock.setOffset(Duration.between(Instant.now(), until.plus(LocalSandboxActivation.DRAIN)));
        assertThat(sandbox.blocksEvidence(Market.US)).as("after the drain nothing is applied").isTrue();
        LocalSandboxActivation off = new Setup().with(s -> s.commandLine.clear()).build();
        assertThat(off.paidOutsideWindow(until.plus(Duration.ofDays(30)))).as("inactive: never").isFalse();
        assertThat(off.blocksNewCheckouts(Market.US)).isFalse();
        assertThat(off.blocksEvidence(Market.US)).isFalse();
    }

    @Test
    void anExpiredMissingMalformedOrTooDistantAcknowledgementIsRefused() {
        refused(new Setup().with(s -> s.commandLine.remove("app.billing.local-sandbox.acknowledged-until")), "missing");
        refused(new Setup().with(s -> s.commandLine.put("app.billing.local-sandbox.acknowledged-until", "next tuesday")), "malformed");
        refused(new Setup().with(s -> s.commandLine.put("app.billing.local-sandbox.acknowledged-until", NOW.minusSeconds(1).toString())), "expired");
        refused(new Setup().with(s -> s.commandLine.put("app.billing.local-sandbox.acknowledged-until", NOW.toString())), "ends now");
        refused(new Setup().with(s -> s.commandLine.put("app.billing.local-sandbox.acknowledged-until", NOW.plus(Duration.ofMinutes(59)).toString())),
                "less than an hour: no usable checkout window");
        refused(new Setup().with(s -> s.commandLine.put("app.billing.local-sandbox.acknowledged-until", NOW.plus(Duration.ofDays(7)).plusSeconds(1).toString())), "over 7 days");
        assertThat(new Setup().with(s -> s.commandLine.put("app.billing.local-sandbox.acknowledged-until", NOW.plus(Duration.ofDays(7)).toString())).build().active()).isTrue();
    }

    // ---- profiles and opt-in source (conditions 1, 2) ----

    @Test
    void productionStagingOrDemoProfilesAndAMissingSandboxProfileAreRefused() {
        refused(new Setup().with(s -> s.profiles = new String[0]), "no local-sandbox profile");
        for (String p : new String[] {"prod", "production", "staging", "demo", "PROD"}) {
            refused(new Setup().with(s -> s.profiles = new String[] {"local-sandbox", p}), p);
        }
    }

    @Test
    void theOptInAndItsExpiryMustComeFromTheCommandLineNotAConfigurationFile() {
        refused(new Setup().with(s -> {
            s.commandLine.remove("app.billing.local-sandbox.enabled");
            s.config.put("app.billing.local-sandbox.enabled", "true");
        }), "opt-in from a file");
        refused(new Setup().with(s -> {
            s.config.put("app.billing.local-sandbox.acknowledged-until", s.commandLine.remove("app.billing.local-sandbox.acknowledged-until"));
        }), "expiry from a file");
    }

    // ---- credentials (conditions 3, 4) ----

    @Test
    void livePublishableOrMissingKeysAreRefusedAndNeverShown() {
        for (String key : new String[] {LIVE_KEY, RESTRICTED_LIVE_KEY, PUBLISHABLE_KEY, "", null}) {
            Setup s = new Setup().with(x -> { if (key == null) x.os.remove("STRIPE_SECRET_KEY"); else x.os.put("STRIPE_SECRET_KEY", key); });
            assertThatThrownBy(s::build).isInstanceOf(IllegalStateException.class).hasMessageContaining("STRIPE_SECRET_KEY")
                    .satisfies(e -> {
                        if (key != null && !key.isEmpty()) assertThat(e.getMessage()).doesNotContain(key).doesNotContain(key.substring(8));
                    });
        }
        refused(new Setup().with(s -> s.config.put("app.billing.stripe.enabled", "false")), "Stripe not enabled");
        refused(new Setup().with(s -> s.config.remove("app.billing.stripe.expected-account-id")), "no expected account (gate 8)");
        refused(new Setup().with(s -> s.config.put("app.billing.stripe.expected-account-id", "cus_Synthetic")), "malformed account");
    }

    // ---- loopback only (condition 5) ----

    @Test
    void everyUrlMustBeLoopback() {
        Map<String, String> publicHosts = Map.of(
                "app.billing.stripe.success-url", "https://agafari.example/billing/return",
                "app.billing.stripe.cancel-url", "http://192.168.1.20:5212/billing/cancelled",
                "app.billing.stripe.trusted-origins", "http://127.0.0.1:5212, https://jemaw-menu.onrender.com",
                "app.billing.stripe.portal-return-url", "https://app.agafari.example/plans",
                "app.public-menu-base-url", "http://localhost.example.com:5212");
        publicHosts.forEach((property, value) -> refused(new Setup().with(s -> s.config.put(property, value)), property));
        refused(new Setup().with(s -> s.config.remove("app.billing.stripe.success-url")), "missing success URL");
        refused(new Setup().with(s -> s.config.put("app.billing.stripe.trusted-origins", "")), "no trusted origin");
        assertThat(new Setup().with(s -> s.config.put("app.billing.stripe.success-url", "http://[::1]:5212/billing/return")).build().active()).isTrue();
    }

    // ---- a disposable local database (condition 6) ----

    @Test
    void theDatabaseMustBeALocalPostgresSandboxDatabase() {
        for (String url : new String[] {"jdbc:postgresql://db.agafari.example:5432/agafari_sandbox_t1", "jdbc:postgresql://127.0.0.1:54409/agafari",
                "jdbc:postgresql://127.0.0.1:54409/agafari_r503_manual", "jdbc:h2:mem:agafari_sandbox_t1", "", "jdbc:postgresql://127.0.0.1:54409/agafari_sandbox_"}) {
            refused(new Setup().with(s -> s.config.put("spring.datasource.url", url)), url);
        }
        assertThat(new Setup().with(s -> s.config.put("spring.datasource.url", "jdbc:postgresql://localhost/agafari_sandbox_t1?sslmode=disable")).build().active()).isTrue();
    }

    // ---- not hosted (condition 7), US only (condition 8) ----

    @Test
    void aHostedEnvironmentOrEthiopianBillingIsRefused() {
        for (String marker : new String[] {"RENDER", "RENDER_SERVICE_ID", "DYNO", "KUBERNETES_SERVICE_HOST", "FLY_APP_NAME", "RAILWAY_ENVIRONMENT"}) {
            refused(new Setup().with(s -> s.os.put(marker, "x")), marker);
        }
        refused(new Setup().with(s -> s.config.put("app.billing.et.billing-enabled", "true")), "ET billing");
    }

    @Test
    void allProblemsAreReportedAtOnceWithoutAnyValue() {
        Setup s = new Setup().with(x -> {
            x.os.put("STRIPE_SECRET_KEY", LIVE_KEY);
            x.os.put("RENDER", "1");
            x.profiles = new String[] {"local-sandbox", "prod"};
        });
        assertThatThrownBy(s::build).hasMessageContaining("STRIPE_SECRET_KEY").hasMessageContaining("RENDER").hasMessageContaining("prod")
                .hasMessageNotContaining(LIVE_KEY);
    }

    // ---- the Spring wiring: off by default; a refused opt-in stops the application from starting ----

    private final org.springframework.boot.test.context.runner.ApplicationContextRunner runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withUserConfiguration(LocalSandboxConfiguration.class).withBean(MonetizationClock.class);

    @Test
    void theApplicationStartsWithTheExceptionOffUnlessOptedIn() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(LocalSandboxActivation.class).active()).isFalse();
        });
    }

    @Test
    void aCommandLineOptInThatFailsAConditionStopsTheApplication() {
        runner.withInitializer(ctx -> {
            ctx.getEnvironment().getPropertySources().addFirst(new SimpleCommandLinePropertySource("--" + LocalSandboxActivation.OPT_IN + "=true",
                    "--" + LocalSandboxActivation.UNTIL + "=" + Instant.now().plus(Duration.ofDays(1))));
            ctx.getEnvironment().setActiveProfiles("local-sandbox", "prod");
        }).run(ctx -> {
            assertThat(ctx).hasFailed();
            StringBuilder all = new StringBuilder();
            for (Throwable t = ctx.getStartupFailure(); t != null; t = t.getCause()) all.append(t.getMessage()).append('\n');
            assertThat(all.toString()).contains("local sandbox").contains("'prod' profile must not be active").contains("spring.datasource.url");
        });
    }

    // ---- condition 2: no committed configuration can switch it on ----

    @Test
    void noCommittedConfigurationNamesTheOptInOrTheSandboxProfile() throws Exception {
        List<java.nio.file.Path> roots = List.of(java.nio.file.Path.of("src/main/resources"), java.nio.file.Path.of("src/demo/resources"));
        int scanned = 0;
        for (java.nio.file.Path root : roots) {
            if (!java.nio.file.Files.isDirectory(root)) continue;
            try (var files = java.nio.file.Files.walk(root)) {
                for (java.nio.file.Path f : files.filter(x -> x.toString().matches(".*\\.(ya?ml|properties)$")).toList()) {
                    String text = java.nio.file.Files.readString(f);
                    assertThat(text).as(f.toString()).doesNotContain("local-sandbox").doesNotContain("local_sandbox");
                    scanned++;
                }
            }
        }
        assertThat(scanned).isPositive();
        assertThat(java.nio.file.Files.exists(java.nio.file.Path.of("src/main/resources/application-local-sandbox.yml"))).as("no profile file").isFalse();
    }

    // ---- the startup validator: only the US readiness violation is lifted, only while active ----

    @Test
    void startupLiftsOnlyTheUsReadinessViolationAndOnlyForAnActiveSandbox() {
        BillingFlags us = new BillingFlags(true, true, true, new MarketFlags(true, true, false, false), new MarketFlags(false, false, false, false));
        FlagFacts all = new FlagFacts(true, true, true, true, true, true, true);
        assertThat(FlagMatrixValidator.violations(us, true, all)).as("production: still refused").singleElement().asString().contains("billing-enabled (US)");
        assertThat(FlagMatrixValidator.violations(us, true, all, true)).as("an active local sandbox").isEmpty();
        assertThat(FlagMatrixValidator.violations(us, true, all, false)).hasSize(1);

        BillingFlags both = new BillingFlags(true, true, true, new MarketFlags(true, true, false, false), new MarketFlags(true, true, false, false));
        assertThat(FlagMatrixValidator.violations(both, true, all, true)).as("ET is never lifted").singleElement().asString().contains("billing-enabled (ET)");

        FlagFacts noSecrets = new FlagFacts(true, true, true, false, true, true, true);
        assertThat(FlagMatrixValidator.violations(us, true, noSecrets, true)).as("the other prerequisites still apply").anyMatch(v -> v.contains("provider secrets"));
        BillingFlags noPeriodEnd = new BillingFlags(false, true, true, new MarketFlags(true, true, false, false), new MarketFlags(false, false, false, false));
        assertThat(FlagMatrixValidator.violations(noPeriodEnd, true, all, true)).anyMatch(v -> v.contains("period-end-enabled"));
    }
}
