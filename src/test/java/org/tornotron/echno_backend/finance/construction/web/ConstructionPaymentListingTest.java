package org.tornotron.echno_backend.finance.construction.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.finance.construction.ConstructionPayeeType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentVoucherStatus;
import org.tornotron.echno_backend.finance.construction.service.ConstructionPaymentService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The construction payment listing takes its page bounds from {@link PageQuery} and passes every
 * filter straight through.
 *
 * <p>Both halves are the subject of issue #638. The page bounds used to arrive as a Spring
 * {@code Pageable}, whose default size comes from
 * {@code spring.data.web.pageable.default-page-size}. That property was never set while its
 * neighbour {@code max-page-size} was, so Spring's own default of twenty applied and the endpoint
 * answered with twenty rows however many the tenant had. Nothing in the configuration said
 * twenty, and nothing in the response said the collection went on.
 *
 * <p>The filters matter for the reason the AR invoice listing pins them: a filter dropped on the
 * floor fails silently, the caller asking for one employee's vouchers is handed everybody's, and
 * the response says nothing about it. Here they matter twice over, because they are what lets the
 * client stop narrowing a page of twenty in the browser and calling the result the register.
 *
 * <p>Ask what each of these would do if the code it pins were deleted. Take the {@code PageQuery}
 * parameter back to a {@code Pageable} and the first two stop compiling, because the bounds no
 * longer reach the service as a page number and a size. Drop any single filter from the handler's
 * call and {@link #everyFilterIsPassedThrough} fails naming it, because the argument it expects
 * arrives as null. Neither is a test that passes on an empty method.
 */
@ExtendWith(MockitoExtension.class)
class ConstructionPaymentListingTest {

    @Mock
    private ConstructionPaymentService service;

    /**
     * A caller who names no page size still gets the twenty rows this endpoint has been
     * answering with, rather than {@link PageQuery#DEFAULT_PAGE_SIZE}.
     *
     * <p>Twenty was never written down: it came from Spring's own default for a {@code Pageable},
     * because {@code spring.data.web.pageable.default-page-size} is not set. Issue #638 is that
     * the number was invisible and that no caller could ask past it, not that it was twenty, so
     * moving the bounds onto {@code PageQuery} writes the number down and leaves it alone. Folding
     * it onto the shared default would halve what the existing unpaged caller receives, which is a
     * contract change this fix has no reason to make.
     */
    @Test
    void anUnfilteredListingKeepsTheSizeTheEndpointShippedWith() {
        when(service.findAll(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Page.empty());

        new ConstructionPaymentControllerWeb(service)
                .list(null, null, null, null, null, null, null, null, new PageQuery());

        verify(service).findAll(isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(),
                eq(0), eq(20));
    }

    /**
     * The caller can now ask for more than one page's worth, which is the whole complaint in issue
     * #638: the client asked for the register and was handed twenty rows with no way to say so.
     * The upper bound is {@code PageQuery}'s and is checked there, so all this pins is that a size
     * the caller names reaches the query rather than being replaced by a default on the way.
     */
    @Test
    void theCallersPageBoundsReachTheService() {
        PageQuery pageQuery = new PageQuery();
        pageQuery.setPageNo(2);
        pageQuery.setPageSize(200);
        when(service.findAll(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Page.empty());

        new ConstructionPaymentControllerWeb(service)
                .list(null, null, null, null, null, null, null, null, pageQuery);

        verify(service).findAll(isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(),
                eq(2), eq(200));
    }

    @Test
    void everyFilterIsPassedThrough() {
        when(service.findAll(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Page.empty());

        new ConstructionPaymentControllerWeb(service).list(
                11L, 22L,
                ConstructionPaymentVoucherStatus.COMPLETED,
                ConstructionPaymentType.SALARY,
                ConstructionPayeeType.EMPLOYEE,
                33L, 44L, 55L,
                new PageQuery());

        verify(service).findAll(eq(11L), eq(22L),
                eq(ConstructionPaymentVoucherStatus.COMPLETED),
                eq(ConstructionPaymentType.SALARY),
                eq(ConstructionPayeeType.EMPLOYEE),
                eq(33L), eq(44L), eq(55L),
                eq(0), eq(20));
    }

    /**
     * The three person filters do not get crossed on the way through.
     *
     * <p>They are three consecutive {@code Long} parameters carrying ids from two different
     * sequences: {@code employeeId} is an employee id, {@code verifiedBy} and {@code raisedBy} are
     * platform user ids. Transposing two of them compiles, and on a young tenant whose sequences
     * still run close together it also returns plausible rows, which is how echno-web#346 shipped.
     * Distinct values here mean a transposition fails rather than passing on the coincidence.
     */
    @Test
    void theThreePersonFiltersDoNotGetCrossed() {
        when(service.findAll(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Page.empty());

        new ConstructionPaymentControllerWeb(service)
                .list(null, null, null, null, null, 101L, 202L, 303L, new PageQuery());

        verify(service).findAll(isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(101L), eq(202L), eq(303L),
                eq(0), eq(20));
    }

    /**
     * The page envelope reaches the caller intact. A listing that answered with the content alone
     * would drop the total, and a client with no total cannot tell a last page from a truncated
     * one, which is exactly how the twenty-row page passed for the whole register.
     */
    @Test
    void theResponseCarriesThePageEnvelope() {
        Page<?> page = Page.empty();
        when(service.findAll(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenAnswer(invocation -> page);

        assertThat(new ConstructionPaymentControllerWeb(service)
                .list(null, null, null, null, null, null, null, null, new PageQuery()))
                .isSameAs(page);
    }
}
