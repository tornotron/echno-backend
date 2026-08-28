package org.tornotron.echno_backend.inspection;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.tornotron.echno_backend.inspection.web.InspectionControllerWeb;
import org.tornotron.echno_backend.inspection.web.NcrControllerWeb;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which authority each new endpoint is behind.
 *
 * <p>{@code EndpointAuthorizationTest} already refuses an endpoint with no guard
 * at all, but it cannot tell a right guard from a wrong one. A report is a read,
 * so it is behind {@code canRead()}; putting it behind
 * {@code canManageInspections()} instead would pass that ratchet while shutting
 * the consultant and the client out of the documents the functional spec gives
 * them, and putting a write behind {@code canRead()} would let a site engineer
 * redraw the evidence on an inspection they only report against.
 *
 * <p>Reflection over the controllers rather than MockMvc: this is an assertion
 * about the annotation, and a web slice would cost the 1 GB test JVM another
 * cached context to learn nothing more.
 */
class InspectionReportGuardTest {

    @Test
    void theInspectionReportIsARead() {
        assertThat(guardOn(InspectionControllerWeb.class, "downloadReport"))
                .isEqualTo("@inspectionSecurity.canRead()");
    }

    @Test
    void readingTheMarksOnAnInspectionIsARead() {
        assertThat(guardOn(InspectionControllerWeb.class, "listAnnotations"))
                .isEqualTo("@inspectionSecurity.canRead()");
    }

    @Test
    void drawingOnAnInspectionNeedsTheAuthorityToRecordOne() {
        assertThat(guardOn(InspectionControllerWeb.class, "replaceAnnotations"))
                .isEqualTo("@inspectionSecurity.canManageInspections()");
    }

    @Test
    void theNcrReportAndThePunchListAreReads() {
        assertThat(guardOn(NcrControllerWeb.class, "downloadReport"))
                .isEqualTo("@inspectionSecurity.canRead()");
        assertThat(guardOn(NcrControllerWeb.class, "downloadPunchList"))
                .isEqualTo("@inspectionSecurity.canRead()");
    }

    private static String guardOn(Class<?> controller, String methodName) {
        Method method = Arrays.stream(controller.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        controller.getSimpleName() + " has no method " + methodName));

        PreAuthorize guard = method.getAnnotation(PreAuthorize.class);
        assertThat(guard)
                .as("%s.%s must carry a @PreAuthorize", controller.getSimpleName(), methodName)
                .isNotNull();
        return guard.value();
    }
}
