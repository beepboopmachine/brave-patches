package dev.brave.extension.core;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.components.origin_matcher.OriginMatcher;
import org.chromium.content.browser.JavascriptInjectorImpl;
import org.chromium.content_public.browser.LoadUrlParams;
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

    // Cached reflection Method for Tab.loadUrl (Tab.k(LoadUrlParams))
    private static volatile Method sTabLoadUrlMethod = null;
    private static volatile boolean sTabLoadUrlLookupAttempted = false;

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
            public void onActiveTabChanged(Tab tab) {
                if (tab != null && tab.getWebContents() != null) {
                    for (Map.Entry<String, Object> entry : BRIDGES.entrySet()) {
                        registerBridge(tab.getWebContents(), entry.getValue(), entry.getKey());
                    }
                    String spec = ActiveTabTracker.getGurlSpec(tab.getUrl());
                    if (spec.startsWith("http://") || spec.startsWith("https://")) {
                        executeTiming(tab, tab.getWebContents(), InjectionTiming.ON_NAVIGATION_FINISH);
                    }
                }
            }

            @Override
            public void onNavigationStarted(Tab tab, NavigationHandle handle) {
                if (tab != null && tab.getWebContents() != null) {
                    for (Map.Entry<String, Object> entry : BRIDGES.entrySet()) {
                        registerBridge(tab.getWebContents(), entry.getValue(), entry.getKey());
                    }
                }
            }

            @Override
            public void onNavigationFinished(Tab tab, NavigationHandle handle) {
                if (tab == null) return;

                // Strict guard: require non-null committed handle that is not an error page
                if (handle == null || !handle.i || handle.k) {
                    Log.d(TAG, "onNavigationFinished: skipping uncommitted or error navigation");
                    return;
                }

                // Check URL scheme across handle, tab, and webContents
                String spec = "";
                if (handle.f != null) {
                    spec = ActiveTabTracker.getGurlSpec(handle.f);
                }
                if ((spec == null || spec.isEmpty()) && handle.g != null) {
                    spec = ActiveTabTracker.getGurlSpec(handle.g);
                }
                if ((spec == null || spec.isEmpty()) && tab.getUrl() != null) {
                    spec = ActiveTabTracker.getGurlSpec(tab.getUrl());
                }
                WebContents webContents = tab.getWebContents();
                if ((spec == null || spec.isEmpty()) && webContents != null) {
                    try {
                        spec = ActiveTabTracker.getGurlSpec(webContents.C());
                    } catch (Throwable ignored) {}
                    if (spec == null || spec.isEmpty()) {
                        try {
                            spec = ActiveTabTracker.getGurlSpec(webContents.b0());
                        } catch (Throwable ignored) {}
                    }
                }

                // Filter internal browser system pages only
                if (spec != null && (spec.startsWith("chrome://") || spec.startsWith("chrome-native://") || spec.startsWith("about:") || spec.startsWith("content://"))) {
                    Log.d(TAG, "onNavigationFinished: skipping internal page spec=" + spec);
                    return;
                }

                if (webContents == null) {
                    Log.w(TAG, "onNavigationFinished: WebContents is null for tab " + tab.getId());
                    return;
                }

                Log.i(TAG, "onNavigationFinished: committed page " + spec + " for tab " + tab.getId() + " - binding bridges and injecting scripts");

                // Bind persistent bridges on Main Thread first
                for (Map.Entry<String, Object> entry : BRIDGES.entrySet()) {
                    registerBridge(webContents, entry.getValue(), entry.getKey());
                }

                // Execute finish scripts passing tab instance directly (Check 3)
                executeTiming(tab, webContents, InjectionTiming.ON_NAVIGATION_FINISH);
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
                for (Method m : webContents.getClass().getMethods()) {
                    if (m.getName().equals("H1") && m.getParameterTypes().length == 2) {
                        injector = (JavascriptInjectorImpl) m.invoke(webContents, JavascriptInjectorImpl.class, factory);
                        break;
                    }
                }
                if (injector != null) {
                    OriginMatcher originMatcher = new OriginMatcher();
                    try {
                        originMatcher.c(Collections.singletonList("*"));
                    } catch (Throwable t) {
                        Log.w(TAG, "OriginMatcher rule error", t);
                    }
                    injector.a(bridgeObject, bridgeName, originMatcher);
                    Log.i(TAG, "Successfully registered bridge " + bridgeName + " on WebContents");
                } else {
                    Log.w(TAG, "Could not resolve JavascriptInjectorImpl on WebContents");
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed to register bridge " + bridgeName, t);
            }
        });
    }

    /**
     * Check 2: Formats a javascript: URL, escaping % to %25 and # to %23
     * so GURL will not truncate the script at the first #.
     */
    public static String formatJavascriptUrl(String script) {
        if (script == null || script.isEmpty()) {
            return "javascript:void(0);";
        }
        String safeScript = script.replace("%", "%25").replace("#", "%23");
        return "javascript:(function(){" + safeScript + "})();";
    }

    private static void loadUrlOnTab(Tab tab, LoadUrlParams params) {
        if (tab == null || params == null) return;
        if (!sTabLoadUrlLookupAttempted) {
            synchronized (JsInjectionHub.class) {
                if (!sTabLoadUrlLookupAttempted) {
                    for (Method m : tab.getClass().getMethods()) {
                        if (m.getName().equals("k")) {
                            Class<?>[] pTypes = m.getParameterTypes();
                            if (pTypes.length == 1 && LoadUrlParams.class.isAssignableFrom(pTypes[0])) {
                                m.setAccessible(true);
                                sTabLoadUrlMethod = m;
                                break;
                            }
                        }
                    }
                    if (sTabLoadUrlMethod == null) {
                        for (Method m : tab.getClass().getMethods()) {
                            Class<?>[] pTypes = m.getParameterTypes();
                            if (pTypes.length == 1 && LoadUrlParams.class.isAssignableFrom(pTypes[0])) {
                                m.setAccessible(true);
                                sTabLoadUrlMethod = m;
                                break;
                            }
                        }
                    }
                    sTabLoadUrlLookupAttempted = true;
                }
            }
        }

        if (sTabLoadUrlMethod != null) {
            try {
                sTabLoadUrlMethod.invoke(tab, params);
                Log.i(TAG, "Successfully invoked Tab.k with javascript URL");
            } catch (Throwable t) {
                Log.w(TAG, "Failed invoking Tab.k via reflection", t);
            }
        } else {
            Log.w(TAG, "sTabLoadUrlMethod not found on Tab class: " + tab.getClass().getName());
        }
    }

    /**
     * Check 3: Executes a script on the specified Tab via Tab.loadUrl.
     * Replaces WebContents.M which triggered native SIGTRAP in release builds.
     */
    public static void executeScript(final Tab tab, final WebContents webContents, final String script) {
        if (script == null || script.isEmpty()) return;

        postToMain(() -> {
            Tab targetTab = tab;
            if (targetTab == null) {
                targetTab = ActiveTabTracker.getActiveTab();
            }
            if (targetTab != null) {
                try {
                    String jsUrl = formatJavascriptUrl(script);
                    Log.i(TAG, "Executing script via Tab.k (len=" + script.length() + ")");
                    loadUrlOnTab(targetTab, new LoadUrlParams(0, jsUrl));
                    return;
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to execute script via Tab.k", t);
                }
            }
            Log.w(TAG, "Cannot execute script: target Tab is null");
        });
    }

    public static void executeScript(final WebContents webContents, final String script) {
        executeScript(null, webContents, script);
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

    private static void executeTiming(Tab tab, WebContents webContents, InjectionTiming timing) {
        synchronized (SCRIPTS) {
            for (ScriptEntry entry : SCRIPTS.values()) {
                if (entry.timing == timing) {
                    executeScript(tab, webContents, entry.script);
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
