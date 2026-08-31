package org.tornotron.echno_backend.attendance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.tornotron.echno_backend.attendance.enums.RegularizationStatus;
import org.tornotron.echno_backend.attendance.service.AttendanceRegularizationService;
import org.tornotron.echno_backend.common.pagination.PageQuery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The regularization register can be asked for a status other than pending, and narrowed to the
 * person who decided a request.
 *
 * <p>This is the whole of issue #637. The register's only listing was
 * {@code /pending}, and {@code processRegularization} stamps the approver in the same call that
 * moves a request off {@code PENDING}. A row with an approver was therefore never in the list and
 * a row in the list never had one, so the {@code approver} filter the web client already declares
 * could not match anything: it rendered an empty table under a chip reading "Approved by
 * &lt;name&gt;", which states the opposite of the truth.
 *
 * <p>Ask what each of these would do if the code it pins were deleted. Remove the {@code list}
 * handler and every one of them stops compiling. Leave the handler but drop the {@code status}
 * argument from its call and {@link #aDecidedStatusReachesTheQuery} fails, which is the case that
 * makes an approved request reachable at all. Drop {@code approvedById} and
 * {@link #theApproverReachesTheQuery} fails. Transpose the approver and the requester, which
 * compiles because both are {@code Long}, and
 * {@link #theApproverAndTheRequesterDoNotGetCrossed} fails on the distinct values.
 */
@ExtendWith(MockitoExtension.class)
class RegularizationListingTest {

    @Mock
    private AttendanceRegularizationService service;

    @Test
    void anUnfilteredListingAsksForTheFirstDefaultSizedPage() {
        when(service.findAll(any(), any(), any(), anyInt(), anyInt())).thenReturn(Page.empty());

        new AttendanceRegularizationControllerWeb(service).list(null, null, null, new PageQuery());

        verify(service).findAll(isNull(), isNull(), isNull(),
                eq(0), eq(PageQuery.DEFAULT_PAGE_SIZE));
    }

    /**
     * A status the pending-only listing could never return reaches the query.
     *
     * <p>The listing exists so a decided request can be found. If the status never reached the
     * query the endpoint would answer with the register unfiltered, which is a different wrong
     * answer from the one it replaced but still not the one asked for.
     */
    @Test
    void aDecidedStatusReachesTheQuery() {
        when(service.findAll(any(), any(), any(), anyInt(), anyInt())).thenReturn(Page.empty());

        new AttendanceRegularizationControllerWeb(service)
                .list(RegularizationStatus.APPROVED, null, null, new PageQuery());

        verify(service).findAll(eq(RegularizationStatus.APPROVED), isNull(), isNull(),
                eq(0), eq(PageQuery.DEFAULT_PAGE_SIZE));
    }

    @Test
    void theApproverReachesTheQuery() {
        when(service.findAll(any(), any(), any(), anyInt(), anyInt())).thenReturn(Page.empty());

        new AttendanceRegularizationControllerWeb(service).list(null, 9L, null, new PageQuery());

        verify(service).findAll(isNull(), eq(9L), isNull(),
                eq(0), eq(PageQuery.DEFAULT_PAGE_SIZE));
    }

    /**
     * The approver and the status travel together, which is what separates an approval from a
     * rejection.
     *
     * <p>{@code processRegularization} writes {@code approvedBy} and {@code approvedById} on both
     * outcomes, so the approver id alone means "requests this person decided". Pairing it with the
     * status is what makes "approved by X" and "rejected by X" two answerable questions rather
     * than one ambiguous column, and is the reason this listing carries both rather than the
     * schema growing a second column pair.
     */
    @Test
    void theApproverAndTheStatusNarrowTogether() {
        when(service.findAll(any(), any(), any(), anyInt(), anyInt())).thenReturn(Page.empty());

        new AttendanceRegularizationControllerWeb(service)
                .list(RegularizationStatus.REJECTED, 9L, null, new PageQuery());

        verify(service).findAll(eq(RegularizationStatus.REJECTED), eq(9L), isNull(),
                eq(0), eq(PageQuery.DEFAULT_PAGE_SIZE));
    }

    /**
     * The approver and the requester are not transposed on the way through. Both are {@code Long}
     * employee ids on adjacent parameters, so swapping them compiles and returns rows.
     */
    @Test
    void theApproverAndTheRequesterDoNotGetCrossed() {
        when(service.findAll(any(), any(), any(), anyInt(), anyInt())).thenReturn(Page.empty());

        new AttendanceRegularizationControllerWeb(service).list(null, 71L, 88L, new PageQuery());

        verify(service).findAll(isNull(), eq(71L), eq(88L),
                eq(0), eq(PageQuery.DEFAULT_PAGE_SIZE));
    }

    @Test
    void theCallersPageBoundsReachTheService() {
        PageQuery pageQuery = new PageQuery();
        pageQuery.setPageNo(4);
        pageQuery.setPageSize(75);
        when(service.findAll(any(), any(), any(), anyInt(), anyInt())).thenReturn(Page.empty());

        new AttendanceRegularizationControllerWeb(service).list(null, null, null, pageQuery);

        verify(service).findAll(isNull(), isNull(), isNull(), eq(4), eq(75));
    }

    /** The page envelope reaches the caller, so a client can tell a last page from a cut one. */
    @Test
    void theResponseCarriesThePageEnvelope() {
        Page<?> page = Page.empty();
        when(service.findAll(any(), any(), any(), anyInt(), anyInt()))
                .thenAnswer(invocation -> page);

        assertThat(new AttendanceRegularizationControllerWeb(service)
                .list(null, null, null, new PageQuery()))
                .isSameAs(page);
    }
}
