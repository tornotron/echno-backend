package org.tornotron.echno_backend.billing.webhook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.tornotron.echno_backend.billing.gateway.BillingEvent;
import org.tornotron.echno_backend.billing.gateway.BillingEventStatus;
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.billing.repositories.BillingEventRepository;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The dead-letter list narrows the way the caller asked, and a retry hands the row back to
 * the projector once and only while there is something to retry.
 */
@ExtendWith(MockitoExtension.class)
class BillingEventAdminServiceTest {

    @Mock
    private BillingEventRepository events;
    @Mock
    private BillingEventProjector projector;

    private BillingEventAdminService service() {
        return new BillingEventAdminService(events, projector);
    }

    private static BillingEvent row(Long id, BillingEventStatus status, int attempts) {
        return BillingEvent.builder().id(id).provider(ProviderId.RAZORPAY).providerEventId("evt_" + id)
                .eventType("subscription.charged").organizationId(7L).payload("{}").signatureVerified(true)
                .status(status).attemptCount(attempts).receivedAt(Instant.now()).lastError("boom").build();
    }

    /** Evaluates the specification against a stub criteria API and reports which fields it touched. */
    @SuppressWarnings("unchecked")
    private static List<String> fieldsConstrainedBy(Specification<BillingEvent> spec) {
        Root<BillingEvent> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        java.util.ArrayList<String> touched = new java.util.ArrayList<>();
        Path<Object> path = mock(Path.class);
        lenient().when(root.get(any(String.class))).thenAnswer(call -> {
            touched.add(call.getArgument(0));
            return path;
        });
        lenient().when(path.in(anyCollection())).thenReturn(mock(Predicate.class));
        lenient().when(cb.equal(any(Expression.class), (Object) any())).thenReturn(mock(Predicate.class));
        lenient().when(cb.and(any(Expression.class), any(Expression.class))).thenReturn(mock(Predicate.class));
        spec.toPredicate(root, query, cb);
        return touched;
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_defaultsToDeadLetters_andNarrowsByOrganizationAndType() {
        Pageable page = PageRequest.of(0, 10);
        when(events.findAll(any(Specification.class), eq(page)))
                .thenReturn(new PageImpl<>(List.of(row(1L, BillingEventStatus.FAILED, 5))));
        ArgumentCaptor<Specification<BillingEvent>> spec = ArgumentCaptor.forClass(Specification.class);

        Page<BillingEventDto> result = service().list(7L, "subscription.charged", null, page);

        assertThat(result.getContent()).extracting(BillingEventDto::id).containsExactly(1L);
        verify(events).findAll(spec.capture(), eq(page));
        assertThat(fieldsConstrainedBy(spec.getValue())).containsExactly("status", "organizationId", "eventType");
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_withoutFilters_constrainsOnlyStatus() {
        Pageable page = PageRequest.of(0, 10);
        when(events.findAll(any(Specification.class), eq(page))).thenReturn(Page.empty());
        ArgumentCaptor<Specification<BillingEvent>> spec = ArgumentCaptor.forClass(Specification.class);

        service().list(null, "  ", List.of(), page);

        verify(events).findAll(spec.capture(), eq(page));
        assertThat(fieldsConstrainedBy(spec.getValue())).containsExactly("status");
    }

    @Test
    void retry_requeuesAFailedRow_andProjectsItOnce() {
        BillingEvent failed = row(5L, BillingEventStatus.FAILED, 5);
        when(events.findById(5L)).thenReturn(Optional.of(failed));
        doAnswer(call -> {
            failed.setStatus(BillingEventStatus.PROCESSED);
            failed.setAttemptCount(failed.getAttemptCount() + 1);
            return null;
        }).when(projector).process(5L);

        BillingEventDto first = service().retry(5L);
        BillingEventDto second = service().retry(5L);

        assertThat(first.status()).isEqualTo(BillingEventStatus.PROCESSED);
        assertThat(first.attemptCount()).isEqualTo(1);
        assertThat(second.status()).isEqualTo(BillingEventStatus.PROCESSED);
        verify(projector, times(1)).process(5L);
        verify(events, times(1)).save(failed);
    }

    @Test
    void retry_ofAProcessedRow_isANoOp() {
        when(events.findById(9L)).thenReturn(Optional.of(row(9L, BillingEventStatus.PROCESSED, 1)));

        BillingEventDto result = service().retry(9L);

        assertThat(result.status()).isEqualTo(BillingEventStatus.PROCESSED);
        verify(projector, never()).process(any());
        verify(events, never()).save(any());
    }

    @Test
    void retry_ofASkippedRow_requeuesIt() {
        BillingEvent skipped = row(3L, BillingEventStatus.SKIPPED, 1);
        when(events.findById(3L)).thenReturn(Optional.of(skipped));

        service().retry(3L);

        verify(projector).process(3L);
        assertThat(skipped.getAttemptCount()).isZero();
    }

    @Test
    void retry_refusesAnUnverifiedRow_andAMissingOne() {
        BillingEvent unverified = row(4L, BillingEventStatus.FAILED, 2);
        unverified.setSignatureVerified(false);
        when(events.findById(4L)).thenReturn(Optional.of(unverified));
        when(events.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().retry(4L)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service().retry(404L)).isInstanceOf(ResourceNotFoundException.class);
        verify(projector, never()).process(any());
    }
}
