package org.tornotron.echno_backend.common.pagination;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.tornotron.echno_backend.asset.AssetControllerWeb;
import org.tornotron.echno_backend.asset.AssetService;
import org.tornotron.echno_backend.asset.dto.AssetDto;
import org.tornotron.echno_backend.employee.EmployeeControllerWeb;
import org.tornotron.echno_backend.employee.EmployeeService;
import org.tornotron.echno_backend.expense.ExpenseControllerWeb;
import org.tornotron.echno_backend.expense.ExpenseService;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNoteControllerWeb;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNoteService;
import org.tornotron.echno_backend.indent.IndentControllerWeb;
import org.tornotron.echno_backend.indent.IndentService;
import org.tornotron.echno_backend.issue.IssueControllerWeb;
import org.tornotron.echno_backend.issue.IssueService;
import org.tornotron.echno_backend.material.MaterialControllerWeb;
import org.tornotron.echno_backend.material.MaterialService;
import org.tornotron.echno_backend.materialConsumption.MaterialConsumptionControllerWeb;
import org.tornotron.echno_backend.materialConsumption.MaterialConsumptionService;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderControllerWeb;
import org.tornotron.echno_backend.purchaseOrder.PurchaseOrderService;
import org.tornotron.echno_backend.receipt.ReceiptControllerWeb;
import org.tornotron.echno_backend.receipt.ReceiptService;
import org.tornotron.echno_backend.siteTransfer.SiteTransferControllerWeb;
import org.tornotron.echno_backend.siteTransfer.SiteTransferService;
import org.tornotron.echno_backend.stockAdjustment.StockAdjustmentControllerWeb;
import org.tornotron.echno_backend.stockAdjustment.StockAdjustmentService;
import org.tornotron.echno_backend.storageLocation.StorageLocationControllerWeb;
import org.tornotron.echno_backend.storageLocation.StorageLocationService;
import org.tornotron.echno_backend.subcontract.SubContractControllerWeb;
import org.tornotron.echno_backend.subcontract.SubContractService;
import org.tornotron.echno_backend.vendor.VendorControllerWeb;
import org.tornotron.echno_backend.vendor.VendorService;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every bare web listing that answers with a JSON array now reads exactly one capped page.
 *
 * <p>This is the regression these tests exist for, and it has already been shipped twice: a bare
 * listing that reads {@code page.getContent()} from a page it sized with a defaulted
 * {@code pageSize} of ten silently answers with the first ten rows, which is worse than an
 * unbounded read because nothing about the response says it is incomplete. The listings here take
 * no page parameters at all, so the only defensible read is page zero at the cap.
 *
 * <p>Each case pins the two halves that can drift apart: the page the controller asks the service
 * for, and that the response carries the true total rather than the returned size. The
 * architecture ratchet in {@code UnboundedRepositoryReadTest} stops the underlying read going back
 * to a whole-table {@code findAll}; it cannot see what page number a controller asks for.
 */
@ExtendWith(MockitoExtension.class)
class CappedWebListingTest {

    @Mock private AssetService assetService;
    @Mock private EmployeeService employeeService;
    @Mock private ExpenseService expenseService;
    @Mock private GoodsReceivedNoteService goodsReceivedNoteService;
    @Mock private IndentService indentService;
    @Mock private IssueService issueService;
    @Mock private MaterialConsumptionService materialConsumptionService;
    @Mock private MaterialService materialService;
    @Mock private PurchaseOrderService purchaseOrderService;
    @Mock private ReceiptService receiptService;
    @Mock private SiteTransferService siteTransferService;
    @Mock private StockAdjustmentService stockAdjustmentService;
    @Mock private StorageLocationService storageLocationService;
    @Mock private SubContractService subContractService;
    @Mock private VendorService vendorService;

    @Test
    void assetsReadTheFirstCappedPage() {
        when(assetService.getAllAssets(anyInt(), anyInt())).thenReturn(Page.empty());

        new AssetControllerWeb(assetService).readAllAssets();

        verify(assetService).getAllAssets(0, UnpagedResultCap.MAX_ROWS);
    }

