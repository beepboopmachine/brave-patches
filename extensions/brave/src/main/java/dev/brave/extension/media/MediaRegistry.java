package dev.brave.extension.media;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import dev.brave.extension.core.ActiveTabTracker;
import dev.brave.extension.core.TabLifecycleListener;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.content_public.browser.NavigationHandle;

/**
 * Thread-safe registry maintaining detected media items segregated by tabId.
 * Uses per-tab locks with monotonic navigation generation counters so stale async
 * results from previous pages cannot race with navigation clearing.
 */
public final class MediaRegistry {
    private static final String TAG = "Brave_MediaRegistry";
    private static final int MAX_MEDIA_PER_TAB = 100;
    private static final int MAX_IMAGES_PER_TAB = 250;

    public interface MediaCountListener {
        void onMediaCountChanged(int tabId, int mediaCount, int imageCount);
    }

    private static final Map<Integer, Object> TAB_LOCKS = new ConcurrentHashMap<>();
    private static final Map<Integer, Set<MediaItem>> TAB_MEDIA = new ConcurrentHashMap<>();
    private static final Map<Integer, Set<MediaItem>> TAB_IMAGES = new ConcurrentHashMap<>();
    private static final Map<Integer, AtomicLong> TAB_GENERATIONS = new ConcurrentHashMap<>();
    private static final Map<Integer, String> TAB_LAST_URL = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<MediaCountListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    static {
        ActiveTabTracker.addListener(new TabLifecycleListener() {
            @Override
            public void onActiveTabChanged(Tab tab) {
                notifyListenersForActiveTab();
            }

            @Override
            public void onNavigationStarted(Tab tab, NavigationHandle handle) {
                // Generation advance & clear is performed synchronously by ActiveTabTracker
                // before any direct media checks run.
            }

            @Override
            public void onNavigationFinished(Tab tab, NavigationHandle handle) {
                notifyListenersForActiveTab();
            }

            @Override
            public void onTabDestroyed(Tab tab) {
                if (tab != null) {
                    removeTab(tab.getId());
                }
            }
        });
    }

    private MediaRegistry() {}

    private static Object getTabLock(int tabId) {
        return TAB_LOCKS.computeIfAbsent(tabId, k -> new Object());
    }

    public static void addListener(MediaCountListener listener) {
        if (listener != null) {
            LISTENERS.addIfAbsent(listener);
        }
    }

