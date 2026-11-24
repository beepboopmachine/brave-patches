package dev.brave.extension.core;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.content_public.browser.NavigationHandle;
import org.chromium.content_public.browser.RenderFrameHost;
import org.chromium.content_public.browser.WebContents;
import org.chromium.url.GURL;

/**
 * Central tracking hub for active Tab and WebContents lifecycle in Brave.
 * Thread-safe, caches tab properties for safe access from background threads.
 */
public final class ActiveTabTracker {
    private static final String TAG = "BraveCore_TabTracker";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<TabLifecycleListener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile WeakReference<Tab> sActiveTabRef = new WeakReference<>(null);
    private static volatile int sActiveTabId = -1;
    private static volatile String sActiveTabUrl = "";
    private static volatile boolean sIsIncognito = false;

    private ActiveTabTracker() {}

    public static void addListener(TabLifecycleListener listener) {
        if (listener != null) {
            LISTENERS.addIfAbsent(listener);
        }
    }

    public static void removeListener(TabLifecycleListener listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

    public static Tab getActiveTab() {
        return sActiveTabRef.get();
    }

    public static int getActiveTabId() {
        if (sActiveTabId >= 0) return sActiveTabId;
        Tab tab = sActiveTabRef.get();
        if (tab != null) {
            try {
                int id = tab.getId();
                if (id >= 0) {
                    sActiveTabId = id;
                    return id;
                }
            } catch (Throwable ignored) {}
        }
        return sActiveTabId;
    }

    public static String getActiveTabUrl() {
        return sActiveTabUrl;
    }

    public static String getGurlSpec(GURL gurl) {
        if (gurl == null) return "";
        try {
            String s = gurl.k();
            if (s != null && !s.isEmpty()) return s;
        } catch (Throwable ignored) {}
        if (gurl.a != null && !gurl.a.isEmpty()) {
            return gurl.a;
        }
        try {
            String s = gurl.l();
            if (s != null && !s.isEmpty()) return s;
        } catch (Throwable ignored) {}
        try {
            String s = gurl.getSpec();
            if (s != null && !s.isEmpty()) return s;
        } catch (Throwable ignored) {}

        // Fallback: reflection on all String fields of GURL instance
        try {
            for (java.lang.reflect.Field f : gurl.getClass().getDeclaredFields()) {
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    Object val = f.get(gurl);
                    if (val instanceof String) {
                        String str = (String) val;
                        if (str.startsWith("http://") || str.startsWith("https://") || str.startsWith("chrome://") || str.startsWith("file://")) {
                            return str;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        return "";
    }

    public static boolean isIncognito() {
        return sIsIncognito;
    }

    // -------------------------------------------------------------
    // Bytecode Hook Entry Points (Invoked from Smali fingerprints)
    // -------------------------------------------------------------

    public static void onActiveTabChanged(final Tab tab) {
        try {
            dev.brave.extension.media.ToolbarHook.ensureInitialized();
        } catch (Throwable ignored) {}

        postToMain(() -> {
            if (tab != null) {
                sActiveTabRef = new WeakReference<>(tab);
                updateCachedTabState(tab);
                Log.i(TAG, "onActiveTabChanged: tab=" + tab.getId() + ", url=" + sActiveTabUrl);
            }

            for (TabLifecycleListener l : LISTENERS) {
                try {
                    l.onActiveTabChanged(tab);
                } catch (Throwable t) {
                    Log.e(TAG, "Error in onActiveTabChanged listener", t);
                }
            }
        });
    }

    public static void onNavigationStarted(final Tab tab, final Object navHandleObj) {
        try {
            dev.brave.extension.media.ToolbarHook.ensureInitialized();
        } catch (Throwable ignored) {}

        final NavigationHandle handle;
        if (navHandleObj instanceof NavigationHandle) {
            handle = (NavigationHandle) navHandleObj;
        } else {
            if (navHandleObj != null) {
                Log.w(TAG, "Unexpected navHandleObj type in onNavigationStarted: " + navHandleObj.getClass());
            }
            handle = null;
        }

        postToMain(() -> {
            if (tab != null) {
                sActiveTabRef = new WeakReference<>(tab);
                updateCachedTabState(tab);

                String startUrl = "";
                if (handle != null) {
                    startUrl = getGurlSpec(handle.f);
                    if (startUrl.isEmpty()) startUrl = getGurlSpec(handle.g);
                }
                if (startUrl.isEmpty()) startUrl = sActiveTabUrl;
                Log.i(TAG, "onNavigationStarted: tab=" + tab.getId() + ", url=" + startUrl);

                // Immediately advance generation & clear previous page's streams/images
                int tId = tab.getId();
                dev.brave.extension.media.IframeStreamResolver.clearTab(tId);
                dev.brave.extension.media.MediaRegistry.advanceGenerationAndClear(tId, startUrl, true);
                dev.brave.extension.media.MediaBridge.checkAndRegisterDirectMedia(tId, startUrl, "onNavigationStarted");
            }

            for (TabLifecycleListener l : LISTENERS) {
                try {
                    l.onNavigationStarted(tab, handle);
                } catch (Throwable t) {
                    Log.e(TAG, "Error in onNavigationStarted listener", t);
                }
            }
        });
    }

    public static void onNavigationFinished(final Tab tab, final Object navHandleObj) {
        final NavigationHandle handle;
        if (navHandleObj instanceof NavigationHandle) {
            handle = (NavigationHandle) navHandleObj;
        } else {
            if (navHandleObj != null) {
                Log.w(TAG, "Unexpected navHandleObj type in onNavigationFinished: " + navHandleObj.getClass());
            }
            handle = null;
        }

        postToMain(() -> {
            if (tab != null) {
                sActiveTabRef = new WeakReference<>(tab);
                updateCachedTabState(tab);
                Log.i(TAG, "onNavigationFinished: tab=" + tab.getId() + ", url=" + sActiveTabUrl + ", handle=" + (handle != null ? "committed=" + handle.i : "null"));

                String finishUrl = "";
                if (handle != null) {
                    finishUrl = getGurlSpec(handle.f);
                    if (finishUrl.isEmpty()) finishUrl = getGurlSpec(handle.g);
                }
                if (finishUrl.isEmpty()) finishUrl = sActiveTabUrl;

                final int tId = tab.getId();
                if (handle != null && handle.i && !handle.k) {
                    // Ensure stale items are cleared if URL changed (e.g. redirect or SPA commit)
                    // WITHOUT clearing items already detected for this exact URL during start
                    dev.brave.extension.media.MediaRegistry.advanceGenerationAndClear(tId, finishUrl, false);
                }
                final long capturedGen = dev.brave.extension.media.MediaRegistry.getTabGeneration(tId);

                dev.brave.extension.media.MediaBridge.checkAndRegisterDirectMedia(tId, finishUrl, "onNavigationFinished");

                if (finishUrl != null && (finishUrl.startsWith("http://") || finishUrl.startsWith("https://"))) {
                    dev.brave.extension.media.IframeStreamResolver.resolveIframeAsync(finishUrl, "", tId);
                }

                if (tab.getWebContents() != null) {
                    final WebContents wc = tab.getWebContents();
                    final String fUrl = finishUrl;
                    scanChildFrames(wc, tId, fUrl, capturedGen);
                    MAIN_HANDLER.postDelayed(() -> scanChildFrames(wc, tId, fUrl, capturedGen), 600);
                    MAIN_HANDLER.postDelayed(() -> scanChildFrames(wc, tId, fUrl, capturedGen), 1500);
                }
            }

            for (TabLifecycleListener l : LISTENERS) {
                try {
                    l.onNavigationFinished(tab, handle);
                } catch (Throwable t) {
                    Log.e(TAG, "Error in onNavigationFinished listener", t);
                }
            }
        });
    }

    public static void onTabDestroyed(final Tab tab) {
        postToMain(() -> {
            if (tab != null) {
                dev.brave.extension.media.IframeStreamResolver.clearTab(tab.getId());
            }
            Tab current = sActiveTabRef.get();
            if (current == tab || (tab != null && tab.getId() == sActiveTabId)) {
                sActiveTabRef.clear();
                sActiveTabId = -1;
                sActiveTabUrl = "";
                sIsIncognito = false;
            }

            for (TabLifecycleListener l : LISTENERS) {
                try {
                    l.onTabDestroyed(tab);
                } catch (Throwable t) {
                    Log.e(TAG, "Error in onTabDestroyed listener", t);
                }
            }
        });
    }

    private static void updateCachedTabState(Tab tab) {
        if (tab != null) {
            try {
                sActiveTabId = tab.getId();
                sIsIncognito = tab.isIncognito();
                String url = getGurlSpec(tab.getUrl());
                if ((url == null || url.isEmpty()) && tab.getWebContents() != null) {
                    try {
                        url = getGurlSpec(tab.getWebContents().C());
                    } catch (Throwable ignored) {}
                    if (url == null || url.isEmpty()) {
                        try {
                            url = getGurlSpec(tab.getWebContents().b0());
                        } catch (Throwable ignored) {}
                    }
                }
                sActiveTabUrl = (url != null) ? url : "";
            } catch (Throwable t) {
                Log.w(TAG, "Failed reading tab state", t);
            }
        } else {
            sActiveTabId = -1;
            sActiveTabUrl = "";
            sIsIncognito = false;
        }
    }

    private static void postToMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            MAIN_HANDLER.post(r);
        }
    }

    public static void scanChildFrames(WebContents webContents, int tabId, String pageUrl, long expectedGen) {
        if (webContents == null) return;
        if (expectedGen > 0 && expectedGen != dev.brave.extension.media.MediaRegistry.getTabGeneration(tabId)) {
            return; // Stale delayed callback from previous page
        }
        try {
            RenderFrameHost mainFrame = webContents.t2();
            if (mainFrame == null) mainFrame = webContents.j();
            if (mainFrame != null) {
                scanFrameRecursive(mainFrame, tabId, pageUrl, 0);
            }
        } catch (Throwable t) {
            Log.d(TAG, "scanChildFrames error", t);
        }
    }

    private static void scanFrameRecursive(RenderFrameHost frame, int tabId, String pageUrl, int depth) {
        if (frame == null || depth > 3) return;
        try {
            GURL url = frame.w();
            String spec = getGurlSpec(url);
            if (spec != null && (spec.startsWith("http://") || spec.startsWith("https://")) && !spec.equals(pageUrl)) {
                Log.i(TAG, "Native child frame detected: " + spec);
                dev.brave.extension.media.IframeStreamResolver.resolveIframeAsync(spec, pageUrl, tabId);
            }
            List<RenderFrameHost> children = frame.r();
            if (children != null) {
                for (RenderFrameHost child : children) {
                    scanFrameRecursive(child, tabId, pageUrl, depth + 1);
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "scanFrameRecursive error", t);
        }
    }
}
