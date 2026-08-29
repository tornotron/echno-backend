package org.tornotron.echno_backend.support;

import com.sun.management.GarbageCollectionNotificationInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;

/**
 * Measures how close the test JVM came to its heap cap, and records it where the build
 * can act on it.
 *
 * <p>The suite runs in one JVM, and Spring keeps a cached application context per distinct
 * test configuration, each with its own Hibernate {@code SessionFactory} over every entity.
 * That memory is held until the cache evicts it, so a run accumulates. When the total
 * reaches the cap the failure lands wherever the next allocation happened to be, as a
 * context-loading error in a class the author never touched, with no assertion failing
 * anywhere. This listener exists so the run reports its own headroom while it still has
 * some, rather than leaving the shortage to be inferred later from the wrong symptom.
 *
 * <p>Two different numbers are recorded, and the difference between them matters.
 *
 * <p>The <em>live set</em> is what cannot be reclaimed, and it is the number that predicts
 * failure: the JVM throws {@code OutOfMemoryError} when a full collection cannot free enough
 * for the next allocation. It is sampled by asking for a collection and reading what is left,
 * every {@value #SAMPLE_INTERVAL_MILLIS} milliseconds through the run and once more after the
 * last test. Sampling has to be active rather than passive because the run's worst moment is
 * not its last: the architecture tests hold the whole application's class graph in memory
 * partway through, on top of whatever contexts are cached at the time, and that is gone again
 * by the end.
 *
 * <p>The <em>occupancy</em> high-water mark is how full the heap got, garbage included, taken
 * from collections that swept the old generation. It runs far higher than the live set,
 * because a collector under no pressure has no reason to collect early, and it is reported for
 * context rather than gated on. Reading occupancy as though it were the live set would have
 * this build permanently declaring an emergency.
 *
 * <p>Registered through {@code META-INF/services}, so it applies to every test JVM the
 * build starts without any test needing to know about it.
 */
public class TestHeapHeadroomListener implements TestExecutionListener {

    /** Where the run leaves its measurement for the build to read. */
    static final String REPORT_PATH = "build/reports/test-heap/heap-headroom.properties";

    /** How often the live set is sampled. Each sample costs one collection of a second or less. */
    private static final long SAMPLE_INTERVAL_MILLIS = 15_000;

