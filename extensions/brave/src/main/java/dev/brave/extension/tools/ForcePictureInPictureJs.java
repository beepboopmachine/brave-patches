package dev.brave.extension.tools;

import dev.brave.extension.core.JsInjectionHub;

public final class ForcePictureInPictureJs {
    private static volatile boolean sRegistered = false;

    private ForcePictureInPictureJs() {}

    /**
     * Registers Force Picture-in-Picture into JsInjectionHub so it runs automatically on every page
     * whenever the Force Picture-in-Picture Morphe patch is enabled.
     */
    public static void ensureRegistered() {
        if (sRegistered) return;
        synchronized (ForcePictureInPictureJs.class) {
            if (!sRegistered) {
                JsInjectionHub.registerScript(
                        "force_picture_in_picture",
                        JsInjectionHub.InjectionTiming.ON_NAVIGATION_FINISH,
                        getEnableJs()
                );
                sRegistered = true;
            }
        }
    }

    /**
     * Permanently removes disablepictureinpicture restrictions on all current and future HTML5
     * <video> elements (including inside Shadow DOM) and overrides HTMLVideoElement.prototype.disablePictureInPicture.
     */
    public static String getEnableJs() {
        return "(() => {"
                + "  if (window._brave_force_pip_installed) return;"
                + "  window._brave_force_pip_installed = true;"
                + "  try {"
                + "    Object.defineProperty(HTMLVideoElement.prototype, 'disablePictureInPicture', {"
                + "      get: () => false,"
                + "      set: () => {},"
                + "      configurable: true"
                + "    });"
                + "  } catch (e) {}"
                + "  function unlockVideos(root) {"
                + "    if (!root || !root.querySelectorAll) return;"
                + "    const videos = root.querySelectorAll('video');"
                + "    for (let i = 0; i < videos.length; i++) {"
                + "      try { videos[i].removeAttribute('disablepictureinpicture'); } catch (e) {}"
                + "    }"
                + "    const all = root.querySelectorAll('*');"
                + "    for (let i = 0; i < all.length; i++) {"
                + "      if (all[i].shadowRoot) unlockVideos(all[i].shadowRoot);"
                + "    }"
                + "  }"
                + "  unlockVideos(document);"
                + "  try {"
                + "    const mo = new MutationObserver((mutations) => {"
                + "      for (const m of mutations) {"
                + "        if (m.type === 'attributes' && m.target && m.target.tagName === 'VIDEO') {"
                + "          try { m.target.removeAttribute('disablepictureinpicture'); } catch (e) {}"
                + "        } else if (m.addedNodes) {"
                + "          for (const n of m.addedNodes) {"
                + "            if (n.nodeType === 1) {"
                + "              if (n.tagName === 'VIDEO') {"
                + "                try { n.removeAttribute('disablepictureinpicture'); } catch (e) {}"
                + "              } else {"
                + "                unlockVideos(n);"
                + "              }"
                + "            }"
                + "          }"
                + "        }"
                + "      }"
                + "    });"
                + "    mo.observe(document.documentElement || document, {"
                + "      childList: true, subtree: true, attributes: true, attributeFilter: ['disablepictureinpicture']"
                + "    });"
                + "  } catch (e) {}"
                + "})();";
    }
}
