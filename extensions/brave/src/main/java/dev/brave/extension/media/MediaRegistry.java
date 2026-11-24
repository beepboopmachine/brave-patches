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
 * Uses a monotonic per-tab navigation generation counter so stale async results
 * from previous pages (e.g. Google search -> target site) are strictly dropped.
 */
public final class MediaRegistry {
    private static final String TAG = "Brave_MediaRegistry";
    private static final int MAX_MEDIA_PER_TAB = 100;
    private static final int MAX_IMAGES_PER_TAB = 250;

    public interface MediaCountListener {
        void onMediaCountChanged(int tabId, int mediaCount, int imageCount);
    }

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
                    clearTab(tab.getId());
                }
            }
        });
    }

    private MediaRegistry() {}

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
     * Advances the navigation generation for a tab and clears all stale streams/images
     * unless we already cleared for this exact URL.
     */
    public static long advanceGenerationAndClear(int tabId, String newUrl, boolean force) {
        if (tabId < 0) tabId = 0;
        String normalizedNew = normalizePageUrl(newUrl);
        String prev = TAB_LAST_URL.get(tabId);
        if (!force && !normalizedNew.isEmpty() && normalizedNew.equals(prev)) {
            return getTabGeneration(tabId);
        }
        AtomicLong gen = TAB_GENERATIONS.computeIfAbsent(tabId, k -> new AtomicLong(1));
        long nextGen = gen.incrementAndGet();
        if (!normalizedNew.isEmpty()) {
            TAB_LAST_URL.put(tabId, normalizedNew);
        } else {
            TAB_LAST_URL.remove(tabId);
        }
        TAB_MEDIA.remove(tabId);
        TAB_IMAGES.remove(tabId);
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

    public static void addMediaItem(int tabId, MediaItem item) {
        addMediaItem(tabId, getTabGeneration(tabId), item);
    }

    public static void addMediaItem(int tabId, long expectedGeneration, MediaItem item) {
        if (tabId < 0) tabId = 0;
        if (item == null || item.url == null || item.url.isEmpty()) return;

        long currentGen = getTabGeneration(tabId);
        if (expectedGeneration > 0 && expectedGeneration != currentGen) {
            Log.d(TAG, "Dropping stale media item from previous page (gen " + expectedGeneration + " != " + currentGen + "): " + item.url);
            return;
        }

        Log.i(TAG, "addMediaItem: tab=" + tabId + " (gen=" + currentGen + "), " + item.type + " -> " + item.url);

        Map<Integer, Set<MediaItem>> targetMap = item.type.isImage() ? TAB_IMAGES : TAB_MEDIA;
        int maxCap = item.type.isImage() ? MAX_IMAGES_PER_TAB : MAX_MEDIA_PER_TAB;

        Set<MediaItem> set = targetMap.computeIfAbsent(tabId, k -> new CopyOnWriteArraySet<>());

        for (MediaItem existing : set) {
            if (existing.equals(item)) {
                existing.updateEnrichedMetadata(item.title, item.mimeType, item.width, item.height, item.sizeBytes, item.durationMs, item.thumbnailUrl, item.qualityLabel);
                return;
            }
        }

        if (set.size() >= maxCap) {
            return;
        }

        set.add(item);
        notifyCountChanged(tabId);
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
        AtomicLong gen = TAB_GENERATIONS.computeIfAbsent(tabId, k -> new AtomicLong(1));
        gen.incrementAndGet();
        TAB_LAST_URL.remove(tabId);
        TAB_MEDIA.remove(tabId);
        TAB_IMAGES.remove(tabId);
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
