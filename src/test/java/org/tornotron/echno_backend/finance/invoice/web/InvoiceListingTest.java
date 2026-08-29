package org.tornotron.echno_backend.finance.invoice.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;
import org.tornotron.echno_backend.finance.invoice.InvoiceStatus;
import org.tornotron.echno_backend.finance.invoice.service.InvoiceService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The AR invoice listing takes its page bounds from {@link PageQuery} and passes its filters
 * straight through.
 *
 * <p>Both halves are worth pinning. The page bounds are the reason
 * {@code PaginationParameterBoundTest} exists: a handler that reads the pair off its own request
 * parameters is bounded by nothing, and one that takes a {@code PageQuery} and then ignores it is
 * the same defect wearing the right type. The filters matter because dropping one on the floor
 * fails silently: the caller asks for one customer's invoices, is handed every customer's, and
 * nothing about the response says the filter was not applied.
 */
@ExtendWith(MockitoExtension.class)
class InvoiceListingTest {

    @Mock
    private InvoiceService service;

    @Test
    void anUnfilteredListingAsksForTheFirstDefaultSizedPage() {
        when(service.findAll(any(), any(), anyBoolean(), anyInt(), anyInt())).thenReturn(Page.empty());

        new InvoiceControllerWeb(service).list(null, null, false, new PageQuery());

        verify(service).findAll(isNull(), isNull(), eq(false), eq(0), eq(PageQuery.DEFAULT_PAGE_SIZE));
    }

    @Test
    void theCallersPageBoundsReachTheService() {
        PageQuery pageQuery = new PageQuery();
        pageQuery.setPageNo(3);
        pageQuery.setPageSize(50);
        when(service.findAll(any(), any(), anyBoolean(), anyInt(), anyInt())).thenReturn(Page.empty());

        new InvoiceControllerWeb(service).list(null, null, false, pageQuery);

        verify(service).findAll(isNull(), isNull(), eq(false), eq(3), eq(50));
    }

    @Test
    void everyFilterIsPassedThrough() {
        UUID customerId = UUID.randomUUID();
        when(service.findAll(any(), any(), anyBoolean(), anyInt(), anyInt())).thenReturn(Page.empty());

        new InvoiceControllerWeb(service).list(
                customerId, InvoiceStatus.ISSUED, true, new PageQuery());

        verify(service).findAll(eq(customerId), eq(InvoiceStatus.ISSUED), eq(true),
                eq(0), eq(PageQuery.DEFAULT_PAGE_SIZE));
    }

    /**
     * The page envelope reaches the caller intact. A listing that answered with the page content
     * alone would drop the total, and a client with no total cannot tell a last page from a
     * truncated one, which is the failure {@link UnpagedResultCap} exists to make visible on the
     * endpoints that have no page parameters at all.
     */
    @Test
    void theResponseCarriesThePageEnvelope() {
        Page<?> page = Page.empty();
        when(service.findAll(any(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenAnswer(invocation -> page);

        assertThat(new InvoiceControllerWeb(service).list(null, null, false, new PageQuery()))
                .isSameAs(page);
    }
}
