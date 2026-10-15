package io.github.yibenwulong.seatflow.catalog;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Real MySQL only. Requires a disposable database server with CREATE DATABASE privilege. */
class SchemaMigrationIT {
    @Test void migratesEmptyDatabaseOnceAndKeepsInventoryIndependentPerSession() {
        String server = required("SEATFLOW_IT_MYSQL_URL");
        String user = required("SEATFLOW_IT_MYSQL_USER");
        String password = System.getenv().getOrDefault("SEATFLOW_IT_MYSQL_PASSWORD", "");
        String schema = "seatflow_it_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(server, user, password));
        admin.execute("CREATE DATABASE " + schema);
        try {
            String options = server.contains("?") ? server.substring(server.indexOf('?') + 1) + "&" : "";
            String url = server.substring(0, server.indexOf('/', "jdbc:mysql://".length()) + 1) + schema + "?" + options + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
            Flyway flyway = Flyway.configure().dataSource(url, user, password)
                    .locations("classpath:db/migration", "classpath:db/demo").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
            SchemaAssertions.verify(jdbc);
            assertThat(new CatalogRepository(jdbc).findSession(101).orElseThrow().startsAt())
                    .isEqualTo(java.time.Instant.parse("2099-12-01T11:00:00Z"));
        } finally {
            admin.execute("DROP DATABASE " + schema);
        }
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException(key + " is required; integration tests never silently skip");
        return value;
    }
}