    private final AtomicLong peakLiveSetBytes = new AtomicLong();
    private final AtomicLong peakOccupancyBytes = new AtomicLong();
    private final Set<GarbageCollectorMXBean> subscribed = new HashSet<>();
    private NotificationListener notificationListener;
    private Thread sampler;

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        startLiveSetSampler();
        Set<String> heapPools = new HashSet<>();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                heapPools.add(pool.getName());
            }
        }
        notificationListener = (notification, handback) -> {
            if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
                return;
            }
            GarbageCollectionNotificationInfo info =
                    GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
            // A young-only collection leaves the old generation untouched, so its post-collection
            // reading says nothing at all about the whole heap. Only collections that swept the
            // old generation are worth recording, and even those are occupancy, not live set.
            if (!sweptOldGeneration(info)) {
                return;
            }
            long used = 0;
            for (Map.Entry<String, MemoryUsage> entry : info.getGcInfo().getMemoryUsageAfterGc().entrySet()) {
                if (heapPools.contains(entry.getKey())) {
                    used += entry.getValue().getUsed();
                }
            }
            peakOccupancyBytes.accumulateAndGet(used, Math::max);
        };
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc instanceof NotificationEmitter emitter) {
                emitter.addNotificationListener(notificationListener, null, null);
                subscribed.add(gc);
            }
        }
    }

    /**
     * Samples the live set through the run rather than only at the end. A collection is asked
     * for and what survives it is recorded, which is the memory the run genuinely cannot give
     * back. Fifteen seconds apart, so a suite of a few minutes pays well under a second in
     * total and gets some thirty readings, which is enough to catch the architecture tests.
     */
    private void startLiveSetSampler() {
        sampler = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(SAMPLE_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                System.gc();
                peakLiveSetBytes.accumulateAndGet(
                        ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max);
            }
        }, "test-heap-headroom-sampler");
        sampler.setDaemon(true);
        sampler.start();
    }

    private static boolean sweptOldGeneration(GarbageCollectionNotificationInfo info) {
        String name = info.getGcName();
        String action = info.getGcAction().toLowerCase(Locale.ROOT);
        return name.contains("Old")
                || name.contains("Concurrent")
                || name.contains("MarkSweep")
                || action.contains("major");
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (sampler != null) {
            sampler.interrupt();
        }
        for (GarbageCollectorMXBean gc : subscribed) {
            try {
                ((NotificationEmitter) gc).removeNotificationListener(notificationListener);
            } catch (Exception ignored) {
                // Nothing useful to do at the end of the run if a bean has already gone.
            }
        }
        // Two passes: the first clears the bulk, the second collects anything the first made
        // unreachable (references cleared during collection, notably ArchUnit's soft-referenced
        // class cache). What is left is what the run was genuinely holding.
        System.gc();
        System.gc();
        long endLiveSetBytes = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        peakLiveSetBytes.accumulateAndGet(endLiveSetBytes, Math::max);
        write(endLiveSetBytes);
    }

    private void write(long endLiveSetBytes) {
        Properties properties = new Properties();
        Path report = Paths.get(REPORT_PATH);
        // A forked run writes this file once per fork. Keep the worst reading rather than the
        // last one, so a build that forks still reports the peak the whole run reached.
        if (Files.isReadable(report)) {
            try (InputStream in = Files.newInputStream(report)) {
                properties.load(in);
            } catch (IOException ignored) {
                properties.clear();
            }
        }
        properties.setProperty("peakLiveSetBytes",
                Long.toString(Math.max(readLong(properties, "peakLiveSetBytes"), peakLiveSetBytes.get())));
        properties.setProperty("endLiveSetBytes",
                Long.toString(Math.max(readLong(properties, "endLiveSetBytes"), endLiveSetBytes)));
        properties.setProperty("peakOccupancyBytes",
                Long.toString(Math.max(readLong(properties, "peakOccupancyBytes"), peakOccupancyBytes.get())));
        properties.setProperty("maxHeapBytes", Long.toString(Runtime.getRuntime().maxMemory()));
        properties.setProperty("machineMemoryBytes", Long.toString(machineMemoryBytes()));
        describeContextCache(properties);
        try {
            Files.createDirectories(report.getParent());
            try (OutputStream out = Files.newOutputStream(report)) {
                properties.store(out, "Written by TestHeapHeadroomListener; read by the testHeapHeadroom task");
            }
        } catch (IOException e) {
            // The measurement is diagnostic. Losing it must never be what fails a test run.
            System.err.println("Could not write the test heap report: " + e.getMessage());
        }
    }

    private static long readLong(Properties properties, String key) {
        try {
            return Long.parseLong(properties.getProperty(key, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Reads how many contexts Spring is holding. There is no public accessor for the shared
     * cache, so this reaches the field directly and simply reports nothing if a future Spring
     * version moves it. The count is the other half of the story: a heap reading on its own
     * says the run is heavy, the context count says why.
     */
    private static void describeContextCache(Properties properties) {
        try {
            Class<?> delegate =
                    Class.forName("org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate");
            Field field = delegate.getDeclaredField("defaultContextCache");
            field.setAccessible(true);
            Object cache = field.get(null);
            properties.setProperty("contextsCached", String.valueOf(invokeInt(cache, "size")));
            properties.setProperty("contextCacheMaxSize", String.valueOf(invokeInt(cache, "getMaxSize")));
            properties.setProperty("contextLoads", String.valueOf(invokeInt(cache, "getMissCount")));
            properties.setProperty("contextHits", String.valueOf(invokeInt(cache, "getHitCount")));
        } catch (Exception e) {
            properties.setProperty("contextsCached", "-1");
        }
    }

    private static int invokeInt(Object target, String method) throws Exception {
        return (int) target.getClass().getMethod(method).invoke(target);
    }

    /** Total memory of the machine the run is on, so a CI log says what the cap is sized against. */
    private static long machineMemoryBytes() {
        try {
            for (String line : Files.readAllLines(Paths.get("/proc/meminfo"))) {
                if (line.startsWith("MemTotal:")) {
                    return Long.parseLong(line.replaceAll("\\D+", "")) * 1024L;
                }
            }
        } catch (Exception ignored) {
            // Not Linux, or no procfs. The reading is optional.
        }
        return -1;
    }
}
