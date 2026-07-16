package org.tornotron.echno_backend.common.numbering;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;

import java.time.LocalDate;
import java.time.Month;

@Component
@RequiredArgsConstructor
public class EntryNumberGenerator {

    private final DocumentSequenceRepository repo;
    private final TenantEntityHelper tenantEntityHelper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next(String docType) {
        int fy = currentFiscalYear(LocalDate.now());
        Long orgId = TenantContext.getCurrentOrgId();
        DocumentSequence seq = repo.findByOrgAndDocTypeAndFiscalYearForUpdate(orgId, docType, fy)
                .orElseGet(() -> {
                    DocumentSequence s = new DocumentSequence();
                    s.setOrganization(tenantEntityHelper.resolveCurrentOrganization());
                    s.setDocType(docType);
                    s.setFiscalYear(fy);
                    s.setNextValue(1L);
                    return repo.save(s);
                });
        long value = seq.getNextValue();
        seq.setNextValue(value + 1);
        repo.save(seq);
        return "%s-%d-%06d".formatted(docType, fy, value);
    }

    static int currentFiscalYear(LocalDate date) {
        if(date.getMonth().getValue() >= Month.APRIL.getValue()) {
            return date.getYear() + 1;
        }
        return date.getYear();
    }

}
