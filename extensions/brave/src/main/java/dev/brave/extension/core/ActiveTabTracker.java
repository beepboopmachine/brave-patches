package dev.brave.extension.core;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.util.concurrent.CopyOnWriteArrayList;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.content_public.browser.NavigationHandle;
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
        return sActiveTabId;
    }

    public static String getActiveTabUrl() {
        return sActiveTabUrl;
    }

    public static boolean isIncognito() {
        return sIsIncognito;
    }

    // -------------------------------------------------------------
    // Bytecode Hook Entry Points (Invoked from Smali fingerprints)
    // -------------------------------------------------------------

    public static void onActiveTabChanged(final Tab tab) {
        postToMain(() -> {
            sActiveTabRef = new WeakReference<>(tab);
            updateCachedTabState(tab);

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
            if (tab == sActiveTabRef.get()) {
                updateCachedTabState(tab);
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
                GURL gurl = tab.getUrl();
                sActiveTabUrl = (gurl != null) ? gurl.i() : "";
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
}
