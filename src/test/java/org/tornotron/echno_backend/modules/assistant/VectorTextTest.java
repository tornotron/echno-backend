package org.tornotron.echno_backend.modules.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.modules.assistant.domain.VectorText;

class VectorTextTest {

    @Test
    void formatsFiniteComponentsInBrackets() {
        assertThat(VectorText.of(new float[]{0.1f, 0.0f, -2.5f})).isEqualTo("[0.1,0.0,-2.5]");
    }

    @Test
    void rejectsNullOrEmptyVectors() {
        assertThatThrownBy(() -> VectorText.of(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VectorText.of(new float[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonFiniteComponents() {
        assertThatThrownBy(() -> VectorText.of(new float[]{1.0f, Float.NaN}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Component 1");
        assertThatThrownBy(() -> VectorText.of(new float[]{Float.POSITIVE_INFINITY}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Component 0");
        assertThatThrownBy(() -> VectorText.of(new float[]{0.0f, 0.0f, Float.NEGATIVE_INFINITY}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Component 2");
    }
}

