package org.tornotron.echno_backend.common.service;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.common.configuration.TenantDataSourceConfig;

import javax.sql.DataSource;
import java.util.Map;

@Service
public class TenantService {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TenantDataSourceConfig tenantDataSourceConfig;

    public void createTenantDatabase(String tenantId, String dbUrl, String  username, String password) {
        Flyway flyway = Flyway.configure()
                .dataSource(dbUrl, username, password)
                .locations("db/migration")
                .load();
        flyway.migrate();
    }

    public void onboardNewTenant(String tenantId,String dbUrl, String username, String password) {
        createTenantDatabase(tenantId, dbUrl, username, password);
        tenantDataSourceConfig.addTenantDataSource(tenantId, dbUrl, username, password);
    }

    public void migrateAllTenants() {
        Map<String, TenantDataSourceConfig.DataSourceProperties> tenants = tenantDataSourceConfig.getDatasources();

        for(Map.Entry<String, TenantDataSourceConfig.DataSourceProperties> entry : tenants.entrySet()) {
            String tenantId = entry.getKey();
            TenantDataSourceConfig.DataSourceProperties props = entry.getValue();

            Flyway flyway = Flyway.configure()
                    .dataSource(props.getUrl(), props.getUsername(), props.getPassword())
                    .locations("db/migration")
                    .load();

            flyway.repair();

            flyway.migrate();
        }
    }


}
