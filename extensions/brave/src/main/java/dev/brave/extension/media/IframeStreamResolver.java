package dev.brave.extension.media;

import android.net.Uri;
import android.util.Log;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * Universal, domain-agnostic background resolver for video streams, embedded players,
 * and cross-origin iframes. Enforces SSRF protection, bounded pools, bounded recursion,
 * retryable failure state tracking, radix-aware P.A.C.K.E.R unpacking, and port-preserving API URLs.
 */
public final class IframeStreamResolver {
    private static final String TAG = "Brave_IframeResolver";
    private static final int MAX_HTML_CHARS = 1024 * 1024; // 1 MB cap
    private static final int MAX_PLAYLIST_CHARS = 4 * 1024 * 1024; // 4M chars strict cap
    private static final int MAX_NESTED_PER_LEVEL = 5;
    private static final int MAX_NESTED_TOTAL = 10;
    private static final long FAILED_RETRY_COOLDOWN_MS = 15_000L;
    private static final int MAX_FAILED_RETRIES = 2;

    private static final ExecutorService RESOLVER_POOL = NetworkSafety.newBoundedPool(4, 48, "BraveIframeResolver");

    private enum Status { PENDING, RESOLVED, FAILED }

    private static final class ResolveEntry {
        final Status status;
        final long timestamp;
        final int failures;

        ResolveEntry(Status status, long timestamp, int failures) {
            this.status = status;
            this.timestamp = timestamp;
            this.failures = failures;
        }
    }

    private static final Map<String, ResolveEntry> RESOLVE_STATES = new ConcurrentHashMap<>();

    // Direct stream extensions: m3u8, mpd, mp4, webm, m4v, mov, mkv, avi, flv, 3gp, ogv, gifv
    private static final Pattern DIRECT_STREAM_PATTERN = Pattern.compile(
            "https?://[^\\s\"'<>]+\\.(?:m3u8|mpd|mp4|webm|m4v|mov|mkv|avi|flv|f4v|3gp|ogv|gifv)(?:\\?[^\\s\"'<>]*)?",
            Pattern.CASE_INSENSITIVE
    );

    // Player configs (JWPlayer, Video.js, Plyr, etc.)
    private static final Pattern CONFIG_URL_PATTERN = Pattern.compile(
            "[\"']?(?:file|src|url|videoUrl|hlsUrl|source|stream)[\"']?\\s*[:=]\\s*[\"'](https?://[^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
    );

    // Dean Edwards packed script pattern: eval(function(p,a,c,k,e,d)...
    private static final Pattern PACKED_PATTERN = Pattern.compile(
            "eval\\(function\\(p,a,c,k,e,[rd]\\)\\s*\\{.*?return p;?\\}\\('(.*?)'\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*'([^']*)'\\.split\\('\\|'\\)",
            Pattern.DOTALL
    );

    // Generic API stream endpoint pattern in script (e.g. /api/stream, /api/source, /ajax/embed)
    private static final Pattern API_ENDPOINT_PATTERN = Pattern.compile(
            "[\"'](/api/(?:stream|source|v|video)[^\"']*|/ajax/[^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
    );

    private IframeStreamResolver() {}

