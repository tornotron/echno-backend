package org.tornotron.echno_backend.project.spatial;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.project.ProjectService;
import org.tornotron.echno_backend.project.spatial.dto.MoveSpatialNodeRequest;
import org.tornotron.echno_backend.project.spatial.dto.SpatialImportRequest;

/**
 * The site structure takes its project's facts: each endpoint asks about the project, for its own
 * action, before the structure is read or changed, so a project the caller may not read is missing
 * and one they may read but not change refuses the change.
 */
class SpatialNodeControllerAccessTest {

    private static final Long PROJECT = 10L;
    private static final UUID NODE = UUID.randomUUID();

    private final SpatialNodeService service = mock(SpatialNodeService.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final SpatialNodeController controller = new SpatialNodeController(service, projects);

    @Test
    void aProjectTheCallerMayNotReadHasNoStructureToShow() {
        doThrow(new ResourceNotFoundException("missing")).when(projects).requireAccess(PROJECT, AccessPermission.READ);

        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> controller.getTree(PROJECT, false));
        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> controller.getNode(PROJECT, NODE));
        verifyNoInteractions(service);
    }

    @Test
    void aChangeIsAskedForByItsActionAndRefusedBeforeTheStructureIsTouched() {
        doThrow(new AccessDeniedException("no")).when(projects).requireAccess(PROJECT, AccessPermission.CREATE);
        doThrow(new AccessDeniedException("no")).when(projects).requireAccess(PROJECT, AccessPermission.EDIT);

        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> controller.move(PROJECT, NODE, new MoveSpatialNodeRequest(UUID.randomUUID())));
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() -> controller.archive(PROJECT, NODE));
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(() -> controller.restore(PROJECT, NODE));
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> controller.importRows(PROJECT, new SpatialImportRequest(List.of())));
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> controller.update(PROJECT, NODE, null));
        verify(projects).requireAccess(PROJECT, AccessPermission.EDIT);
        verifyNoInteractions(service);
    }
}
