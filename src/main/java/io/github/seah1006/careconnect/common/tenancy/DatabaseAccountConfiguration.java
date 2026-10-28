package io.github.seah1006.careconnect.common.tenancy;

import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DatabaseAccountConfiguration {
    @Bean
    SmartInitializingSingleton verifyApplicationAccount(DataSource dataSource,
            @Value("${spring.flyway.user}") String migrationUser) {
        return () -> {
            Boolean safe = new JdbcTemplate(dataSource).queryForObject("""
                    select not rolsuper and not rolbypassrls and rolname <> ?
                    from pg_roles where rolname = current_user
                    """, Boolean.class, migrationUser);
            if (!Boolean.TRUE.equals(safe)) {
                throw new IllegalStateException("Application database account must be separate and subject to RLS");
            }
        };
    }
}
