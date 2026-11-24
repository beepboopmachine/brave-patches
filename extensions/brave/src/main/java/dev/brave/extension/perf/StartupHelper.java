package dev.brave.extension.perf;

import java.util.ArrayList;
import java.util.List;

public class StartupHelper {

    /**
     * Drains startup callbacks safely without re-entrancy issues, stale indexing,
     * or dropped listeners.
     */
    public static void drainAndRunCallbacks(List<?> callbacks) {
        if (callbacks == null || callbacks.isEmpty()) return;

        List<Runnable> copy = new ArrayList<>();
        synchronized (callbacks) {
            for (Object o : callbacks) {
                if (o instanceof Runnable) {
                    copy.add((Runnable) o);
                }
            }
            callbacks.clear();
        }

        for (Runnable r : copy) {
            try {
                if (r != null) {
                    r.run();
                }
            } catch (Throwable ignored) {}
        }
    }
}