    @Test
    void employeesReadTheFirstCappedPageWithNoFilters() {
        when(employeeService.displayAllEmployees(anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(Page.empty());

        new EmployeeControllerWeb(employeeService).readAllEmployees();

        verify(employeeService).displayAllEmployees(
                eq(0), eq(UnpagedResultCap.MAX_ROWS), isNull(), isNull(), isNull());
    }

    @Test
    void theEmployeePickerPassesItsSearchAndLimitThrough() {
        when(employeeService.lookupEmployees(any(), anyInt())).thenReturn(Page.empty());

        new EmployeeControllerWeb(employeeService).lookupEmployees("mason", 25);

        verify(employeeService).lookupEmployees("mason", 25);
    }

    @Test
    void expensesReadTheFirstCappedPageWithNoFilters() {
        when(expenseService.getPaginated(anyInt(), anyInt(), any(), any())).thenReturn(Page.empty());

        new ExpenseControllerWeb(expenseService).readAllExpenses();

        verify(expenseService).getPaginated(eq(0), eq(UnpagedResultCap.MAX_ROWS), isNull(), isNull());
    }

    @Test
    void goodsReceivedNotesReadTheFirstCappedPage() {
        when(goodsReceivedNoteService.getAllGrns(anyInt(), anyInt())).thenReturn(Page.empty());

        new GoodsReceivedNoteControllerWeb(goodsReceivedNoteService).getAllGrns();

        verify(goodsReceivedNoteService).getAllGrns(0, UnpagedResultCap.MAX_ROWS);
    }

    @Test
    void indentsReadTheFirstCappedPage() {
        when(indentService.getAllIndents(anyInt(), anyInt())).thenReturn(Page.empty());

        new IndentControllerWeb(indentService).getAllIndents();

        verify(indentService).getAllIndents(0, UnpagedResultCap.MAX_ROWS);
    }

    @Test
    void issuesReadTheFirstCappedPageWithNoFilters() {
        when(issueService.getAllIssuesPaginated(
                anyInt(), anyInt(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Page.empty());

        new IssueControllerWeb(issueService, null).readAllIssues();

        verify(issueService).getAllIssuesPaginated(
                eq(0), eq(UnpagedResultCap.MAX_ROWS),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void materialsReadTheFirstCappedPage() {
        when(materialService.getAllMaterials(anyInt(), anyInt())).thenReturn(Page.empty());

        new MaterialControllerWeb(materialService, null).getAllMaterials();

        verify(materialService).getAllMaterials(0, UnpagedResultCap.MAX_ROWS);
    }

    @Test
    void materialConsumptionsReadTheFirstCappedPage() {
        when(materialConsumptionService.getAllMaterialConsumptions(anyInt(), anyInt()))
                .thenReturn(Page.empty());

        new MaterialConsumptionControllerWeb(materialConsumptionService).getAllMaterialConsumptions();

        verify(materialConsumptionService).getAllMaterialConsumptions(0, UnpagedResultCap.MAX_ROWS);
    }

    @Test
    void purchaseOrdersReadTheFirstCappedPage() {
        when(purchaseOrderService.getAllPurchaseOrders(anyInt(), anyInt())).thenReturn(Page.empty());

        new PurchaseOrderControllerWeb(purchaseOrderService).getAllPurchaseOrders();

        verify(purchaseOrderService).getAllPurchaseOrders(0, UnpagedResultCap.MAX_ROWS);
    }

    @Test
    void receiptsReadTheFirstCappedPageWithNoFilters() {
        when(receiptService.getPaginated(anyInt(), anyInt(), any(), any())).thenReturn(Page.empty());

        new ReceiptControllerWeb(receiptService).readAllReceipts();

        verify(receiptService).getPaginated(eq(0), eq(UnpagedResultCap.MAX_ROWS), isNull(), isNull());
    }

    @Test
    void siteTransfersReadTheFirstCappedPage() {
        when(siteTransferService.getAllSiteTransfers(anyInt(), anyInt())).thenReturn(Page.empty());

        new SiteTransferControllerWeb(siteTransferService).getAllSiteTransfers();

        verify(siteTransferService).getAllSiteTransfers(0, UnpagedResultCap.MAX_ROWS);
    }

    @Test
    void stockAdjustmentsReadTheFirstCappedPage() {
        when(stockAdjustmentService.getAll(anyInt(), anyInt())).thenReturn(Page.empty());

        new StockAdjustmentControllerWeb(stockAdjustmentService).readAllStockAdjustments();

        verify(stockAdjustmentService).getAll(0, UnpagedResultCap.MAX_ROWS);
    }

    @Test
    void storageLocationsReadTheFirstCappedPage() {
        when(storageLocationService.getAllStorageLocations(anyInt(), anyInt())).thenReturn(Page.empty());

        new StorageLocationControllerWeb(storageLocationService).getAllStorageLocations();

        verify(storageLocationService).getAllStorageLocations(0, UnpagedResultCap.MAX_ROWS);
    }

    @Test
    void subContractsReadTheFirstCappedPageWithNoFilters() {
        when(subContractService.getPaginated(anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(Page.empty());

        new SubContractControllerWeb(subContractService).readAllSubContracts();

        verify(subContractService).getPaginated(
                eq(0), eq(UnpagedResultCap.MAX_ROWS), isNull(), isNull(), isNull());
    }

    @Test
    void vendorsReadTheFirstCappedPage() {
        when(vendorService.getAllVendors(anyInt(), anyInt())).thenReturn(Page.empty());

        new VendorControllerWeb(vendorService, null).getAllVendors();

        verify(vendorService).getAllVendors(0, UnpagedResultCap.MAX_ROWS);
    }

    /**
     * A truncated listing says so. Without this a caller cannot distinguish a tenant with exactly
     * the cap's worth of rows from one whose result was cut, which is how a silent truncation goes
     * unnoticed in the first place.
     */
    @Test
    void aTruncatedListingCarriesTheTrueTotalAndTheCappedFlag() {
        List<AssetDto> rows = IntStream.range(0, UnpagedResultCap.MAX_ROWS)
                .mapToObj(i -> new AssetDto())
                .toList();
        when(assetService.getAllAssets(anyInt(), anyInt())).thenReturn(new PageImpl<>(
                rows, PageRequest.of(0, UnpagedResultCap.MAX_ROWS), UnpagedResultCap.MAX_ROWS + 7));

        ResponseEntity<List<AssetDto>> response =
                new AssetControllerWeb(assetService).readAllAssets();

        assertThat(response.getBody()).hasSize(UnpagedResultCap.MAX_ROWS);
        assertThat(response.getHeaders().getFirst(UnpagedResultCap.TOTAL_HEADER))
                .isEqualTo(Long.toString(UnpagedResultCap.MAX_ROWS + 7L));
        assertThat(response.getHeaders().getFirst(UnpagedResultCap.CAPPED_HEADER)).isEqualTo("true");
    }
}
