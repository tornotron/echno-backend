package org.tornotron.echno_backend.indentItem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;
import org.tornotron.echno_backend.indent.IndentRepository;
import org.tornotron.echno_backend.indentItem.mapper.IndentItemMapper;
import org.tornotron.echno_backend.inventoryTransaction.InventoryService;
import org.tornotron.echno_backend.material.MaterialRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Paging in {@link IndentItemService#getIndentItemsPaginated}.
 *
 * <p>This is the route out of the cap, so the thing worth pinning is that it cannot become a way
 * back around it: a caller asking for an enormous page gets the cap, not the whole table. The sort
 * matters too, because paging an unordered result is what makes rows repeat on one page and go
 * missing from another.
 */
@ExtendWith(MockitoExtension.class)
class IndentItemServicePaginationTest {

    @Mock
    private IndentItemRepository indentItemRepository;

    @Mock
    private IndentRepository indentRepository;

    @Mock
    private MaterialRepository materialRepository;

    @Mock
    private TenantEntityHelper tenantEntityHelper;

    @Mock
    private IndentItemMapper indentItemMapper;

    @Mock
    private InventoryService inventoryService;

    @InjectMocks
    private IndentItemService service;

    private Pageable capturePageable() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(indentItemRepository).findAll(pageable.capture());
        return pageable.getValue();
    }

    private void stubEmptyPage() {
        when(indentItemRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());
    }

    @Test
    void clampsAPageSizeAboveTheCap() {
        stubEmptyPage();

        service.getIndentItemsPaginated(0, 100_000);

        assertThat(capturePageable().getPageSize()).isEqualTo(UnpagedResultCap.MAX_ROWS);
    }

    @Test
    void clampsAPageSizeOfZeroUpToOneRow() {
        stubEmptyPage();

        service.getIndentItemsPaginated(0, 0);

        assertThat(capturePageable().getPageSize()).isEqualTo(1);
    }

    @Test
    void treatsANegativePageNumberAsTheFirstPage() {
        stubEmptyPage();

        service.getIndentItemsPaginated(-5, 20);

        assertThat(capturePageable().getPageNumber()).isZero();
    }

    @Test
    void ordersByIdSoThePagesAddUp() {
        stubEmptyPage();

        service.getIndentItemsPaginated(2, 20);

        Pageable pageable = capturePageable();
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "id"));
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(20);
    }
}
