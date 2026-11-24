package dev.brave.extension.media;

import android.net.Uri;
import android.util.Log;
import android.webkit.JavascriptInterface;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
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
 * Supports all non-DRM video, audio, HLS, GIF/animation, and image formats,
 * including local HTTP (127.0.0.1 / LAN) streams, with per-tab generation guards
 * and cross-domain cookie isolation.
 */
public class MediaBridge {
    private static final String TAG = "Brave_MediaBridge";
    private static final int MAX_PLAYLIST_BYTES = 1024 * 1024; // 1 MB cap
    private static final ExecutorService PARSER_POOL = Executors.newFixedThreadPool(2);

    @JavascriptInterface
    public void onPageUrlChanged(String newUrl) {
        if (newUrl == null || newUrl.isEmpty()) return;
        int tabId = resolveActiveTabId();
        Log.i(TAG, "SPA/Page URL changed on tab " + tabId + ": " + newUrl);
        IframeStreamResolver.clearTab(tabId);
        MediaRegistry.advanceGenerationAndClear(tabId, newUrl, false);
        if (YouTubeExtractor.isYouTubeUrl(newUrl)) {
            YouTubeExtractor.resolveAsync(tabId, newUrl);
        }
    }

    @JavascriptInterface
    public void onIframeDetected(String iframeUrl, String pageUrl) {
        if (iframeUrl == null || iframeUrl.isEmpty()) return;
        int tabId = resolveActiveTabId();
        Log.i(TAG, "onIframeDetected: " + iframeUrl + " (referer=" + pageUrl + ", tab=" + tabId + ")");
        IframeStreamResolver.resolveIframeAsync(iframeUrl, pageUrl, tabId);
    }

    @JavascriptInterface
    public void onMediaFound(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) return;

