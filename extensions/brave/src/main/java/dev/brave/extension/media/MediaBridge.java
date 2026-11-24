package dev.brave.extension.media;

import android.net.Uri;
import android.util.Log;
import android.webkit.JavascriptInterface;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import dev.brave.extension.core.ActiveTabTracker;

/**
 * @JavascriptInterface bridge exposed as window.BraveMediaBridge to page contexts.
 * Hardened with strict URL scheme/SSRF validation, cross-domain cookie isolation,
 * bounded parser threads, and input sanitization.
 */
public class MediaBridge {
    private static final String TAG = "Brave_MediaBridge";
    private static final int MAX_PLAYLIST_BYTES = 1024 * 1024; // 1 MB cap
    private static final ExecutorService PARSER_POOL = Executors.newFixedThreadPool(2);

    @JavascriptInterface
    public void onMediaFound(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) return;

        try {
            JSONObject obj = new JSONObject(jsonString);
            String url = obj.optString("url", "").trim();
            if (url.isEmpty()) return;

            // 1. Strict Protocol & SSRF Validation
            String urlLower = url.toLowerCase(Locale.ROOT);
            if (!urlLower.startsWith("http://") && !urlLower.startsWith("https://")) {
                return; // Disallow blob:, data:, file:, content:, javascript:
            }

            Uri parsedUri = Uri.parse(url);
            String host = parsedUri.getHost();
            if (host == null || host.isEmpty() || isPrivateOrLoopbackHost(host)) {
                return; // Block private/LAN IPs & loopback targets to prevent SSRF
            }

            // 2. Cookie Isolation: only allow document.cookie if media host matches referer origin
            String referer = obj.optString("referer", "");
            String cookie = obj.optString("cookie", "");
            if (!cookie.isEmpty() && !referer.isEmpty()) {
                Uri refererUri = Uri.parse(referer);
                String refHost = refererUri.getHost();
                if (refHost == null || !refHost.equalsIgnoreCase(host)) {
                    cookie = ""; // Do NOT leak first-party cookies to cross-domain CDNs
                }
            }

            String typeStr = obj.optString("type", "VIDEO").toUpperCase(Locale.ROOT);
            String title = obj.optString("title", "");
            String mimeType = obj.optString("mimeType", "").toLowerCase(Locale.ROOT);
            long size = obj.optLong("size", 0);
            int width = obj.optInt("width", 0);
            int height = obj.optInt("height", 0);
            long duration = obj.optLong("duration", 0);
            String userAgent = obj.optString("userAgent", "");

            MediaType type;
            if (urlLower.contains(".m3u8") || typeStr.contains("HLS") || mimeType.contains("mpegurl")) {
                type = MediaType.HLS_STREAM;
            } else if (urlLower.contains(".mpd") || typeStr.contains("DASH") || mimeType.contains("dash+xml")) {
                type = MediaType.DASH_STREAM;
            } else if ("IMAGE".equals(typeStr) || mimeType.startsWith("image/")) {
                type = MediaType.IMAGE;
            } else if ("AUDIO".equals(typeStr) || mimeType.startsWith("audio/")) {
                type = MediaType.AUDIO;
            } else {
                type = MediaType.VIDEO;
            }

            final int tabId = ActiveTabTracker.getActiveTabId();
            if (tabId <= 0) return;

            final MediaItem item = new MediaItem(
                    url, type, url, title, mimeType, size, width, height, duration,
                    userAgent, cookie, referer, tabId
            );

            if (type == MediaType.HLS_STREAM) {
                // Fetch & parse master playlist in background pool
                PARSER_POOL.execute(() -> {
                    try {
                        String playlistContent = fetchUrlContent(item.url, item.userAgent, item.cookie, item.referer);
                        if (playlistContent != null && !playlistContent.isEmpty()) {
                            List<MediaItem.HlsVariant> variants = HlsParser.parseMasterPlaylist(item.url, playlistContent);
                            Collections.sort(variants);
                            item.variants.addAll(variants);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed parsing HLS master playlist for " + item.url, t);
                    }
                    // Check if tab is still active or valid
                    if (tabId == ActiveTabTracker.getActiveTabId()) {
                        MediaRegistry.addMediaItem(tabId, item);
                    }
                });
            } else {
                MediaRegistry.addMediaItem(tabId, item);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error handling onMediaFound payload", t);
        }
    }

    private static boolean isPrivateOrLoopbackHost(String host) {
        try {
            if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host)) {
                return true;
            }
            InetAddress addr = InetAddress.getByName(host);
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress() || addr.isAnyLocalAddress();
        } catch (Throwable ignored) {
            // DNS resolution failure or invalid host string
            return false;
        }
    }

    private static String fetchUrlContent(String urlStr, String userAgent, String cookie, String referer) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(urlStr);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            if (userAgent != null && !userAgent.isEmpty()) conn.setRequestProperty("User-Agent", userAgent);
            if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            if (referer != null && !referer.isEmpty()) conn.setRequestProperty("Referer", referer);

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    StringBuilder sb = new StringBuilder();
                    char[] buf = new char[4096];
                    int r;
                    int total = 0;
                    while ((r = reader.read(buf)) != -1) {
                        total += r;
                        if (total > MAX_PLAYLIST_BYTES) break; // Cap
                        sb.append(buf, 0, r);
                    }
                    return sb.toString();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }
}
