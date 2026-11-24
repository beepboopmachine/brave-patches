package dev.brave.extension.core;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.components.origin_matcher.OriginMatcher;
import org.chromium.content.browser.JavascriptInjectorImpl;
import org.chromium.content_public.browser.NavigationHandle;
import org.chromium.content_public.browser.WebContents;
import org.chromium.url.GURL;

/**
 * Deterministic registry for JavaScript execution and @JavascriptInterface bridge binding.
 * Guarantees main-thread safety, privilege separation, and navigation validation.
 */
public final class JsInjectionHub {
    private static final String TAG = "BraveCore_JsHub";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public enum InjectionTiming {
        ON_NAVIGATION_FINISH,
        ON_DEMAND
    }

    // Preserve insertion order deterministically
    private static final Map<String, ScriptEntry> SCRIPTS = Collections.synchronizedMap(new LinkedHashMap<>());
    private static final Map<String, Object> BRIDGES = new ConcurrentHashMap<>();

    // Cached factory object for JavascriptInjectorImpl
    private static volatile Object sCachedInjectorFactory = null;
    private static volatile boolean sFactoryResolved = false;

    private static class ScriptEntry {
        final InjectionTiming timing;
        final String script;
        ScriptEntry(InjectionTiming timing, String script) {
            this.timing = timing;
            this.script = script;
        }
    }

    static {
        ActiveTabTracker.addListener(new TabLifecycleListener() {
            @Override
            public void onActiveTabChanged(Tab tab) {}

            @Override
            public void onNavigationStarted(Tab tab, NavigationHandle handle) {
                // Deliberately do NOT execute scripts on navigation start into the outgoing page.
            }

            @Override
            public void onNavigationFinished(Tab tab, NavigationHandle handle) {
                if (tab == null) return;

                // Strict guard: require non-null committed handle that is not an error page
                if (handle == null || !handle.i || handle.k) {
                    return;
                }

                // Check URL scheme - NEVER inject into chrome://, brave://, about:blank, or file://
                GURL gurl = tab.getUrl();
                String spec = (gurl != null) ? gurl.i() : "";
                if (spec == null || (!spec.startsWith("http://") && !spec.startsWith("https://"))) {
                    return;
                }

                WebContents webContents = tab.getWebContents();
                if (webContents == null) return;

                // Bind persistent bridges on Main Thread first
                for (Map.Entry<String, Object> entry : BRIDGES.entrySet()) {
                    registerBridge(webContents, entry.getValue(), entry.getKey());
                }

                // Execute finish scripts
                executeTiming(webContents, InjectionTiming.ON_NAVIGATION_FINISH);
            }

            @Override
            public void onTabDestroyed(Tab tab) {}
        });
    }

    private JsInjectionHub() {}

    public static void registerScript(String id, InjectionTiming timing, String script) {
        if (id != null && script != null) {
            SCRIPTS.put(id, new ScriptEntry(timing, script));
        }
    }

    public static void registerPersistentBridge(String bridgeName, Object bridgeObject) {
        if (bridgeName != null && bridgeObject != null) {
            BRIDGES.put(bridgeName, bridgeObject);
            // If there is already an active tab, bind immediately on main thread
            Tab activeTab = ActiveTabTracker.getActiveTab();
            if (activeTab != null && activeTab.getWebContents() != null) {
                registerBridge(activeTab.getWebContents(), bridgeObject, bridgeName);
            }
        }
    }

    public static void registerBridge(final WebContents webContents, final Object bridgeObject, final String bridgeName) {
        if (webContents == null || bridgeObject == null || bridgeName == null) return;

        postToMain(() -> {
            try {
                Object factory = getInjectorFactory();
                if (factory == null) {
                    Log.w(TAG, "Cannot register bridge " + bridgeName + ": JavascriptInjector factory not found");
                    return;
                }

                JavascriptInjectorImpl injector = null;
                for (java.lang.reflect.Method m : webContents.getClass().getMethods()) {
                    if (m.getName().equals("H1") && m.getParameterTypes().length == 2) {
                        injector = (JavascriptInjectorImpl) m.invoke(webContents, JavascriptInjectorImpl.class, factory);
                        break;
                    }
                }
                if (injector != null) {
                    OriginMatcher originMatcher = new OriginMatcher();
                    originMatcher.c(Collections.singletonList("*"));
                    injector.a(bridgeObject, bridgeName, originMatcher);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed to register bridge " + bridgeName, t);
            }
        });
    }

    public static void executeScript(final WebContents webContents, final String script) {
        if (webContents == null || script == null || script.isEmpty()) return;

        postToMain(() -> {
            try {
                webContents.M(script);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to execute script via WebContents.M", t);
            }
        });
    }

    private static Object getInjectorFactory() {
        if (sFactoryResolved) return sCachedInjectorFactory;
        synchronized (JsInjectionHub.class) {
            if (!sFactoryResolved) {
                try {
                    Class<?> l0aClass = Class.forName("l0a");
                    sCachedInjectorFactory = l0aClass.getField("a").get(null);
                } catch (Throwable t) {
                    Log.w(TAG, "Could not resolve l0a.a factory", t);
                }
                sFactoryResolved = true;
            }
        }
        return sCachedInjectorFactory;
    }

    private static void executeTiming(WebContents webContents, InjectionTiming timing) {
        synchronized (SCRIPTS) {
            for (ScriptEntry entry : SCRIPTS.values()) {
                if (entry.timing == timing) {
                    executeScript(webContents, entry.script);
                }
            }
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
