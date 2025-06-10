package org.tornotron.echno_backend.common.configuration;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "tenants")
public class TenantDataSourceConfig {
    private Map<String, DataSourceProperties> datasources;

    public Map<String, DataSourceProperties> getDatasources() {
        return datasources;
    }

    public void setDatasources(Map<String, DataSourceProperties> datasources) {
        this.datasources = datasources;
    }

    public void addTenantDataSource(String tenantId, String dbUrl, String username, String password) {
        DataSourceProperties props = new DataSourceProperties();
        props.setUrl(dbUrl);
        props.setUsername(username);
        props.setPassword(password);
        props.setDriverClassName("org.postgresql.Driver");
        datasources.put(tenantId, props);
    }

    public static class DataSourceProperties {
        private String url;
        private String username;
        private String password;
        private String driverClassName;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getDriverClassName() { return driverClassName; }
        public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }
    }
}
