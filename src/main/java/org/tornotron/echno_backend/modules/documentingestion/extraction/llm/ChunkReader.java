package org.tornotron.echno_backend.modules.documentingestion.extraction.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.common.llm.ChatCompletionClient;
import org.tornotron.echno_backend.common.llm.ChatCompletionOutcome;
import org.tornotron.echno_backend.common.llm.ChatCompletionRequest;
import org.tornotron.echno_backend.common.llm.ChatCompletionResult;
import org.tornotron.echno_backend.common.retry.TransactionRetryTemplate;
import org.tornotron.echno_backend.modules.documentingestion.DocumentIngestionModuleEnabled;
import org.tornotron.echno_backend.modules.documentingestion.domain.LlmCallOutcome;

/**
 * Reads one chunk through the LLM, with the retries of design section 9:
 *
 * <ul>
 *   <li>A chunk with an {@code OK} ledger row in this job is skipped: it was read on an earlier attempt.</li>
 *   <li>429, 5xx, a timeout or no connection: wait 2, 8 and 30 seconds between tries, then the chunk fails
 *       and the job goes on with the others.</li>
 *   <li>{@code finish_reason = length}: never read as complete. The chunk is split in half and each half
 *       sent, down to one page. A retried job goes straight to the halves of a chunk already cut short.</li>
 *   <li>An answer that is not the JSON asked for, or an envelope that cannot be read: sent once more,
 *       then the chunk fails.</li>
 * </ul>
 *
 * <p>Before every call the guard is asked whether LLM reading is still allowed, whether the budget has
 * room and whether this replica still holds the job; any of those stops the chunk with what it had.
 * Every call that reaches the endpoint gets a ledger row. Exists only with the module switched on, so
 * no bean that can send a call exists while the module is off.
 */
@Slf4j
@Component
@DocumentIngestionModuleEnabled
public class ChunkReader {

    /** Why a job stopped before it ran out of chunks. */
    public enum Stop {
        LLM_OFF,
        BUDGET,
        LEASE_LOST
    }

    public record StopReason(Stop stop, String message) {
    }

    /** Asked before every call. */
    @FunctionalInterface
    public interface Guard {
        Optional<StopReason> check();
    }

    /** What the job already knows from its ledger: chunk keys read, and chunk keys cut short. */
    public record Known(Set<String> read, Set<String> truncated) {
    }

    /**
     * How a chunk ended. A split chunk sums its halves.
     *
     * @param stop        Set when the guard stopped it
     * @param readPages   Pages whose chunk was read
     * @param failedPages Pages whose chunk failed after its retries
     * @param chunks      Chunks this one ended as, counting each half of a split
     * @param failed      How many of those failed
     */
    public record Outcome(StopReason stop, List<Integer> readPages, List<Integer> failedPages, int chunks, int failed) {

        static Outcome read(ChunkPages chunk) {
            return new Outcome(null, chunk.pageNumbers(), List.of(), 1, 0);
        }

        static Outcome failed(ChunkPages chunk) {
            return new Outcome(null, List.of(), chunk.pageNumbers(), 1, 1);
        }

        static Outcome stopped(StopReason reason) {
            return new Outcome(reason, List.of(), List.of(), 0, 0);
        }

        Outcome plus(Outcome other) {
            List<Integer> read = new ArrayList<>(readPages);
            read.addAll(other.readPages);
            List<Integer> failedP = new ArrayList<>(failedPages);
            failedP.addAll(other.failedPages);
            return new Outcome(stop != null ? stop : other.stop, read, failedP, chunks + other.chunks,
                    failed + other.failed);
        }
    }

    private final ChatCompletionClient client;
    private final ChunkRecorder recorder;
    private final TransactionRetryTemplate retry;
    private final IngestLlmProperties properties;

    public ChunkReader(ChatCompletionClient client, ChunkRecorder recorder, TransactionRetryTemplate retry,
                       IngestLlmProperties properties) {
        this.client = client;
        this.recorder = recorder;
        this.retry = retry;
        this.properties = properties;
    }