    public static void removeListener(MediaCountListener listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

    public static long getTabGeneration(int tabId) {
        if (tabId < 0) tabId = 0;
        AtomicLong gen = TAB_GENERATIONS.computeIfAbsent(tabId, k -> new AtomicLong(1));
        return gen.get();
    }

    /**
     * Fast pre-check so callers avoid queuing expensive network work when a tab's
     * media or image list is already at capacity.
     */
    public static boolean canAcceptMedia(int tabId, MediaType type) {
        if (tabId < 0) tabId = 0;
        boolean isImage = (type != null && type.isImage());
        int maxCap = isImage ? MAX_IMAGES_PER_TAB : MAX_MEDIA_PER_TAB;
        Set<MediaItem> set = isImage ? TAB_IMAGES.get(tabId) : TAB_MEDIA.get(tabId);
        return set == null || set.size() < maxCap;
    }

    /**
     * Advances the navigation generation for a tab and clears all stale streams/images
     * atomically relative to concurrent addMediaItem calls.
     */
    public static long advanceGenerationAndClear(int tabId, String newUrl, boolean force) {
        if (tabId < 0) tabId = 0;
        long nextGen;
        String normalizedNew = normalizePageUrl(newUrl);

        synchronized (getTabLock(tabId)) {
            String prev = TAB_LAST_URL.get(tabId);
            if (!force && !normalizedNew.isEmpty() && normalizedNew.equals(prev)) {
                return getTabGeneration(tabId);
            }
            AtomicLong gen = TAB_GENERATIONS.computeIfAbsent(tabId, k -> new AtomicLong(1));
            nextGen = gen.incrementAndGet();
            if (!normalizedNew.isEmpty()) {
                TAB_LAST_URL.put(tabId, normalizedNew);
            } else {
                TAB_LAST_URL.remove(tabId);
            }
            TAB_MEDIA.remove(tabId);
            TAB_IMAGES.remove(tabId);
        }

        notifyCountChanged(tabId);
        Log.i(TAG, "Cleared tab " + tabId + " (gen=" + nextGen + ", url=" + normalizedNew + ")");
        return nextGen;
    }

    private static String normalizePageUrl(String url) {
        if (url == null) return "";
        String u = url.trim();
        int hash = u.indexOf('#');
        if (hash > 0) u = u.substring(0, hash);
        return u;
    }

    public static boolean addMediaItem(int tabId, MediaItem item) {
        return addMediaItem(tabId, getTabGeneration(tabId), item);
    }

    /**
     * Atomically validates navigation generation, merges duplicates (including auth/variants),
     * enforces per-tab capacity, and inserts the item.
     *
     * @return true if a new item was inserted, false if stale, duplicate-merged, or rejected at cap.
     */
    public static boolean addMediaItem(int tabId, long expectedGeneration, MediaItem item) {
        if (tabId < 0) tabId = 0;
        if (item == null || item.url == null || item.url.isEmpty()) return false;

        boolean inserted = false;
        synchronized (getTabLock(tabId)) {
            long currentGen = getTabGeneration(tabId);
            if (expectedGeneration > 0 && expectedGeneration != currentGen) {
                Log.d(TAG, "Dropping stale media item from previous page (gen " + expectedGeneration + " != " + currentGen + "): " + item.url);
                return false;
            }

            Map<Integer, Set<MediaItem>> targetMap = item.type.isImage() ? TAB_IMAGES : TAB_MEDIA;
            int maxCap = item.type.isImage() ? MAX_IMAGES_PER_TAB : MAX_MEDIA_PER_TAB;

            Set<MediaItem> set = targetMap.computeIfAbsent(tabId, k -> new CopyOnWriteArraySet<>());

            for (MediaItem existing : set) {
                if (existing.equals(item)) {
                    existing.mergeFrom(item);
                    return false;
                }
            }

            if (set.size() >= maxCap) {
                return false;
            }

            Log.i(TAG, "addMediaItem: tab=" + tabId + " (gen=" + currentGen + "), " + item.type + " -> " + item.url);
            set.add(item);
            inserted = true;
        }

        if (inserted) {
            notifyCountChanged(tabId);
        }
        return inserted;
    }

    public static List<MediaItem> getMediaForTab(int tabId) {
        if (tabId < 0) return Collections.emptyList();
        Set<MediaItem> items = TAB_MEDIA.get(tabId);
        return items != null ? new ArrayList<>(items) : Collections.emptyList();
    }

    public static List<MediaItem> getImagesForTab(int tabId) {
        if (tabId < 0) return Collections.emptyList();
        Set<MediaItem> items = TAB_IMAGES.get(tabId);
        return items != null ? new ArrayList<>(items) : Collections.emptyList();
    }

    public static int getMediaCount(int tabId) {
        if (tabId < 0) return 0;
        Set<MediaItem> items = TAB_MEDIA.get(tabId);
        return items != null ? items.size() : 0;
    }

    public static int getImageCount(int tabId) {
        if (tabId < 0) return 0;
        Set<MediaItem> items = TAB_IMAGES.get(tabId);
        return items != null ? items.size() : 0;
    }

    public static void clearTab(int tabId) {
        if (tabId < 0) tabId = 0;
        synchronized (getTabLock(tabId)) {
            AtomicLong gen = TAB_GENERATIONS.computeIfAbsent(tabId, k -> new AtomicLong(1));
            gen.incrementAndGet();
            TAB_LAST_URL.remove(tabId);
            TAB_MEDIA.remove(tabId);
            TAB_IMAGES.remove(tabId);
        }
        notifyCountChanged(tabId);
    }

    /**
     * Permanently removes all per-tab registry state (including generation counter and lock)
     * when a tab is destroyed.
     */
    public static void removeTab(int tabId) {
        if (tabId < 0) return;
        Object lock = TAB_LOCKS.get(tabId);
        if (lock != null) {
            synchronized (lock) {
                AtomicLong gen = TAB_GENERATIONS.get(tabId);
                if (gen != null) {
                    gen.incrementAndGet();
                }
                TAB_LAST_URL.remove(tabId);
                TAB_MEDIA.remove(tabId);
                TAB_IMAGES.remove(tabId);
                TAB_GENERATIONS.remove(tabId);
                TAB_LOCKS.remove(tabId, lock);
            }
        } else {
            TAB_LAST_URL.remove(tabId);
            TAB_MEDIA.remove(tabId);
            TAB_IMAGES.remove(tabId);
            TAB_GENERATIONS.remove(tabId);
        }
        notifyCountChanged(tabId);
    }

    private static void notifyCountChanged(final int tabId) {
        final int mediaCount = getMediaCount(tabId);
        final int imageCount = getImageCount(tabId);

        Runnable task = () -> {
            for (MediaCountListener l : LISTENERS) {
                try {
                    l.onMediaCountChanged(tabId, mediaCount, imageCount);
                } catch (Throwable t) {
                    Log.w(TAG, "Listener error in notifyCountChanged", t);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            MAIN_HANDLER.post(task);
        }
    }

    private static void notifyListenersForActiveTab() {
        int tabId = ActiveTabTracker.getActiveTabId();
        notifyCountChanged(tabId);
    }
}
