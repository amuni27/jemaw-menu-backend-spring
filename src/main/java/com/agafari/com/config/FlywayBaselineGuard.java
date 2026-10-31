package com.agafari.com.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decision A, from the S1-08A plan §6 ("startup safety check"): Flyway's baseline-on-migrate records a non-empty schema
 * without history at version 1 without running V1. That is right for an existing Agafari development database, and
 * wrong for any other database. This guard refuses to continue when a non-empty schema without Flyway history lacks
 * the Agafari core tables.
 */
public final class FlywayBaselineGuard {

    public static final List<String> CORE_TABLES =
            List.of("business", "business_hours", "category", "menu", "menu_item", "menu_type", "users");

    private FlywayBaselineGuard() {
    }

    /** Throws {@link IllegalStateException} if {@code schema} must not be baselined; does nothing otherwise. */
    public static void verify(Connection connection, String schema) throws SQLException {
        Set<String> tables = new TreeSet<>();
        try (ResultSet rs = connection.getMetaData().getTables(null, schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) tables.add(rs.getString("TABLE_NAME").toLowerCase());
        }
        if (tables.isEmpty() || tables.contains("flyway_schema_history")) {
            return; // empty: V1 will run; history present: Flyway decides as usual
        }
        List<String> missing = new ArrayList<>();
        for (String core : CORE_TABLES) if (!tables.contains(core)) missing.add(core);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Refusing to baseline schema '" + schema + "': it is not empty but is not an Agafari "
                    + "database (missing tables: " + String.join(", ", missing) + "). Check the datasource URL.");
        }
    }
}
