package org.tornotron.echno_backend.common.startup;

/**
 * Data work that runs after the application has started, on every boot. {@link StartupTaskRunner}
 * runs the tasks one after another, in {@code @Order} order, on one thread of their own.
 *
 * <p>A task runs again on every boot, so it has to be repeat-safe and bring things up to date rather
 * than assume a starting point, and it has to work in transactions small enough that a run stopping
 * part way loses nothing. An exception stops that task for this boot and nothing else.
 */
public interface StartupTask {

    /** A short stable name, which is also the key of its record in {@code startup_task_state}. */
    String name();

    void run();
}
