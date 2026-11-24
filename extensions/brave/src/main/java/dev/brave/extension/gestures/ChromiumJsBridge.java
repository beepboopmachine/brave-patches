package dev.brave.extension.gestures;

import dev.brave.extension.core.ActiveTabTracker;
import dev.brave.extension.core.JsInjectionHub;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.content_public.browser.WebContents;

/**
 * Safe JavaScript execution bridge for fullscreen video gestures.
 * Routes strictly through JsInjectionHub (Tab.k + LoadUrlParams) to avoid
 * WebContents.M(String) JNI SIGTRAP crashes on release builds.
 */
public final class ChromiumJsBridge {
    private ChromiumJsBridge() {}

    public static void executeScript(final Tab tab, final String script) {
        if (tab == null || script == null || script.isEmpty()) return;
        JsInjectionHub.executeScript(tab, tab.getWebContents(), script);
    }

    public static void executeScript(final WebContents webContents, final String script) {
        if (script == null || script.isEmpty()) return;
        JsInjectionHub.executeScript(ActiveTabTracker.getActiveTab(), webContents, script);
    }
}
