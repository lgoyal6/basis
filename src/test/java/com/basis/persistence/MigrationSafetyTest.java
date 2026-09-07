package com.basis.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Quantity;
import com.basis.reconcile.BreakRecord;
import com.basis.reconcile.BreakStatus;
import com.basis.reconcile.BreakType;
import com.basis.reconcile.ProbableCause;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What a deploy does to the schema, checked rather than assumed.
 *
 * <p>Two questions. The first is what a migration that fails halfway leaves behind, because
 * the answer decides whether a bad deploy is fixed by editing a file or by somebody logging
 * into the database at night. The second is whether the version of the service that is still
 * running during a rolling deploy can keep writing to a schema the new version has already
 * migrated, which is the whole of expand and contract and the reason V7 added columns beside
 * {@code probable_cause} rather than replacing it.
 *
 * <p>Nothing here is a fix. These are the properties the shipped migrations already have,
 * written down so that the next migration cannot quietly stop having them.
 */
@Testcontainers
class MigrationSafetyTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String SHIPPED = "filesystem:src/main/resources/db/migration";

    /** The columns the first BreakRecordRepository wrote before V7 widened the cause. */
    private static final String WEEK_ONE_INSERT =
            "INSERT INTO break_record (as_of_date, account, commodity, break_type,"
                    + " broker_quantity, computed_quantity, probable_cause)"
                    + " VALUES (DATE '2020-08-31', 'Assets:Broker:Uploaded', 'AAPL', 'QUANTITY_MISMATCH',"
                    + " 40, 10, 'looks like a four for one split')";

    @Test
    @DisplayName("a migration that fails leaves the schema exactly as it was, and rerunning works")
    void aFailedMigrationLeavesNothingBehind(@TempDir Path broken) throws Exception {
        String schema = schema("failed_migration");
        flyway(schema, SHIPPED).migrate();
        new PreviousBreakService(schema).record("survives failed migration");
        // Valid up to the last statement, so it fails after doing work rather than before.
        // That is the case worth knowing about: a migration that never started is not the
        // one that wakes anybody up.
        Files.writeString(broken.resolve("V900__half_of_a_good_idea.sql"),
                "CREATE TABLE half_applied (id BIGSERIAL PRIMARY KEY);\n"
                        + "ALTER TABLE break_record ADD COLUMN triage_note TEXT;\n"
                        + "ALTER TABLE no_such_table ADD COLUMN nonsense TEXT;\n");

        assertThatThrownBy(() -> flyway(schema, SHIPPED, "filesystem:" + broken).migrate())
                .hasMessageContaining("no_such_table");

        assertThat(tableExists(schema, "half_applied"))
                .as("Postgres rolls DDL back, so a half applied migration is not half applied")
                .isFalse();
        assertThat(columnExists(schema, "break_record", "triage_note")).isFalse();
        assertThat(appliedVersions(schema))
                .as("nothing to repair by hand: the failure recorded no version to get stuck on")
                .doesNotContain("900");
        assertThat(count(schema, "SELECT count(*) FROM break_record"))
                .as("the row written before the failed deploy survives its rollback")
                .isEqualTo(1);

        // The recovery is to fix the file and run it again. Here the fix is to remove it.
        assertThat(flyway(schema, SHIPPED).migrate().success).isTrue();
        assertThat(tableExists(schema, "break_record")).isTrue();
        assertThat(currentService(schema).findOpen(Account.of("Assets:Broker:Uploaded")))
                .as("current code can read the preserved row after the repair")
                .hasSize(1);
    }

    @Test
    @DisplayName("the version still running during a deploy can write to the migrated schema")
    void oldCodeStillWritesAfterAnAdditiveMigration() throws Exception {
        String schema = schema("expand_contract");
        // The old version's schema, as it stood before V7 widened the cause into four
        // columns. Its writes are the ones a rolling deploy leaves in flight.
        flywayUpTo(schema, "6").migrate();
        PreviousBreakService previous = new PreviousBreakService(schema);
        previous.record("old service before migration");

        flyway(schema, SHIPPED).migrate();

        // Old code, new schema, same statement. This is what makes V7 an expand: every
        // column it added carries a default, and probable_cause is still there to write to.
        previous.record("old service during rollout");
        currentService(schema).record(new BreakRecord(
                LocalDate.of(2020, 8, 31),
                Account.of("Assets:Broker:Uploaded"),
                Commodity.equity("MSFT"),
                BreakType.QUANTITY_MISMATCH,
                Quantity.of(BigDecimal.valueOf(25)),
                Quantity.of(BigDecimal.TEN),
                null,
                null,
                ProbableCause.unexplained("current service during rollout"),
                BreakStatus.OPEN));
        assertThat(count(schema, "SELECT count(*) FROM break_record")).isEqualTo(3);
        assertThat(count(schema,
                "SELECT count(*) FROM break_record WHERE cause_code = 'UNEXPLAINED'"))
                .as("a row written by the old version reads back with the new column defaulted")
                .isEqualTo(3);
        assertThat(count(schema,
                "SELECT count(*) FROM break_record WHERE probable_cause IS NOT NULL"))
                .as("the column the old version writes was widened beside, not replaced")
                .isEqualTo(3);
        assertThat(currentService(schema).findOpen(Account.of("Assets:Broker:Uploaded")))
                .as("the current service reads rows from both service versions")
                .hasSize(3);
    }

    @Test
    @DisplayName("no shipped migration drops or renames a column, which is what would break a rollout")
    void everyMigrationIsAdditive() throws Exception {
        List<Path> migrations;
        try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
            migrations = files.filter(path -> path.toString().endsWith(".sql")).sorted().toList();
        }
        assertThat(migrations).as("the migration directory has to actually be here").isNotEmpty();

        for (Path migration : migrations) {
            // Comments are stripped first: V5 and V7 both discuss dropping and renaming in
            // prose, and a check that reads prose as SQL is a check nobody can trust.
            String sql = Files.readString(migration)
                    .replaceAll("(?m)--.*$", "")
                    .toUpperCase(java.util.Locale.ROOT);
            assertThat(sql)
                    .as(migration.getFileName() + " drops a column, so a rolling deploy would"
                            + " have the running version writing to something that is gone")
                    .doesNotContain("DROP COLUMN")
                    .doesNotContain("RENAME COLUMN")
                    .doesNotContain("DROP TABLE");
            // A NOT NULL column with no default is the other half of the same failure: the
            // running version's INSERT does not name it, so every write it makes is rejected.
            for (String added : addedColumns(sql)) {
                assertThat(added)
                        .as(migration.getFileName() + " adds " + added.trim() + " to a table that"
                                + " already exists, with NOT NULL and no default")
                        .satisfiesAnyOf(
                                clause -> assertThat(clause).doesNotContain("NOT NULL"),
                                clause -> assertThat(clause).contains("DEFAULT"));
            }
        }
    }

    /** The ADD COLUMN clauses of an ALTER TABLE, which are the ones an old writer can trip on. */
    private static List<String> addedColumns(String sql) {
        List<String> clauses = new java.util.ArrayList<>();
        java.util.regex.Matcher alter = java.util.regex.Pattern
                .compile("ALTER TABLE.*?;", java.util.regex.Pattern.DOTALL).matcher(sql);
        while (alter.find()) {
            for (String clause : alter.group().split(",")) {
                if (clause.contains("ADD COLUMN")) {
                    clauses.add(clause);
                }
            }
        }
        return clauses;
    }

    private static Flyway flyway(String schema, String... locations) {
        return configure(schema, locations).load();
    }

    /** Stops at a version, so the schema the previous release saw can be recreated. */
    private static Flyway flywayUpTo(String schema, String version) {
        return configure(schema, SHIPPED).target(version).load();
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration configure(
            String schema, String... locations) {
        return Flyway.configure()
                .dataSource(dataSource())
                .schemas(schema)
                .locations(locations)
                .cleanDisabled(true);
    }

    private static String schema(String name) throws Exception {
        execute(null, "CREATE SCHEMA IF NOT EXISTS " + name);
        return name;
    }

    private static void execute(String schema, String sql) throws Exception {
        try (Connection connection = dataSource().getConnection();
                Statement statement = connection.createStatement()) {
            if (schema != null) {
                statement.execute("SET search_path TO " + schema);
            }
            statement.execute(sql);
        }
    }

    private static long count(String schema, String sql) throws Exception {
        try (Connection connection = dataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            try (ResultSet rows = statement.executeQuery(sql)) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static boolean tableExists(String schema, String table) throws Exception {
        return count(schema, "SELECT count(*) FROM information_schema.tables"
                + " WHERE table_schema = '" + schema + "' AND table_name = '" + table + "'") > 0;
    }

    private static boolean columnExists(String schema, String table, String column) throws Exception {
        return count(schema, "SELECT count(*) FROM information_schema.columns"
                + " WHERE table_schema = '" + schema + "' AND table_name = '" + table + "'"
                + " AND column_name = '" + column + "'") > 0;
    }

    private static List<String> appliedVersions(String schema) throws Exception {
        List<String> versions = new java.util.ArrayList<>();
        try (Connection connection = dataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            try (ResultSet rows = statement.executeQuery(
                    "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL")) {
                while (rows.next()) {
                    versions.add(rows.getString(1));
                }
            }
        }
        return versions;
    }

    private static DataSource dataSource() {
        org.springframework.jdbc.datasource.DriverManagerDataSource source =
                new org.springframework.jdbc.datasource.DriverManagerDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    private static BreakRecordRepository currentService(String schema) throws Exception {
        org.springframework.jdbc.datasource.DriverManagerDataSource source =
                new org.springframework.jdbc.datasource.DriverManagerDataSource();
        String url = POSTGRES.getJdbcUrl();
        source.setUrl(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return new BreakRecordRepository(JdbcClient.create(source));
    }

    /**
     * The deployed V6 write path retained as executable code. It names only
     * columns that existed before V7, exactly as the previous service did.
     */
    private static final class PreviousBreakService {
        private final String schema;

        private PreviousBreakService(String schema) {
            this.schema = schema;
        }

        private void record(String cause) throws Exception {
            execute(schema, WEEK_ONE_INSERT.replace("looks like a four for one split", cause));
        }
    }
}
