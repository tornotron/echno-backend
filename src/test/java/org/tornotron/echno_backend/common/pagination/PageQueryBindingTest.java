package org.tornotron.echno_backend.common.pagination;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.tornotron.echno_backend.asset.AssetControllerWeb;
import org.tornotron.echno_backend.asset.AssetService;
import org.tornotron.echno_backend.asset.dto.AssetDto;
import org.tornotron.echno_backend.asset.dto.AssetMovementDto;
import org.tornotron.echno_backend.common.exception.GlobalExceptionHandler;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a caller's {@code pageNo} and {@code pageSize} are allowed to be, proved through the real
 * request pipeline.
 *
 * <p>Driven through {@code MockMvc} standalone rather than against {@link PageQuery} directly,
 * because the part that had to be got right is the binding, not the constraint annotations. A
 * bound is only worth as much as its reaching the handler, and the ways it can fail to are all in
 * the wiring: whether Spring's model-attribute binding claims the type at all, whether
 * {@code @Valid} on it is honoured, and whether what a breach raises is a failure
 * {@code GlobalExceptionHandler} answers as a 400 naming the field rather than as a 500.
 *
 * <p>{@code AssetControllerWeb} is the subject because it carries one endpoint on the shared
 * default and one that shipped with a default of twenty, so both readings of an absent
 * {@code pageSize} are pinned here. It also carries {@code @Validated}, which is the arrangement
 * most of these controllers are in.
 *
 * <p>Stubbing is lenient because most of these cases are refusals, where the service is never
 * reached and its stub is deliberately unused.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PageQueryBindingTest {

    private static final String PAGINATED = "/api/v1/assets/web/paginated";

    /** The movement ledger of asset 7: the endpoint whose own default page size is twenty. */
    private static final String MOVEMENTS = "/api/v1/assets/web/7/movements";

    @Mock
    private AssetService assetService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // An empty page still needs a real PageRequest behind it: Jackson cannot serialise the
        // Unpaged instance the single-argument PageImpl constructor leaves in place.
        when(assetService.getAllAssets(anyInt(), anyInt()))
                .thenReturn(new PageImpl<AssetDto>(List.of(), PageRequest.of(0, 10), 0));
        when(assetService.getMovements(anyLong(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<AssetMovementDto>(List.of(), PageRequest.of(0, 10), 0));
        mockMvc = MockMvcBuilders.standaloneSetup(new AssetControllerWeb(assetService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void bindsTheParametersTheCallerSent() throws Exception {
        mockMvc.perform(get(PAGINATED).param("pageNo", "3").param("pageSize", "25"))
                .andExpect(status().isOk());

        verify(assetService).getAllAssets(3, 25);
    }

    @Test
    void appliesTheSharedDefaultWhenTheCallerSendsNeither() throws Exception {
        mockMvc.perform(get(PAGINATED)).andExpect(status().isOk());

        verify(assetService).getAllAssets(0, PageQuery.DEFAULT_PAGE_SIZE);
    }

    /**
     * The endpoints that shipped with a default of twenty or thirty keep it. Folding them onto the
     * shared default would shrink the page every caller who omits the parameter already receives,
     * which is a change to a published contract and no part of bounding anything.
     */
    @Test
    void keepsAnEndpointsOwnDefaultPageSize() throws Exception {
        mockMvc.perform(get(MOVEMENTS)).andExpect(status().isOk());

        verify(assetService).getMovements(7L, 0, 20);
    }

    @Test
    void refusesAPageSizeAboveTheRowCap() throws Exception {
        mockMvc.perform(get(PAGINATED)
                        .param("pageSize", Integer.toString(UnpagedResultCap.MAX_ROWS + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pageSize").exists());

        verify(assetService, never()).getAllAssets(anyInt(), anyInt());
    }

    @Test
    void acceptsAPageSizeAtTheRowCap() throws Exception {
        mockMvc.perform(get(PAGINATED)
                        .param("pageSize", Integer.toString(UnpagedResultCap.MAX_ROWS)))
                .andExpect(status().isOk());

        verify(assetService).getAllAssets(0, UnpagedResultCap.MAX_ROWS);
    }

    @Test
    void refusesANegativePageNumber() throws Exception {
        mockMvc.perform(get(PAGINATED).param("pageNo", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pageNo").exists());
    }

    @Test
    void refusesAPageSizeOfZero() throws Exception {
        mockMvc.perform(get(PAGINATED).param("pageSize", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pageSize").exists());
    }

    /**
     * The case that used to be a 500 rather than merely a wrong answer: a page number that is not
     * a number failed while binding, and nothing turned that into a refusal the caller could read.
     */
    @Test
    void refusesAPageNumberThatIsNotANumber() throws Exception {
        mockMvc.perform(get(PAGINATED).param("pageNo", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pageNo").exists());
    }

    @Test
    void refusesAPageSizeTooLargeForAnInt() throws Exception {
        mockMvc.perform(get(PAGINATED).param("pageSize", "99999999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pageSize").exists());
    }

    /**
     * The bound belongs to the pair, not to the endpoint, so an endpoint with a default of its own
     * is refused on the same ceiling as every other.
     */
    @Test
    void refusesAnOversizedPageOnAnEndpointWithItsOwnDefault() throws Exception {
        mockMvc.perform(get(MOVEMENTS).param("pageSize", "1000000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pageSize").exists());

        verify(assetService, never()).getMovements(anyLong(), anyInt(), anyInt());
    }

    /**
     * Nothing about the pair moved: the parameters keep the names the API published, so a caller
     * already sending them is unaffected.
     */
    @Test
    void stillReadsTheParametersUnderTheirPublishedNames() throws Exception {
        mockMvc.perform(get(MOVEMENTS).param("pageNo", "2").param("pageSize", "5"))
                .andExpect(status().isOk());

        verify(assetService).getMovements(7L, 2, 5);
    }
}
