package org.tornotron.echno_backend.modules.documentingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.tornotron.echno_backend.modules.documentingestion.extraction.llm.ChunkReader;
import org.tornotron.echno_backend.modules.documentingestion.job.ExtractJobDispatcher;
import org.tornotron.echno_backend.modules.documentingestion.job.ExtractJobWorker;

/** The poller, the worker and the one bean that sends a call exist only with the operator switch on. */
class ExtractQueueSwitchTest {

    @Test
    void theExtractQueueAndTheChunkReaderExistOnlyWithTheSwitchOnAndNoDefaultToOn() {
        for (Class<?> type : List.of(ExtractJobDispatcher.class, ExtractJobWorker.class, ChunkReader.class)) {
            ConditionalOnProperty condition = AnnotatedElementUtils.findMergedAnnotation(type, ConditionalOnProperty.class);
            assertThat(condition).as(type.getSimpleName()).isNotNull();
            assertThat(condition.name()).containsExactly("echno.modules.document-ingestion.enabled");
            assertThat(condition.havingValue()).isEqualTo("true");
            assertThat(condition.matchIfMissing()).isFalse();
        }
    }
}
