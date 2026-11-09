package org.tornotron.echno_backend.pdfGeneration;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.tornotron.echno_backend.common.controller.AttachmentControllerWeb;

/**
 * Every attachment handler and the organization report asks the access matrix for the documents
 * action it performs, and nothing else: reading attachments needs read, filing one (directly or by
 * presign and register) needs create, renaming or dating one needs edit and removing one needs
 * delete. The PDF report needs the named read-reports. The tenant API's attachment controller
 * inherits every handler from its {@code /web} twin, so it is read through the twin. A new handler
 * fails here until it is given its row.
 */
class DocumentsGuardsTest {

    /** Keyed by controller and handler, as {@link #key} names them; valued by area and action. */
    static final Map<String, String> PERMISSION_BY_HANDLER = Map.ofEntries(
            Map.entry("AttachmentControllerWeb#creatAttachment", "documents:create"),
            Map.entry("AttachmentControllerWeb#presignUploads", "documents:create"),
            Map.entry("AttachmentControllerWeb#registerUploads", "documents:create"),
            Map.entry("AttachmentControllerWeb#readAttachments", "documents:read"),
            Map.entry("AttachmentControllerWeb#updateDocumentMetadata", "documents:edit"),
            Map.entry("AttachmentControllerWeb#deleteAttachment", "documents:delete"),
            Map.entry("ReportController#pdfReport", "documents:read-reports"));

    @Test
    void everyHandlerNeedsTheActionItPerforms() {
        Map<String, String> guards = handlers().stream().collect(Collectors.toMap(DocumentsGuardsTest::key,
                handler -> handler.getAnnotation(PreAuthorize.class).value()));

        assertThat(guards.keySet()).containsExactlyInAnyOrderElementsOf(PERMISSION_BY_HANDLER.keySet());
        PERMISSION_BY_HANDLER.forEach((handler, permission) -> {
            String[] parts = permission.split(":");
            assertThat(guards.get(handler)).as(handler)
                    .isEqualTo("@access.can('" + parts[0] + "', '" + parts[1] + "')");
        });
    }

    static String key(Method handler) {
        return handler.getDeclaringClass().getSimpleName() + "#" + handler.getName();
    }

    static List<Method> handlers() {
        return Stream.of(AttachmentControllerWeb.class, ReportController.class)
                .map(Class::getDeclaredMethods)
                .flatMap(Arrays::stream)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .toList();
    }
}
