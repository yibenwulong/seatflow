package io.github.yibenwulong.seatflow.catalog;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Fast SQL compatibility check on H2; does not claim MySQL integration coverage. */
class SchemaMigrationTest {
    @Test void productionMigrationsNeverCreateDemoAccountsOrInventory() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_account", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM show_session", Integer.class)).isZero();
    }

    @Test void migrationAndSeedAreRepeatableAndSnapshotsAreIndependent() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway flyway = Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration", "classpath:db/demo").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        SchemaAssertions.verify(new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")));
    }
}
