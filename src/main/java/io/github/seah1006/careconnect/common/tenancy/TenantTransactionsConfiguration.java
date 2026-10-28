package io.github.seah1006.careconnect.common.tenancy;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;

@Configuration
public class TenantTransactionsConfiguration {
    @Bean
    HibernatePropertiesCustomizer tenantIdentifier() {
        return properties -> properties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER,
                new TenantIdentifierResolver());
    }

    @Bean
    JdbcTransactionManager identityTransactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource) {
            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
                super.doBegin(transaction, definition);
                bindTenant(dataSource);
            }
        };
    }

    @Bean
    JpaTransactionManager transactionManager(EntityManagerFactory factory, DataSource dataSource) {
        return new JpaTransactionManager(factory) {
            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
                super.doBegin(transaction, definition);
                bindTenant(dataSource);
            }
        };
    }

    private static void bindTenant(DataSource dataSource) {
        TenantContext.current().ifPresent(org -> new JdbcTemplate(dataSource)
                .queryForObject("select set_config('app.current_org', ?, true)", String.class, org.toString()));
    }
}