        try {
            JSONObject obj = new JSONObject(jsonString);
            String url = obj.optString("url", "").trim();
            if (url.isEmpty()) return;

            String urlLower = url.toLowerCase(Locale.ROOT);
            if (!urlLower.startsWith("http://") && !urlLower.startsWith("https://")) {
                return;
            }
            // Filter out un-downloadable YouTube SABR / raw googlevideo page-sniffer URLs (YouTubeExtractor provides verified streams)
            if (urlLower.contains("googlevideo.com/videoplayback")) {
                return;
            }

            Uri parsedUri = Uri.parse(url);
            String rawHost = parsedUri.getHost();
            if (rawHost == null || rawHost.isEmpty()) {
                return;
            }
            String cleanHost = rawHost.toLowerCase(Locale.ROOT);
            if (cleanHost.startsWith("[") && cleanHost.endsWith("]")) {
                cleanHost = cleanHost.substring(1, cleanHost.length() - 1);
            }

            // Cookie isolation: do not leak first-party cookies to cross-domain CDNs
            String referer = obj.optString("referer", "");
            String cookie = obj.optString("cookie", "");
            if (referer.isEmpty()) {
                cookie = "";
            } else {
                Uri refererUri = Uri.parse(referer);
                String refHost = refererUri.getHost();
                if (refHost == null || !refHost.equalsIgnoreCase(cleanHost)) {
                    cookie = "";
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
            String thumbnail = obj.optString("thumbnail", "");
            String quality = obj.optString("quality", "");
            if (quality.isEmpty()) quality = obj.optString("label", "");
            if (quality.isEmpty()) quality = obj.optString("res", "");

            MediaType type = classifyMediaType(urlLower, typeStr, mimeType);
            final int capturedTabId = resolveActiveTabId();
            final long capturedGen = MediaRegistry.getTabGeneration(capturedTabId);

            Log.i(TAG, "onMediaFound: " + type + " -> " + url + " (tab=" + capturedTabId + ", gen=" + capturedGen + ", quality=" + quality + ")");

            final MediaItem item = new MediaItem(
                    url, type, url, title, mimeType, size, width, height, duration,
                    userAgent, cookie, referer, capturedTabId, thumbnail, quality
            );

            if (type == MediaType.HLS_STREAM) {
                PARSER_POOL.execute(() -> {
                    if (capturedGen != MediaRegistry.getTabGeneration(capturedTabId)) return;
                    try {
                        String playlistContent = fetchUrlContent(item.url, item.userAgent, item.cookie, item.referer);
                        if (playlistContent != null && !playlistContent.isEmpty()) {
                            if (playlistContent.contains("#EXT-X-KEY:") &&
                                    (playlistContent.contains("SAMPLE-AES") || playlistContent.contains("KEYFORMAT=\"urn:uuid:"))) {
                                Log.i(TAG, "Ignoring DRM-protected HLS stream: " + item.url);
                                return;
                            }
                            List<MediaItem.HlsVariant> variants = HlsParser.parseMasterPlaylist(item.url, playlistContent);
                            Collections.sort(variants);
                            item.variants.addAll(variants);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed parsing HLS master playlist for " + item.url, t);
                    }
                    MediaRegistry.addMediaItem(capturedTabId, capturedGen, item);
                });
            } else {
                MediaRegistry.addMediaItem(capturedTabId, capturedGen, item);
                if (type == MediaType.VIDEO && item.sizeBytes <= 0) {
                    IframeStreamResolver.probeContentLengthAsync(item);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error handling onMediaFound payload", t);
        }
    }

    private static MediaType classifyMediaType(String urlLower, String typeStr, String mimeType) {
        if (urlLower.contains(".m3u8") || typeStr.contains("HLS") || mimeType.contains("mpegurl")) {
            return MediaType.HLS_STREAM;
        }
        if (urlLower.contains(".mpd") || typeStr.contains("DASH") || mimeType.contains("dash+xml")) {
            return MediaType.DASH_STREAM;
        }
        if ("IMAGE".equals(typeStr) || (mimeType.startsWith("image/") && !mimeType.equals("image/gif"))) {
            return MediaType.IMAGE;
        }
        if ("AUDIO".equals(typeStr) || mimeType.startsWith("audio/")) {
            return MediaType.AUDIO;
        }
        if (urlLower.contains(".gifv") || "VIDEO".equals(typeStr) || mimeType.startsWith("video/")) {
            return MediaType.VIDEO;
        }
        if (mimeType.equals("image/gif") || urlLower.contains(".gif")) {
            return "VIDEO".equals(typeStr) ? MediaType.VIDEO : MediaType.IMAGE;
        }
        return MediaType.VIDEO;
    }

    private static int resolveActiveTabId() {
        int tabId = ActiveTabTracker.getActiveTabId();
        if (tabId < 0) {
            org.chromium.chrome.browser.tab.Tab tab = ActiveTabTracker.getActiveTab();
            if (tab != null) {
                try {
                    tabId = tab.getId();
                } catch (Throwable ignored) {}
            }
        }
        return tabId >= 0 ? tabId : 0;
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
                        if (total > MAX_PLAYLIST_BYTES) break;
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

    public static void checkAndRegisterDirectMedia(int tabId, String url, String stage) {
        if (url == null || url.isEmpty()) return;
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return;

        if (YouTubeExtractor.isYouTubeUrl(url)) {
            YouTubeExtractor.resolveAsync(tabId >= 0 ? tabId : 0, url);
            return;
        }

        MediaType type = null;
        if (lower.contains(".m3u8") || lower.contains("/hls/") || lower.contains("master.m3u8") || lower.contains("playlist.m3u8")) {
            type = MediaType.HLS_STREAM;
        } else if (lower.contains(".mpd") || lower.contains("/dash/")) {
            type = MediaType.DASH_STREAM;
        } else if (lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".m4v") || lower.contains(".mov")
                || lower.contains(".mkv") || lower.contains(".avi") || lower.contains(".flv") || lower.contains(".f4v")
                || lower.contains(".3gp") || lower.contains(".ogv") || lower.contains(".wmv") || lower.contains(".gifv")
                || lower.contains("videoplayback")) {
            type = MediaType.VIDEO;
        } else if (lower.contains(".mp3") || lower.contains(".m4a") || lower.contains(".aac") || lower.contains(".ogg")
                || lower.contains(".opus") || lower.contains(".flac") || lower.contains(".wav") || lower.contains(".weba")) {
            type = MediaType.AUDIO;
        } else if (lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") || lower.contains(".webp")
                || lower.contains(".gif") || lower.contains(".avif") || lower.contains(".bmp") || lower.contains(".heic")
                || lower.contains(".heif") || lower.contains(".svg")) {
            type = MediaType.IMAGE;
        }

        if (type != null) {
            final int targetTab = tabId >= 0 ? tabId : 0;
            final long capturedGen = MediaRegistry.getTabGeneration(targetTab);
            Log.i(TAG, "Direct media detected during " + stage + ": tab=" + targetTab + ", " + type + " -> " + url);
            String title = extractTitleFromUrl(url);
            final MediaItem item = new MediaItem(
                    url, type, url, title, "", 0, 0, 0, 0,
                    "", "", url, targetTab
            );
            if (type == MediaType.HLS_STREAM) {
                PARSER_POOL.execute(() -> {
                    if (capturedGen != MediaRegistry.getTabGeneration(targetTab)) return;
                    try {
                        String playlistContent = fetchUrlContent(item.url, item.userAgent, item.cookie, item.referer);
                        if (playlistContent != null && !playlistContent.isEmpty()) {
                            if (playlistContent.contains("#EXT-X-KEY:") &&
                                    (playlistContent.contains("SAMPLE-AES") || playlistContent.contains("KEYFORMAT=\"urn:uuid:"))) {
                                Log.i(TAG, "Ignoring DRM-protected HLS stream: " + item.url);
                                return;
                            }
                            List<MediaItem.HlsVariant> variants = HlsParser.parseMasterPlaylist(item.url, playlistContent);
                            Collections.sort(variants);
                            item.variants.addAll(variants);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed parsing HLS master playlist for " + item.url, t);
                    }
                    MediaRegistry.addMediaItem(targetTab, capturedGen, item);
                });
            } else {
                MediaRegistry.addMediaItem(targetTab, capturedGen, item);
            }
        }
    }

    private static String extractTitleFromUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            String last = uri.getLastPathSegment();
            if (last != null && !last.isEmpty()) return last;
        } catch (Throwable ignored) {}
        return "Stream_" + Math.abs(url.hashCode() % 100000);
    }
}
