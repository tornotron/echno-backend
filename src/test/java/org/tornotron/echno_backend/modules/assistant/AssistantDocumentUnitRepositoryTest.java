package org.tornotron.echno_backend.modules.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.common.exception.TenantIdMissingException;
import org.tornotron.echno_backend.modules.assistant.domain.AssistantDocumentUnit;
import org.tornotron.echno_backend.modules.assistant.repository.AssistantDocumentUnitRepository;

class AssistantDocumentUnitRepositoryTest {

    @Test
    void rejectsWrongQueryVectorDimensionAndUnscopedCallsBeforeCallingNativeQuery() {
        AssistantDocumentUnitRepository repository = mock(AssistantDocumentUnitRepository.class, CALLS_REAL_METHODS);

        assertThatThrownBy(() -> repository.nearest(new float[3], 1L, List.of(10L), 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(AssistantDocumentUnit.EMBEDDING_DIMENSION));
        assertThatThrownBy(() -> repository.nearest(
                new float[AssistantDocumentUnit.EMBEDDING_DIMENSION + 1], 1L, List.of(10L), 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(AssistantDocumentUnit.EMBEDDING_DIMENSION));
        assertThatThrownBy(() -> repository.nearest(null, 1L, List.of(10L), 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.nearest(new float[1024], null, List.of(10L), 5))
                .isInstanceOf(TenantIdMissingException.class);
        assertThat(repository.nearest(new float[1024], 1L, List.of(), 5)).isEmpty();
        assertThat(repository.nearest(new float[1024], 1L, List.of(10L), 0)).isEmpty();

        verify(repository, never()).nearestNative(any(), any(), any(), anyInt());
    }
}