    public Outcome read(ExtractContext context, Extractor extractor, ChunkPages chunk, Known known, Guard guard) {
        if (known.read().contains(chunk.key())) {
            return Outcome.read(chunk);
        }
        if (chunk.size() > 1 && known.truncated().contains(chunk.key())) {
            return split(context, extractor, chunk, known, guard);
        }
        int invalid = 0;
        int waits = 0;
        List<Duration> backoff = properties.getBackoff() == null ? List.of() : properties.getBackoff();
        while (true) {
            Optional<StopReason> stop = guard.check();
            if (stop.isPresent()) {
                return Outcome.stopped(stop.get());
            }
            ChatCompletionResult result = client.complete(request(context, extractor, chunk));
            ChatCompletionOutcome outcome = result.outcome();
            if (outcome == ChatCompletionOutcome.NOT_CONFIGURED) {
                return Outcome.stopped(new StopReason(Stop.LLM_OFF, LlmGate.NOT_CONFIGURED));
            }
            if (outcome == ChatCompletionOutcome.OK) {
                ChunkOutput output;
                try {
                    output = extractor.read(context, chunk, result.content());
                } catch (RuntimeException e) {
                    log.warn("Job {} chunk {}: the answer could not be read ({})", context.jobId(), chunk.key(),
                            e.getClass().getSimpleName());
                    record(context, extractor, chunk, result, LlmCallOutcome.INVALID_ANSWER);
                    if (++invalid > 1) {
                        return Outcome.failed(chunk);
                    }
                    continue;
                }
                ChunkOutput read = output;
                retry.executeWithoutResult("DocumentIngestion.chunkAnswered",
                        () -> recorder.answered(context, extractor, chunk, result, read));
                return Outcome.read(chunk);
            }
            record(context, extractor, chunk, result, LlmCallOutcome.of(outcome));
            if (outcome == ChatCompletionOutcome.TRUNCATED) {
                return chunk.size() > 1 ? split(context, extractor, chunk, known, guard) : Outcome.failed(chunk);
            }
            if (outcome == ChatCompletionOutcome.MALFORMED_ENVELOPE) {
                if (++invalid > 1) {
                    return Outcome.failed(chunk);
                }
                continue;
            }
            if (outcome.isRetryable() && waits < backoff.size()) {
                pause(backoff.get(waits++));
                continue;
            }
            log.warn("Job {} chunk {} failed after {} call(s): {}", context.jobId(), chunk.key(), waits + invalid + 1,
                    result.detail());
            return Outcome.failed(chunk);
        }
    }

    private Outcome split(ExtractContext context, Extractor extractor, ChunkPages chunk, Known known, Guard guard) {
        Outcome total = null;
        for (ChunkPages half : chunk.halves()) {
            Outcome o = read(context, extractor, half, known, guard);
            total = total == null ? o : total.plus(o);
            if (o.stop() != null) {
                break;
            }
        }
        return total;
    }

    private ChatCompletionRequest request(ExtractContext context, Extractor extractor, ChunkPages chunk) {
        ChatCompletionRequest request = ChatCompletionRequest.of(extractor.prompt().system(),
                extractor.userPrompt(context, chunk), properties.getMaxTokens()).withTemperature(properties.getTemperature());
        return properties.isJsonResponseFormat() ? request.withJsonResponseFormat() : request;
    }

    private void record(ExtractContext context, Extractor extractor, ChunkPages chunk, ChatCompletionResult result,
                        LlmCallOutcome outcome) {
        retry.executeWithoutResult("DocumentIngestion.chunkCall",
                () -> recorder.call(context, extractor, chunk, result, outcome));
    }

    private static void pause(Duration wait) {
        if (wait == null || wait.isZero() || wait.isNegative()) {
            return;
        }
        try {
            Thread.sleep(wait.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
