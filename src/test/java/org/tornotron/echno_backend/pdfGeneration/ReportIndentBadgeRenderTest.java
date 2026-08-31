package org.tornotron.echno_backend.pdfGeneration;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorN;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.tornotron.echno_backend.common.configuration.ThymeleafConfig;
import org.tornotron.echno_backend.common.conversions.DateConversion;
import org.tornotron.echno_backend.common.pagination.UnpagedResultCap;
import org.tornotron.echno_backend.employee.dto.EmployeeDto;
import org.tornotron.echno_backend.indent.IndentService;
import org.tornotron.echno_backend.indent.dto.IndentDto;
import org.tornotron.echno_backend.indent.enums.IndentStatus;
import org.tornotron.echno_backend.organization.OrganizationService;
import org.tornotron.echno_backend.project.ProjectService;
import org.tornotron.echno_backend.task.TaskService;
import org.tornotron.echno_backend.task.dto.TaskDto;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * How the indent status badge comes out of the renderer, measured on the page rather than
 * asserted about the template (issue #612).
 *
 * <p>Until #605 the report answered 500 for any tenant holding an indent, so nobody had seen a
 * rendered copy with indent rows in it. The first look found three faults in the badge, and all
 * three are pinned here: it was cut off by the right page edge, it printed the constant name
 * rather than words, and its colour never applied to {@code ON_SITE}.
 *
 * <p>The clipping is checked by position, not by looking for a truncated string. {@code .container}
 * declared {@code width: 100%} next to its own padding and inherited more from {@code .md:p-8}, so
 * the indent section ran 4rem past the right page margin and the badge, the rightmost thing in it,
 * was what the page edge took. Reading every glyph's right edge out of the content stream catches
 * that whatever the label happens to say.
 *
 * <p>No Spring context, following {@link ReportPdfRenderTest} and the other PDF tests.
 */
class ReportIndentBadgeRenderTest {

    /** The {@code @page} margin in report.css, 15mm, in PDF points. */
    private static final float PAGE_MARGIN_PT = 15f / 25.4f * 72f;

    /** Rounding room, so a box that lands exactly on the margin is not read as overflowing it. */
    private static final float TOLERANCE_PT = 1f;

    private final TaskService taskService = mock(TaskService.class);
    private final PdfReportService pdfReportService = mock(PdfReportService.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final OrganizationService organizationService = mock(OrganizationService.class);
    private final IndentService indentService = mock(IndentService.class);

    private final ReportController controller = new ReportController(
            taskService, pdfReportService, new DateConversion(),
            new ThymeleafConfig().pdfTemplateEngine(), new PdfRenderer(),
            projectService, organizationService, indentService);

    @Test
    void theStatusBadgeIsPrintedInWordsRatherThanAsTheConstantName() throws Exception {
        given(IndentStatus.ON_SITE);

        String text = textOf(render());

        assertThat(text).contains("On site");
        assertThat(text).doesNotContain("ON_SITE");
    }

    @ParameterizedTest
    @EnumSource(IndentStatus.class)
    void everyStatusPrintsItsOwnLabel(IndentStatus status) throws Exception {
        given(status);

        String text = textOf(render());

        assertThat(text).contains(status.getLabel());
        assertThat(text).doesNotContain(status.name());
    }

    @Test
    void nothingInTheReportRunsPastTheRightMargin() throws Exception {
        given(IndentStatus.ON_SITE);

        for (Chunk chunk : chunksOf(render())) {
            assertThat(chunk.right())
                    .describedAs("'%s' on page %d ends at %.1fpt, past the %.1fpt right margin",
                            chunk.text(), chunk.pageNumber(), chunk.right(), chunk.rightLimit())
                    .isLessThanOrEqualTo(chunk.rightLimit() + TOLERANCE_PT);
        }
    }

    /**
     * The badge specifically, so a regression that moves only the badge off the page is named as
     * such rather than arriving as a general overflow.
     */
    @Test
    void theStatusBadgeSitsInsideTheRightMargin() throws Exception {
        given(IndentStatus.ON_SITE);

        List<Chunk> badge = chunksOf(render()).stream()
                .filter(chunk -> chunk.text().contains("On site"))
                .toList();

        assertThat(badge).isNotEmpty();
        for (Chunk chunk : badge) {
            assertThat(chunk.right())
                    .describedAs("the badge ends at %.1fpt, past the %.1fpt right margin",
                            chunk.right(), chunk.rightLimit())
                    .isLessThanOrEqualTo(chunk.rightLimit() + TOLERANCE_PT);
        }
    }

    /**
     * The colour half of the fault. The template decided the badge colour by comparing
     * {@code #strings.toLowerCase(indent.status)} to {@code 'on-site'}, which an enum that
     * lowercases to {@code on_site} never equals, so an ON_SITE badge was drawn in the body's
     * default grey while the four constants with no underscore in them came out coloured.
     */
    @Test
    void theOnSiteBadgeIsPrintedInItsOwnColour() throws Exception {
        given(IndentStatus.ON_SITE);

        List<Chunk> badge = chunksOf(render()).stream()
                .filter(chunk -> chunk.text().contains("On site"))
                .toList();

        assertThat(badge).isNotEmpty();
        assertThat(badge).allSatisfy(chunk ->
                assertThat(chunk.rgb() & 0xFFFFFF)
                        .describedAs("the badge label's fill colour")
                        .isEqualTo(0x059669));
    }

    private byte[] render() throws Exception {
        ResponseEntity<byte[]> response = controller.pdfReport();
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private void given(IndentStatus status) {
        Page<TaskDto> taskPage = page(List.of(task(1L, "Slab shuttering, block C", 0.0)));
        Page<IndentDto> indentPage = page(List.of(indent(status)));
        when(taskService.getAllTasks(0, UnpagedResultCap.MAX_ROWS)).thenReturn(taskPage);
        when(indentService.getAllIndents(0, UnpagedResultCap.MAX_ROWS)).thenReturn(indentPage);
        when(pdfReportService.statusCount())
                .thenReturn(Map.of("COMPLETED", 2L, "IN_PROGRESS", 5L, "NOT_STARTED", 1L));
    }

    private <T> Page<T> page(List<T> content) {
        return new PageImpl<>(content, PageRequest.of(0, UnpagedResultCap.MAX_ROWS), content.size());
    }

    private String textOf(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /**
     * One drawn character: what it says, where its right edge is, how far right that page allows,
     * and the fill colour in force when it was drawn.
     */
    private record Glyph(String text, float right, float rightLimit, int pageNumber, int rgb) {
    }

    /** A run of glyphs drawn one after another, reassembled into a word. */
    private record Chunk(String text, float right, float rightLimit, int pageNumber, int rgb) {
    }

    /**
     * Every character the renderer actually drew, with its right edge, the page's own right
     * margin, and its colour. Read from the content stream rather than from the extracted string,
     * because a character drawn past the edge of the paper is still in the stream: the fault is
     * where it was put, not whether it survived extraction.
     *
     * <p>Collected in {@code processTextPosition}, which runs while the stream is being walked,
     * rather than in {@code writeString}, which PDFBox calls only once the whole page has been
     * processed. By then the graphics state holds whatever colour the page happened to end on, so
     * reading the fill colour there reports the last one for every character on the page.
     *
     * <p>The colour operators have to be registered by hand: a plain {@link PDFTextStripper}
     * installs the text operators only, so without these the fill colour never moves off its
     * initial black and every glyph reads as {@code 000000}.
     */
    private List<Glyph> glyphsOf(byte[] pdf) throws IOException {
        List<Glyph> glyphs = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition text) {
                    PDPage page = getCurrentPage();
                    float limit = page.getMediaBox().getUpperRightX() - PAGE_MARGIN_PT;
                    int rgb;
                    try {
                        rgb = getGraphicsState().getNonStrokingColor().toRGB();
                    } catch (IOException e) {
                        throw new IllegalStateException("Could not read the fill colour of a glyph", e);
                    }
                    glyphs.add(new Glyph(text.getUnicode(),
                            text.getXDirAdj() + text.getWidthDirAdj(),
                            limit, getCurrentPageNo(), rgb));
                    super.processTextPosition(text);
                }
            };
            stripper.addOperator(new SetNonStrokingColorSpace(stripper));
            stripper.addOperator(new SetNonStrokingDeviceRGBColor(stripper));
            stripper.addOperator(new SetNonStrokingDeviceGrayColor(stripper));
            stripper.addOperator(new SetNonStrokingDeviceCMYKColor(stripper));
            stripper.addOperator(new SetNonStrokingColor(stripper));
            stripper.addOperator(new SetNonStrokingColorN(stripper));
            stripper.getText(document);
        }
        return glyphs;
    }

    /**
     * Groups the glyphs into runs that share a page and a colour, so a test can talk about "the
     * badge" rather than about individual letters. A run's right edge is its rightmost glyph's.
     */
    private List<Chunk> chunksOf(byte[] pdf) throws IOException {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        Glyph first = null;
        float right = 0f;
        for (Glyph glyph : glyphsOf(pdf)) {
            boolean sameRun = first != null && first.pageNumber() == glyph.pageNumber()
                    && first.rgb() == glyph.rgb();
            if (!sameRun && first != null) {
                chunks.add(new Chunk(text.toString(), right, first.rightLimit(), first.pageNumber(), first.rgb()));
                text.setLength(0);
                right = 0f;
            }
            if (!sameRun) {
                first = glyph;
            }
            text.append(glyph.text());
            right = Math.max(right, glyph.right());
        }
        if (first != null) {
            chunks.add(new Chunk(text.toString(), right, first.rightLimit(), first.pageNumber(), first.rgb()));
        }
        return chunks;
    }

    private TaskDto task(Long id, String title, Double progress) {
        TaskDto task = new TaskDto();
        task.setId(id);
        task.setTitle(title);
        task.setProgress(progress);
        task.setUpdatedAt(LocalDateTime.of(2026, 8, 20, 9, 30));
        return task;
    }

    private IndentDto indent(IndentStatus status) {
        EmployeeDto createdBy = new EmployeeDto();
        createdBy.setId(4L);
        createdBy.setEmployeeName("Echno Admin");

        IndentDto indent = new IndentDto();
        indent.setId(1L);
        indent.setIndentNumber("IND-2026-000001");
        indent.setStatus(status);
        indent.setCreatedBy(createdBy);
        indent.setProjectId(5L);
        indent.setCreatedAt(LocalDateTime.of(2026, 8, 18, 11, 0));
        return indent;
    }
}
