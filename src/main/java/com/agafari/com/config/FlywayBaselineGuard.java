package com.agafari.com.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decision A, from the S1-08A plan §6 ("startup safety check"): Flyway's baseline-on-migrate records a non-empty schema
 * without history at version 1 without running V1. That is right for an existing Agafari development database, and
 * wrong for any other database. This guard refuses to continue when a non-empty schema without Flyway history lacks
 * the Agafari core tables.
 *
 * <p>AGF-S1-08B hardening (S1-08A drift checks D12 and D14, found by the S1-08B offline rehearsal):
 * <ul>
 *   <li><b>D12</b>: while V2 has not run, a menu type that uses a V2 seed id with another name would make V2 fail on the
 *       primary key (V2 skips existing types by name only);</li>
 *   <li><b>D14</b>: a schema without history must not already contain objects that V3 and later create (left by a start with
 *       Flyway off and {@code ddl-auto: update}); V3 would fail half-way after the baseline.</li>
 * </ul>
 * The guard only reads (catalog and {@code SELECT}); it never repairs data or schema. All problems are reported at once.
 */
public final class FlywayBaselineGuard {

    public static final List<String> CORE_TABLES =
            List.of("business", "business_hours", "category", "menu", "menu_item", "menu_type", "users");

    /** Ids and names V2 seeds (kept equal to V2__seed_menu_types.sql by FlywayBaselineGuardTest). */
    public static final Map<String, String> V2_SEED_MENU_TYPES = seedMenuTypes();

    /** Objects created by V3–V9 (checked against a fresh database by FlywayBaselineGuardTest; V9: AGF-S2-24). */
    public static final List<String> LATER_MIGRATION_COLUMNS = List.of(
            "business.location_url", "business.country", "business.time_zone",
            "category.name_am", "menu.name_am", "menu_item.name_am", "menu_item.description_am",
            "users.email_verified_at"); // V10: AGF-S2-22
    public static final List<String> LATER_MIGRATION_TABLES = List.of("special", "category_special", "menu_view_daily",
            "admin_audit_event", // V11: AGF E4-M1
            "monetization_config", // V12: AGF E4-M2
            "subscription_plan", "business_subscription", // V13: AGF E4-M3
            "plan_request"); // V14: AGF E4-M4
    public static final List<String> LATER_MIGRATION_FUNCTIONS =
            List.of("special_business_matches_dish", "category_special_business_matches_category",
                    "admin_audit_event_append_only", // V11: AGF E4-M1
                    "monetization_config_append_only", // V12: AGF E4-M2
                    "subscription_plan_read_only", "business_subscription_guard", // V13: AGF E4-M3
                    "plan_request_guard"); // V14: AGF E4-M4

    private FlywayBaselineGuard() {
    }

    /** Throws {@link IllegalStateException} if {@code schema} must not be baselined or migrated; does nothing otherwise. */
    public static void verify(Connection connection, String schema) throws SQLException {
        Set<String> tables = new TreeSet<>();
        try (ResultSet rs = connection.getMetaData().getTables(null, schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) tables.add(rs.getString("TABLE_NAME").toLowerCase());
        }
        if (tables.isEmpty()) {
            return; // empty: V1 will run
        }
        boolean hasHistory = tables.contains("flyway_schema_history");
        List<String> problems = new ArrayList<>();
        if (!hasHistory) {
            List<String> missing = new ArrayList<>();
            for (String core : CORE_TABLES) if (!tables.contains(core)) missing.add(core);
            if (!missing.isEmpty()) {
                throw new IllegalStateException("Refusing to baseline schema '" + schema + "': it is not empty but is not an Agafari "
                        + "database (missing tables: " + String.join(", ", missing) + "). Check the datasource URL.");
            }
            List<String> present = laterMigrationObjects(connection, schema, tables);
            if (!present.isEmpty()) {
                problems.add("D14: objects that V3 and later create already exist without Flyway history (" + String.join(", ", present)
                        + "); was the application started with Flyway off? Restore the backup taken before that start");
            }
        }
        if (tables.contains("menu_type") && !(hasHistory && version2Applied(connection, schema))) {
            List<String> collisions = seedIdCollisions(connection, schema);
            if (!collisions.isEmpty()) {
                problems.add("D12: menu types use a V2 seed id with another name (" + String.join(", ", collisions)
                        + "); V2 would fail on the primary key. Fix the data first (DBA-approved)");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to migrate schema '" + schema + "'. Nothing was changed. "
                    + String.join(". ", problems) + ". See the S1-08A plan, drift checks D12-D14.");
        }
    }

    private static List<String> laterMigrationObjects(Connection connection, String schema, Set<String> tables) throws SQLException {
        List<String> present = new ArrayList<>();
        for (String column : LATER_MIGRATION_COLUMNS) {
            String[] tc = column.split("\\.");
            try (ResultSet rs = connection.getMetaData().getColumns(null, schema, tc[0], tc[1])) {
                if (rs.next()) present.add(column);
            }
        }
        for (String table : LATER_MIGRATION_TABLES) if (tables.contains(table)) present.add(table);
        try (PreparedStatement ps = connection.prepareStatement("select p.proname from pg_proc p join pg_namespace n "
                + "on n.oid = p.pronamespace where n.nspname = ? and p.proname = any (?) order by 1")) {
            ps.setString(1, schema);
            ps.setArray(2, connection.createArrayOf("text", LATER_MIGRATION_FUNCTIONS.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) present.add(rs.getString(1) + "()");
            }
        }
        return present;
    }

    private static boolean version2Applied(Connection connection, String schema) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("select count(*) from " + quote(connection, schema)
                + ".flyway_schema_history where version = '2' and success")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private static List<String> seedIdCollisions(Connection connection, String schema) throws SQLException {
        List<String> collisions = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("select id, name from " + quote(connection, schema)
                + ".menu_type where id = any (?) order by id")) {
            ps.setArray(1, connection.createArrayOf("text", V2_SEED_MENU_TYPES.keySet().toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(1), name = rs.getString(2);
                    if (!V2_SEED_MENU_TYPES.get(id).equalsIgnoreCase(name)) { // V2 compares names case-insensitively
                        collisions.add(id + " is '" + name + "', V2 seeds '" + V2_SEED_MENU_TYPES.get(id) + "'");
                    }
                }
            }
        }
        return collisions;
    }

    private static String quote(Connection connection, String identifier) throws SQLException {
        String q = connection.getMetaData().getIdentifierQuoteString();
        return q + identifier.replace(q, q + q) + q;
    }

    private static Map<String, String> seedMenuTypes() {
        Map<String, String> seed = new LinkedHashMap<>();
        seed.put("mt-breakfast", "Breakfast");
        seed.put("mt-brunch", "Brunch");
        seed.put("mt-kids", "Kids");
        seed.put("mt-lunch", "Lunch");
        seed.put("mt-all-day", "All Day");
        seed.put("mt-specials", "Specials");
        seed.put("mt-desserts", "Desserts");
        seed.put("mt-drinks", "Drinks");
        seed.put("mt-dinner", "Dinner");
        return Collections.unmodifiableMap(seed);
    }
}
