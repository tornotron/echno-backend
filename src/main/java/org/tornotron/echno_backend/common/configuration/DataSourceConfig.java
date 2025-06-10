package org.tornotron.echno_backend.common.configuration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.tornotron.echno_backend.common.datasource.MultitenantDataSource;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class DataSourceConfig {

    @Autowired
    private TenantDataSourceConfig tenantDataSourceConfig;

    @Bean
    public DataSource dataSource() {
        MultitenantDataSource dataSource = new MultitenantDataSource();
        Map<Object, Object> targetDataSources = new HashMap<>();

        for (Map.Entry<String, TenantDataSourceConfig.DataSourceProperties> entry : tenantDataSourceConfig.getDatasources().entrySet()) {
            DriverManagerDataSource ds = new DriverManagerDataSource();
            ds.setUrl(entry.getValue().getUrl());
            ds.setUsername(entry.getValue().getUsername());
            ds.setPassword(entry.getValue().getPassword());
            ds.setDriverClassName(entry.getValue().getDriverClassName());
            targetDataSources.put(entry.getKey(), ds);
        }

        dataSource.setTargetDataSources(targetDataSources);

        if(targetDataSources.containsKey("default")) {
            dataSource.setDefaultTargetDataSource(targetDataSources.get("default"));
        } else {
            throw new IllegalStateException("No default data source configured. Please ensure 'default' is defined in your tenant configuration.");
        }

        dataSource.afterPropertiesSet();
        return dataSource;
    }
}
