package io.github.yibenwulong.seatflow.identity;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserAccountRepository {
    private final JdbcTemplate jdbc;
    public UserAccountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Optional<Account> byUsername(String username) { return find("username", username); }
    Optional<Account> byId(long id) { return find("id", id); }
    private Optional<Account> find(String column, Object value) {
        return jdbc.query("SELECT id, username, password_hash, role, enabled FROM user_account WHERE " + column + "=?",
                (rs, row) -> new Account(new CurrentUser(rs.getLong("id"), rs.getString("username"), Role.valueOf(rs.getString("role"))),
                        rs.getString("password_hash"), rs.getBoolean("enabled")), value).stream().findFirst();
    }
    record Account(CurrentUser user, String passwordHash, boolean enabled) {
        @Override public String toString() { return "Account[user=" + user + ", enabled=" + enabled + "]"; }
    }
}
