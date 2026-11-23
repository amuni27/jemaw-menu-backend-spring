package com.agafari.com.billing;

import com.agafari.com.billing.stripe.StripeAccountIdentity;
import com.agafari.com.service.MonetizationClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * AGF B-L1 (register §93): the local-only Stripe sandbox exception, for one supervised test-mode Checkout on a developer
 * machine. It is <b>not</b> a gate closure: {@link ProviderActivationReadiness} keeps its eight gates, {@code ready()} stays
 * false and the gates are still reported. Only while this exception is active may {@code billing-enabled} start for the
 * <b>US</b> market, and only until its acknowledged time.
 *
 * <p>It activates only with an explicit opt-in, and then every one of nine conditions must hold, or startup is refused
 * (fail-closed):
 * <ol>
 *   <li>the {@code local-sandbox} profile is active and no {@code prod}, {@code production}, {@code staging} or {@code demo}
 *   profile is;</li>
 *   <li>{@value #OPT_IN}{@code =true} and {@value #UNTIL} are passed on the command line (never from a configuration file);</li>
 *   <li>Stripe is enabled with a test-mode secret key ({@code sk_test_}/{@code rk_test_}); the key itself is never read beyond
 *   its prefix, kept or shown;</li>
 *   <li>a valid expected Stripe account is configured (gate 8 verifies it before any call);</li>
 *   <li>every URL (success, cancel, trusted origins, portal return, public menu base) is loopback;</li>
 *   <li>the datasource is PostgreSQL on loopback, in a database named {@value #DATABASE_PREFIX}…;</li>
 *   <li>no hosting marker is in the environment;</li>
 *   <li>Ethiopian billing is off (US only);</li>
 *   <li>the acknowledged time is in the future and at most seven days ahead; at that time the exception ends, without a restart.</li>
 * </ol>
 */
public final class LocalSandboxActivation {

    public static final String PROFILE = "local-sandbox";
    public static final String OPT_IN = "app.billing.local-sandbox.enabled";
    public static final String UNTIL = "app.billing.local-sandbox.acknowledged-until";
    public static final String DATABASE_PREFIX = "agafari_sandbox_";
    static final Duration MAX_WINDOW = Duration.ofDays(7);
    static final Set<String> FORBIDDEN_PROFILES = Set.of("prod", "production", "staging", "demo");
    static final List<String> HOSTING_MARKERS = List.of("RENDER", "RENDER_SERVICE_ID", "DYNO", "KUBERNETES_SERVICE_HOST", "FLY_APP_NAME", "RAILWAY_ENVIRONMENT",
            "AWS_EXECUTION_ENV", "ECS_CONTAINER_METADATA_URI", "WEBSITE_SITE_NAME", "K_SERVICE");
    static final List<String> URL_PROPERTIES = List.of("app.billing.stripe.success-url", "app.billing.stripe.cancel-url", "app.billing.stripe.trusted-origins",
            "app.billing.stripe.portal-return-url", "app.public-menu-base-url");
    private static final List<String> REQUIRED_URLS = List.of("app.billing.stripe.success-url", "app.billing.stripe.cancel-url", "app.billing.stripe.trusted-origins",
            "app.public-menu-base-url");
    private static final Logger log = LoggerFactory.getLogger(LocalSandboxActivation.class);

    /** What kind of {@code STRIPE_SECRET_KEY} is set; the key itself is never kept. */
    public enum KeyKind { MISSING, TEST, LIVE, OTHER }

    /** Everything the conditions depend on, read once from the environment. No secret is held here. */
    public record Inputs(boolean optIn, boolean optInFromCommandLine, String acknowledgedUntil, boolean untilFromCommandLine, List<String> profiles,
                         boolean stripeEnabled, KeyKind keyKind, String expectedAccountId, Map<String, String> urls, String datasourceUrl,
                         Set<String> hostingMarkers, boolean etBillingEnabled) {

        public Inputs {
            profiles = List.copyOf(profiles);
            urls = Map.copyOf(urls);
            hostingMarkers = Set.copyOf(hostingMarkers);
        }

        public static Inputs from(ConfigurableEnvironment env, Function<String, String> osEnvironment) {
            Map<String, String> urls = new LinkedHashMap<>();
            for (String p : URL_PROPERTIES) {
                String v = env.getProperty(p);
                if (v != null) urls.put(p, v);
            }
            Set<String> markers = new TreeSet<>();
            for (String m : HOSTING_MARKERS) {
                String v = osEnvironment.apply(m);
                if (v != null && !v.isBlank()) markers.add(m);
            }
            return new Inputs("true".equalsIgnoreCase(String.valueOf(env.getProperty(OPT_IN)).strip()), fromCommandLine(env, OPT_IN),
                    env.getProperty(UNTIL), fromCommandLine(env, UNTIL), Arrays.asList(env.getActiveProfiles()),
                    "true".equalsIgnoreCase(String.valueOf(env.getProperty("app.billing.stripe.enabled")).strip()), keyKind(osEnvironment.apply("STRIPE_SECRET_KEY")),
                    env.getProperty(StripeAccountIdentity.PROPERTY), urls, env.getProperty("spring.datasource.url"), markers,
                    "true".equalsIgnoreCase(String.valueOf(env.getProperty("app.billing.et.billing-enabled")).strip()));
        }

        /** True only when the command-line arguments supply the value and no other source overrides it to something else. */
        private static boolean fromCommandLine(ConfigurableEnvironment env, String name) {
            for (PropertySource<?> source : env.getPropertySources()) {
                if (source instanceof CommandLinePropertySource<?> && source.containsProperty(name)) {
                    return String.valueOf(source.getProperty(name)).equals(env.getProperty(name));
                }
            }
            return false;
        }

        private static KeyKind keyKind(String key) {
            if (key == null || key.isBlank()) return KeyKind.MISSING;
            if (key.startsWith("sk_test_") || key.startsWith("rk_test_")) return KeyKind.TEST;
            if (key.startsWith("sk_live_") || key.startsWith("rk_live_")) return KeyKind.LIVE;
            return KeyKind.OTHER;
        }
    }

    private final boolean active;
    private final Instant until;
    private final MonetizationClock clock;

    /** Inactive unless opted in; opted in with any problem, refuses (fail-closed) naming each problem and no value. */
    public LocalSandboxActivation(Inputs in, MonetizationClock clock) {
        this.clock = clock;
        if (!in.optIn()) {
            this.active = false;
            this.until = null;
            return;
        }
        List<String> problems = problems(in, clock.now());
        if (!problems.isEmpty()) {
            throw new IllegalStateException("The local sandbox billing exception (" + OPT_IN + ") can't start: " + String.join("; ", problems));
        }
        this.active = true;
        this.until = Instant.parse(in.acknowledgedUntil().strip());
        log.warn("event=billing.local_sandbox_active market=US until={} note=\"LOCAL SANDBOX ONLY: Stripe test mode, loopback URLs, disposable database; never production\" "
                + "openGates=\"{}\"", until, String.join("; ", ProviderActivationReadiness.openGates()));
    }

    public boolean active() {
        return active;
    }

    public Instant until() {
        return until;
    }

    /**
     * True when this active exception must stop billing in {@code market}: always outside the US, and in the US from its
     * acknowledged time. An inactive exception never blocks anything (and allows nothing): normal behaviour is unchanged.
     */
    public boolean blocks(Market market) {
        if (!active || ProviderActivationReadiness.ready()) return false;
        return market != Market.US || !clock.now().isBefore(until);
    }

    static List<String> problems(Inputs in, Instant now) {
        List<String> p = new ArrayList<>();
        // 1. profiles
        List<String> profiles = in.profiles().stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        if (!profiles.contains(PROFILE)) p.add("the '" + PROFILE + "' profile must be active");
        for (String f : FORBIDDEN_PROFILES) if (profiles.contains(f)) p.add("the '" + f + "' profile must not be active");
        // 2. opt-in and expiry from the command line
        if (!in.optInFromCommandLine()) p.add(OPT_IN + " must be passed on the command line");
        if (!in.untilFromCommandLine()) p.add(UNTIL + " must be passed on the command line");
        // 3. Stripe in test mode (the key is described, never shown)
        if (!in.stripeEnabled()) p.add("app.billing.stripe.enabled must be true");
        switch (in.keyKind()) {
            case TEST -> { }
            case MISSING -> p.add("STRIPE_SECRET_KEY must be set to a test-mode key");
            case LIVE -> p.add("STRIPE_SECRET_KEY is a live-mode key; only sk_test_/rk_test_ keys are allowed");
            default -> p.add("STRIPE_SECRET_KEY must be a test-mode secret key (sk_test_/rk_test_)");
        }
        // 4. the expected account (gate 8)
        String account = StripeAccountIdentity.problem(in.expectedAccountId());
        if (account != null) p.add(account);
        // 5. loopback URLs
        for (String name : REQUIRED_URLS) if (blank(in.urls().get(name))) p.add(name + " must be set to a loopback URL");
        in.urls().forEach((name, value) -> {
            if (blank(value)) return;
            for (String part : value.split(",")) {
                if (!part.isBlank() && !loopbackUrl(part.strip())) p.add(name + " must use a loopback host (localhost, 127.0.0.1 or [::1])");
            }
        });
        // 6. a disposable local PostgreSQL database
        if (!sandboxDatabase(in.datasourceUrl())) p.add("spring.datasource.url must be PostgreSQL on a loopback host in a database named " + DATABASE_PREFIX + "…");
        // 7. not hosted
        for (String m : in.hostingMarkers()) p.add("the hosting marker " + m + " is set");
        // 8. US only
        if (in.etBillingEnabled()) p.add("app.billing.et.billing-enabled must be false (the local sandbox is US only)");
        // 9. a near, future expiry
        Instant until = parse(in.acknowledgedUntil());
        if (until == null) p.add(UNTIL + " must be an ISO-8601 instant (e.g. 2026-11-25T18:00:00Z)");
        else if (!until.isAfter(now)) p.add(UNTIL + " has passed");
        else if (until.isAfter(now.plus(MAX_WINDOW))) p.add(UNTIL + " must be at most " + MAX_WINDOW.toDays() + " days ahead");
        return p;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static Instant parse(String s) {
        if (blank(s)) return null;
        try {
            return Instant.parse(s.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static boolean loopbackHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1");
    }

    private static boolean loopbackUrl(String url) {
        try {
            URI u = URI.create(url.replace("{CHECKOUT_SESSION_ID}", "x"));
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && u.getRawUserInfo() == null && loopbackHost(u.getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean sandboxDatabase(String jdbcUrl) {
        if (blank(jdbcUrl) || !jdbcUrl.startsWith("jdbc:postgresql://")) return false;
        try {
            URI u = URI.create(jdbcUrl.substring("jdbc:".length()));
            String path = u.getPath() == null ? "" : u.getPath();
            String database = path.startsWith("/") ? path.substring(1) : path;
            return loopbackHost(u.getHost()) && database.startsWith(DATABASE_PREFIX) && database.length() > DATABASE_PREFIX.length()
                    && database.matches("[a-z0-9_]+");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