    public static void clearTab(int tabId) {
        String prefix = tabId + ":";
        RESOLVE_STATES.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public static void resolveIframeAsync(final String targetUrl, final String pageContextUrl, final int tabId) {
        if (targetUrl == null || targetUrl.isEmpty()) return;
        final int targetTab = tabId >= 0 ? tabId : 0;
        if (!MediaRegistry.canAcceptMedia(targetTab, MediaType.VIDEO)) return;

        final long gen = MediaRegistry.getTabGeneration(targetTab);
        final String key = targetTab + ":" + gen + ":" + targetUrl;
        final long now = System.currentTimeMillis();

        ResolveEntry prev = RESOLVE_STATES.get(key);
        if (prev != null) {
            if (prev.status == Status.PENDING || prev.status == Status.RESOLVED) {
                return;
            }
            if (prev.status == Status.FAILED) {
                if (prev.failures >= MAX_FAILED_RETRIES || (now - prev.timestamp) < FAILED_RETRY_COOLDOWN_MS) {
                    return;
                }
            }
        }

        final int priorFailures = (prev != null) ? prev.failures : 0;
        RESOLVE_STATES.put(key, new ResolveEntry(Status.PENDING, now, priorFailures));

        RESOLVER_POOL.execute(() -> {
            if (gen != MediaRegistry.getTabGeneration(targetTab)) {
                RESOLVE_STATES.remove(key);
                return;
            }
            boolean succeeded = false;
            try {
                Log.i(TAG, "Resolving target for tab " + targetTab + " (gen=" + gen + "): " + targetUrl);
                AtomicInteger nestedBudget = new AtomicInteger(MAX_NESTED_TOTAL);
                succeeded = resolveInternal(targetUrl, pageContextUrl, pageContextUrl, targetTab, gen, 0, nestedBudget);
            } catch (Throwable t) {
                Log.w(TAG, "Error resolving: " + targetUrl, t);
            } finally {
                if (gen != MediaRegistry.getTabGeneration(targetTab)) {
                    RESOLVE_STATES.remove(key);
                } else if (succeeded) {
                    RESOLVE_STATES.put(key, new ResolveEntry(Status.RESOLVED, System.currentTimeMillis(), priorFailures));
                } else {
                    RESOLVE_STATES.put(key, new ResolveEntry(Status.FAILED, System.currentTimeMillis(), priorFailures + 1));
                }
            }
        });
    }

    private static boolean resolveInternal(
            String targetUrl,
            String referer,
            String pageContextUrl,
            int tabId,
            long gen,
            int depth,
            AtomicInteger nestedBudget
    ) {
        if (depth > 2) return false;
        if (gen != MediaRegistry.getTabGeneration(tabId)) return false;

        NetworkSafety.TextFetchResult fetched = fetchContentSafe(targetUrl, referer, pageContextUrl);
        if (fetched == null || fetched.body == null || fetched.body.isEmpty()) return false;
        if (gen != MediaRegistry.getTabGeneration(tabId)) return false;

        String effectiveUrl = fetched.finalUrl;
        String html = fetched.body;
        String pageTitle = extractPageTitle(html, effectiveUrl);

        String defaultPoster = "";
        try {
            Matcher ogm = Pattern.compile("<meta[^>]+(?:property|name)=[\"'](?:og:image|twitter:image)[\"'][^>]+content=[\"'](https?://[^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
            if (ogm.find()) defaultPoster = ogm.group(1);
        } catch (Throwable ignored) {}

        // 1. Scan <video> tags with <source> and poster
        java.util.Set<String> resolvedInTag = new java.util.HashSet<>();
        try {
            Pattern videoTagPattern = Pattern.compile("<video[^>]*>(.*?)</video>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher vMatcher = videoTagPattern.matcher(html);
            while (vMatcher.find()) {
                String vTag = vMatcher.group(0);
                String poster = extractTagAttribute(vTag, "data-poster");
                if (poster.isEmpty()) poster = extractTagAttribute(vTag, "poster");
                if (poster.isEmpty()) poster = defaultPoster;

                Matcher srcMatcher = Pattern.compile("<source[^>]+src=[\"'](https?://[^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE).matcher(vTag);
                while (srcMatcher.find()) {
                    String src = srcMatcher.group(1);
                    String sTag = srcMatcher.group(0);
                    String res = extractTagAttribute(sTag, "res");
                    if (res.isEmpty()) res = extractTagAttribute(sTag, "label");
                    if (isValidStreamUrl(src)) {
                        registerFoundStream(src, effectiveUrl, pageContextUrl, tabId, gen, pageTitle, poster, res);
                        resolvedInTag.add(src);
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 2. Direct regex scan for .m3u8, .mp4, .mpd URLs
        Matcher streamMatcher = DIRECT_STREAM_PATTERN.matcher(html);
        while (streamMatcher.find()) {
            String candidate = streamMatcher.group(0);
            if (!resolvedInTag.contains(candidate) && isValidStreamUrl(candidate)) {
                registerFoundStream(candidate, effectiveUrl, pageContextUrl, tabId, gen, pageTitle, defaultPoster, "");
            }
        }

        // 3. Check for player configs: file: "...", src: "...", videoUrl: "..."
        Matcher configMatcher = CONFIG_URL_PATTERN.matcher(html);
        while (configMatcher.find()) {
            String candidate = configMatcher.group(1);
            if (!resolvedInTag.contains(candidate) && isValidStreamUrl(candidate)) {
                registerFoundStream(candidate, effectiveUrl, pageContextUrl, tabId, gen, pageTitle, defaultPoster, "");
            }
        }

        // 4. Dean Edwards packed script unpacking (radix-aware)
        Matcher packedMatcher = PACKED_PATTERN.matcher(html);
        while (packedMatcher.find()) {
            try {
                String p = packedMatcher.group(1);
                int a = Integer.parseInt(packedMatcher.group(2));
                int c = Integer.parseInt(packedMatcher.group(3));
                String[] k = packedMatcher.group(4).split("\\|", -1);
                String unpacked = unpackDeanEdwards(p, a, c, k);
                if (unpacked != null && !unpacked.isEmpty()) {
                    Matcher unpackedMatcher = DIRECT_STREAM_PATTERN.matcher(unpacked);
                    if (unpackedMatcher.find()) {
                        String stream = unpackedMatcher.group(0);
                        if (isValidStreamUrl(stream)) {
                            registerFoundStream(stream, effectiveUrl, pageContextUrl, tabId, gen, pageTitle, defaultPoster, "");
                        }
                    }
                    Matcher unpackedConfig = CONFIG_URL_PATTERN.matcher(unpacked);
                    if (unpackedConfig.find()) {
                        String stream = unpackedConfig.group(1);
                        if (isValidStreamUrl(stream)) {
                            registerFoundStream(stream, effectiveUrl, pageContextUrl, tabId, gen, pageTitle, defaultPoster, "");
                        }
                    }
                }
            } catch (Throwable t) {
                Log.d(TAG, "Unpacker error", t);
            }
        }

        // 5. Generic stream API endpoint detection (e.g. /api/stream with filecode/id)
        Matcher apiMatcher = API_ENDPOINT_PATTERN.matcher(html);
        if (apiMatcher.find()) {
            String endpoint = apiMatcher.group(1);
            tryResolveGenericApi(effectiveUrl, endpoint, html, tabId, gen, pageTitle, pageContextUrl);
        }

        // 6. Content Images in HTML (e.g. albums, gallery pages)
        if (MediaRegistry.canAcceptMedia(tabId, MediaType.IMAGE)) {
            try {
                Pattern imgPattern = Pattern.compile("<img[^>]+(?:src|data-src)=[\"'](https?://[^\"']+\\.(?:jpg|jpeg|jfif|png|webp|gif|avif|bmp|heic|heif)[^\"']*)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
                Matcher imgMatcher = imgPattern.matcher(html);
                int imgCount = 0;
                final int targetTab = tabId >= 0 ? tabId : 0;
                while (imgMatcher.find() && imgCount < 100) {
                    if (gen != MediaRegistry.getTabGeneration(targetTab)) return true;
                    String imgUrl = imgMatcher.group(1);
                    String lower = imgUrl.toLowerCase(Locale.ROOT);
                    if (!lower.contains("avatar") && !lower.contains("logo") && !lower.contains("icon") && !lower.contains("analytics") && !lower.contains("tracker") && !lower.contains("doubleclick")) {
                        String fullTag = imgMatcher.group(0);
                        String alt = extractTagAttribute(fullTag, "alt");
                        if (alt.isEmpty()) alt = extractTagAttribute(fullTag, "title");
                        int w = parseIntAttribute(fullTag, "width");
                        int h = parseIntAttribute(fullTag, "height");
                        if (w == 0 || w >= 150) {
                            imgCount++;
                            String safeRef = NetworkSafety.computeSafeReferer(effectiveUrl, imgUrl);
                            MediaItem imgItem = new MediaItem(
                                    imgUrl, MediaType.IMAGE, imgUrl, alt.isEmpty() ? pageTitle : alt,
                                    lower.contains(".gif") ? "image/gif" : "image/jpeg", 0, w, h, 0,
                                    NetworkSafety.DEFAULT_USER_AGENT,
                                    "", safeRef, targetTab, imgUrl, ""
                            );
                            imgItem.pageUrl = effectiveUrl;
                            imgItem.cookieSourceUrl = "";
                            MediaRegistry.addMediaItem(targetTab, gen, imgItem);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 7. Nested iframes (strictly bounded per level and per tree)
        Pattern nestedIframePattern = Pattern.compile("<iframe[^>]+(?:src|data-src)=[\"'](https?://[^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher nestedMatcher = nestedIframePattern.matcher(html);
        int levelCount = 0;
        while (nestedMatcher.find() && levelCount < MAX_NESTED_PER_LEVEL) {
            if (gen != MediaRegistry.getTabGeneration(tabId)) return true;
            if (nestedBudget.getAndDecrement() <= 0) break;
            String nestedUrl = nestedMatcher.group(1);
            String lower = nestedUrl.toLowerCase(Locale.ROOT);
            if (!lower.contains("google") && !lower.contains("doubleclick") && !lower.contains("facebook")) {
                levelCount++;
                Log.i(TAG, "Found nested iframe: " + nestedUrl);
                resolveInternal(nestedUrl, effectiveUrl, pageContextUrl, tabId, gen, depth + 1, nestedBudget);
            }
        }
        return true;
    }

    private static boolean isValidStreamUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        if (lower.contains("google-analytics") || lower.contains("/telemetry")) return false;
        // Ignore single ts chunks
        if (!lower.contains(".m3u8") && !lower.contains(".mpd") && (lower.endsWith(".ts") || lower.contains(".ts?"))) {
            return false;
        }
        return lower.contains(".m3u8") || lower.contains(".mpd") || lower.contains(".mp4") || lower.contains(".webm")
                || lower.contains(".m4v") || lower.contains(".mov") || lower.contains(".mkv") || lower.contains(".avi")
                || lower.contains(".flv") || lower.contains(".3gp") || lower.contains(".ogv") || lower.contains(".gifv")
                || lower.contains("/hls/") || lower.contains("videoplayback");
    }

    /**
     * Unpacks Dean Edwards JavaScript packing honoring the supplied radix 'a' (2..62).
     */
    private static String unpackDeanEdwards(String p, int a, int c, String[] k) {
        int radix = (a >= 2 && a <= 62) ? a : 62;
        StringBuilder sb = new StringBuilder(p.length());
        int len = p.length();
        int i = 0;
        while (i < len) {
            char ch = p.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                int start = i;
                while (i < len && Character.isLetterOrDigit(p.charAt(i))) {
                    i++;
                }
                String token = p.substring(start, i);
                int val = parseRadix(token, radix);
                if (val >= 0 && val < k.length && (c <= 0 || val < c) && !k[val].isEmpty()) {
                    sb.append(k[val]);
                } else {
                    sb.append(token);
                }
            } else {
                sb.append(ch);
                i++;
            }
        }
        return sb.toString();
    }

    private static int parseRadix(String s, int radix) {
        try {
            long result = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                int digit;
                if (c >= '0' && c <= '9') digit = c - '0';
                else if (c >= 'a' && c <= 'z') digit = c - 'a' + 10;
                else if (c >= 'A' && c <= 'Z') digit = c - 'A' + 36;
                else return -1;
                if (digit >= radix) return -1;
                result = result * radix + digit;
                if (result > Integer.MAX_VALUE) return -1;
            }
            return (int) result;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void tryResolveGenericApi(String pageUrl, String endpoint, String html, int tabId, long gen, String pageTitle, String pageContextUrl) {
        if (gen != MediaRegistry.getTabGeneration(tabId)) return;
        try {
            String origin = NetworkSafety.formatOrigin(pageUrl);
            if (origin == null) return;

            String fullApiUrl = endpoint.startsWith("http://") || endpoint.startsWith("https://")
                    ? endpoint
                    : origin + (endpoint.startsWith("/") ? endpoint : "/" + endpoint);

            Uri uri = Uri.parse(pageUrl);
            String filecode = "";
            String path = uri.getPath();
            if (path != null) {
                String[] segments = path.split("/");
                for (int i = segments.length - 1; i >= 0; i--) {
                    if (!segments[i].isEmpty()) {
                        filecode = segments[i];
                        break;
                    }
                }
            }

            if (filecode.isEmpty()) {
                Matcher fcMatcher = Pattern.compile("(?:filecode|file_id|videoId|video_id|id)\\s*[:=]\\s*[\"']([^\"']+)[\"']").matcher(html);
                if (fcMatcher.find()) {
                    filecode = fcMatcher.group(1);
                }
            }

            if (filecode.isEmpty()) return;

            JSONObject body = new JSONObject();
            body.put("filecode", filecode);
            body.put("id", filecode);
            body.put("device", "android");
            body.put("codecs", new org.json.JSONArray());

            String apiResp = postBodySafe(fullApiUrl, "application/json", body.toString(), pageUrl, pageContextUrl);
            if (apiResp == null || apiResp.isEmpty()) {
                String formBody = "filecode=" + Uri.encode(filecode) + "&id=" + Uri.encode(filecode) + "&device=android";
                apiResp = postBodySafe(fullApiUrl, "application/x-www-form-urlencoded", formBody, pageUrl, pageContextUrl);
            }
            if (apiResp != null && !apiResp.isEmpty()) {
                Matcher m = DIRECT_STREAM_PATTERN.matcher(apiResp);
                if (m.find()) {
                    registerFoundStream(m.group(0), pageUrl, pageContextUrl, tabId, gen, pageTitle, "", "");
                    return;
                }
                try {
                    JSONObject json = new JSONObject(apiResp);
                    String stream = json.optString("streaming_url", "");
                    if (stream.isEmpty()) stream = json.optString("file", "");
                    if (stream.isEmpty()) stream = json.optString("url", "");
                    if (stream.isEmpty()) stream = json.optString("src", "");
                    if (!stream.isEmpty()) {
                        String thumb = json.optString("thumbnail", "");
                        if (thumb.isEmpty()) thumb = json.optString("poster", "");
                        String title = json.optString("title", pageTitle);
                        registerFoundStream(stream, pageUrl, pageContextUrl, tabId, gen, title, thumb, "");
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.d(TAG, "tryResolveGenericApi error", t);
        }
    }

    private static String extractTagAttribute(String tag, String attrName) {
        try {
            Matcher m = Pattern.compile(attrName + "=['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE).matcher(tag);
            if (m.find()) return m.group(1).trim();
        } catch (Throwable ignored) {}
        return "";
    }

    private static int parseIntAttribute(String tag, String attrName) {
        try {
            String val = extractTagAttribute(tag, attrName);
            if (!val.isEmpty()) return Integer.parseInt(val);
        } catch (Throwable ignored) {}
        return 0;
    }

    private static void registerFoundStream(String streamUrl, String sourceUrl, String pageContextUrl, int tabId, long gen, String title, String thumb, String quality) {
        if (streamUrl == null || streamUrl.isEmpty()) return;
        final int targetTab = tabId >= 0 ? tabId : 0;
        if (gen != MediaRegistry.getTabGeneration(targetTab)) return;

        MediaType type = MediaType.VIDEO;
        String lower = streamUrl.toLowerCase(Locale.ROOT);
        if (lower.contains(".m3u8") || lower.contains("/hls/")) {
            type = MediaType.HLS_STREAM;
        } else if (lower.contains(".mpd") || lower.contains("/dash/")) {
            type = MediaType.DASH_STREAM;
        }

        if (!MediaRegistry.canAcceptMedia(targetTab, type)) return;

        final String effectivePage = (sourceUrl != null && !sourceUrl.isEmpty()) ? sourceUrl : pageContextUrl;
        if (!NetworkSafety.isAllowedTargetUrl(streamUrl, pageContextUrl)) return;

        Log.i(TAG, "UNIVERSALLY RESOLVED STREAM: tab=" + targetTab + " (gen=" + gen + "), " + streamUrl + " (quality=" + quality + ", thumb=" + thumb + ")");

        final String safeRef = NetworkSafety.computeSafeReferer(effectivePage, streamUrl);
        final MediaItem item = new MediaItem(
                streamUrl, type, streamUrl, title, "", 0, 0, 0, 0,
                NetworkSafety.DEFAULT_USER_AGENT,
                "", safeRef, targetTab, thumb, quality
        );
        item.pageUrl = effectivePage != null ? effectivePage : "";
        item.cookieSourceUrl = "";

        if (type == MediaType.HLS_STREAM) {
            try {
                NetworkSafety.TextFetchResult plRes = NetworkSafety.fetchTextStrict(
                        streamUrl, NetworkSafety.DEFAULT_USER_AGENT, "", "",
                        effectivePage, pageContextUrl, MAX_PLAYLIST_CHARS, 8000
                );
                if (gen != MediaRegistry.getTabGeneration(targetTab)) return;
                if (plRes != null && plRes.body != null && !plRes.body.isEmpty()) {
                    String playlist = plRes.body;
                    if (playlist.contains("#EXT-X-KEY:") &&
                            (playlist.contains("SAMPLE-AES") || playlist.contains("KEYFORMAT=\"urn:uuid:"))) {
                        Log.i(TAG, "Ignoring DRM-protected HLS stream: " + streamUrl);
                        return;
                    }
                    if (HlsParser.isIFramesOnlyPlaylist(playlist)) {
                        Log.i(TAG, "Ignoring I-frames-only HLS playlist: " + streamUrl);
                        return;
                    }
                    List<MediaItem.HlsVariant> variants = HlsParser.parseMasterPlaylist(plRes.finalUrl, playlist);
                    Collections.sort(variants);
                    item.variants.addAll(variants);
                }
            } catch (Throwable ignored) {}
        }

        boolean added = MediaRegistry.addMediaItem(targetTab, gen, item);
        if (added && type == MediaType.VIDEO && item.sizeBytes <= 0) {
            probeContentLengthAsync(item);
        }
    }

    public static void probeContentLengthAsync(final MediaItem item) {
        if (item == null || item.sizeBytes > 0 || item.url == null || item.type != MediaType.VIDEO) return;
        RESOLVER_POOL.execute(() -> {
            NetworkSafety.SafeConnection sc = null;
            try {
                String refPage = (item.pageUrl != null && !item.pageUrl.isEmpty()) ? item.pageUrl : item.referer;
                sc = NetworkSafety.openSafeConnection(
                        item.url, "HEAD", item.userAgent, item.cookie, item.cookieSourceUrl,
                        refPage, refPage, null, false, 3500, 3500
                );
                if (sc.responseCode >= 200 && sc.responseCode < 300) {
                    long len = sc.conn.getContentLengthLong();
                    if (len > 0) {
                        item.sizeBytes = len;
                        Log.d(TAG, "Probed content length for " + item.url + ": " + len + " bytes");
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (sc != null && sc.conn != null) sc.conn.disconnect();
            }
        });
    }

    private static String extractPageTitle(String html, String fallbackUrl) {
        try {
            Matcher m = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find()) {
                String t = m.group(1).trim();
                if (!t.isEmpty() && !t.equalsIgnoreCase("embed") && !t.equalsIgnoreCase("player")) {
                    return t;
                }
            }
        } catch (Throwable ignored) {}
        try {
            Uri u = Uri.parse(fallbackUrl);
            String last = u.getLastPathSegment();
            if (last != null && !last.isEmpty()) return last;
        } catch (Throwable ignored) {}
        return "Video_" + Math.abs(fallbackUrl.hashCode() % 100000);
    }

    private static NetworkSafety.TextFetchResult fetchContentSafe(String urlStr, String referer, String pageContextUrl) {
        try {
            return NetworkSafety.fetchTextSafe(
                    urlStr, NetworkSafety.DEFAULT_USER_AGENT, "", "",
                    referer, pageContextUrl, MAX_HTML_CHARS, 8000
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String postBodySafe(String urlStr, String contentType, String body, String referer, String pageContextUrl) {
        try {
            String origin = NetworkSafety.formatOrigin(referer);
            NetworkSafety.TextFetchResult res = NetworkSafety.postTextSafe(
                    urlStr, contentType, body, NetworkSafety.DEFAULT_USER_AGENT,
                    referer, pageContextUrl, origin, MAX_HTML_CHARS, 8000
            );
            return res != null ? res.body : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
