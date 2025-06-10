package org.tornotron.echno_backend.common.datasource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.tornotron.echno_backend.common.context.TenantContext;

public class MultitenantDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        String tenantId = TenantContext.getCurrentTenant();
        return tenantId != null ? tenantId : "default";
    }
}
