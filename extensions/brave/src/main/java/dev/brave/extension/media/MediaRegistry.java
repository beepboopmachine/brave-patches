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
import dev.brave.extension.core.ActiveTabTracker;
import dev.brave.extension.core.TabLifecycleListener;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.content_public.browser.NavigationHandle;

/**
 * Thread-safe registry maintaining detected media items segregated by tabId.
 * Caps memory consumption to prevent unbounded heap growth.
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
                // Do not clear on start; only clear on navigation commit
            }

            @Override
            public void onNavigationFinished(Tab tab, NavigationHandle handle) {
                if (tab != null && handle != null && handle.i && !handle.k) {
                    clearTab(tab.getId());
                }
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

    public static void addMediaItem(int tabId, MediaItem item) {
        if (tabId <= 0 || item == null || item.url == null || item.url.isEmpty()) return;

        Map<Integer, Set<MediaItem>> targetMap = item.type.isImage() ? TAB_IMAGES : TAB_MEDIA;
        int maxCap = item.type.isImage() ? MAX_IMAGES_PER_TAB : MAX_MEDIA_PER_TAB;

        Set<MediaItem> set = targetMap.computeIfAbsent(tabId, k -> new CopyOnWriteArraySet<>());

        // If item already exists, enrich its metadata
        for (MediaItem existing : set) {
            if (existing.equals(item)) {
                existing.updateEnrichedMetadata(item.title, item.mimeType, item.width, item.height, item.sizeBytes, item.durationMs);
                return;
            }
        }

        if (set.size() >= maxCap) {
            return; // Cap reached for this tab
        }

        set.add(item);
        notifyCountChanged(tabId);
    }

    public static List<MediaItem> getMediaForTab(int tabId) {
        if (tabId <= 0) return Collections.emptyList();
        Set<MediaItem> items = TAB_MEDIA.get(tabId);
        return items != null ? new ArrayList<>(items) : Collections.emptyList();
    }

    public static List<MediaItem> getImagesForTab(int tabId) {
        if (tabId <= 0) return Collections.emptyList();
        Set<MediaItem> items = TAB_IMAGES.get(tabId);
        return items != null ? new ArrayList<>(items) : Collections.emptyList();
    }

    public static int getMediaCount(int tabId) {
        if (tabId <= 0) return 0;
        Set<MediaItem> items = TAB_MEDIA.get(tabId);
        return items != null ? items.size() : 0;
    }

    public static int getImageCount(int tabId) {
        if (tabId <= 0) return 0;
        Set<MediaItem> items = TAB_IMAGES.get(tabId);
        return items != null ? items.size() : 0;
    }

    public static void clearTab(int tabId) {
        if (tabId <= 0) return;
        TAB_MEDIA.remove(tabId);
        TAB_IMAGES.remove(tabId);
        notifyCountChanged(tabId);
    }

    private static void notifyCountChanged(final int tabId) {
        final int mediaCount = getMediaCount(tabId);
        final int imageCount = getImageCount(tabId);

        MAIN_HANDLER.post(() -> {
            for (MediaCountListener l : LISTENERS) {
                try {
                    l.onMediaCountChanged(tabId, mediaCount, imageCount);
                } catch (Throwable t) {
                    Log.w(TAG, "Listener error in notifyCountChanged", t);
                }
            }
        });
    }

    private static void notifyListenersForActiveTab() {
        int tabId = ActiveTabTracker.getActiveTabId();
        notifyCountChanged(tabId);
    }
}
