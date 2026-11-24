package dev.brave.extension.tools;

import dev.brave.extension.core.JsInjectionHub;

public final class ForceCopyPasteJs {
    private static volatile boolean sRegistered = false;

    private ForceCopyPasteJs() {}

    /**
     * Registers Force Copy/Paste into JsInjectionHub so it runs automatically on every page
     * whenever the Force Copy/Paste Morphe patch is enabled.
     */
    public static void ensureRegistered() {
        if (sRegistered) return;
        synchronized (ForceCopyPasteJs.class) {
            if (!sRegistered) {
                JsInjectionHub.registerScript(
                        "force_copy_paste",
                        JsInjectionHub.InjectionTiming.ON_NAVIGATION_FINISH,
                        getEnableJs()
                );
                sRegistered = true;
            }
        }
    }

    /**
     * Unblocks text selection, copy, cut, paste, and context menus.
     * Hooks capture phase strictly on window (stopping propagation before document/elements).
     * Clears both document and document.body inline blocking handlers.
     */
    public static String getEnableJs() {
        return "(() => {"
                + "  const styleId = '_brave_force_copypaste_style';"
                + "  let st = document.getElementById(styleId);"
                + "  if (!st) {"
                + "    st = document.createElement('style');"
                + "    st.id = styleId;"
                + "    st.textContent = '* { -webkit-user-select: text !important; -moz-user-select: text !important; user-select: text !important; -webkit-touch-callout: default !important; }';"
                + "    (document.head || document.documentElement).appendChild(st);"
                + "  }"
                + ""
                + "  const events = ['copy', 'cut', 'paste', 'selectstart', 'contextmenu', 'dragstart'];"
                + "  if (!window._brave_unblock_handler) {"
                + "    window._brave_unblock_handler = function(e) { e.stopPropagation(); };"
                + "    events.forEach(evt => {"
                + "      window.addEventListener(evt, window._brave_unblock_handler, true);"
                + "    });"
                + "  }"
                + "  try {"
                + "    document.oncopy = null;"
                + "    document.oncut = null;"
                + "    document.onpaste = null;"
                + "    document.oncontextmenu = null;"
                + "    document.onselectstart = null;"
                + "    if (document.body) {"
                + "      document.body.oncopy = null;"
                + "      document.body.oncut = null;"
                + "      document.body.onpaste = null;"
                + "      document.body.oncontextmenu = null;"
                + "      document.body.onselectstart = null;"
                + "    }"
                + "  } catch (ignored) {}"
                + "})();";
    }
}
