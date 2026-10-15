package io.github.yibenwulong.seatflow.support;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Disposable real MySQL schema; never connects silently to H2. */
public final class MySqlTestDatabase implements AutoCloseable {
    private final JdbcTemplate admin;
    private final String schema;
    private final String url;
    private final String user;
    private final String password;
    private MySqlTestDatabase() {
        String server=required("SEATFLOW_IT_MYSQL_URL");
        user=required("SEATFLOW_IT_MYSQL_USER");
        password=System.getenv().getOrDefault("SEATFLOW_IT_MYSQL_PASSWORD","");
        schema="seatflow_it_"+UUID.randomUUID().toString().replace("-","");
        admin=new JdbcTemplate(new DriverManagerDataSource(server,user,password));
        admin.execute("CREATE DATABASE "+schema);
        String options=server.contains("?")?server.substring(server.indexOf('?')+1)+"&":"";
        url=server.substring(0,server.indexOf('/',"jdbc:mysql://".length())+1)+schema+"?"+options+"connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        try { Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration","classpath:db/demo").load().migrate(); }
        catch(RuntimeException ex) { close(); throw ex; }
    }
    public static MySqlTestDatabase create() { return new MySqlTestDatabase(); }
    public JdbcTemplate jdbc() { return new JdbcTemplate(new DriverManagerDataSource(url,user,password)); }
    public String url() { return url; }
    public String user() { return user; }
    public String password() { return password; }
    @Override public void close() { admin.execute("DROP DATABASE "+schema); }
    private static String required(String key) {
        String value=System.getenv(key);
        if(value==null || value.isBlank()) throw new IllegalStateException(key+" required; integration tests never skip");
        return value;
    }
}
