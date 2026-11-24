package dev.brave.extension.media;

import android.net.Uri;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.json.JSONObject;

/**
 * Universal, domain-agnostic background resolver for video streams, embedded players,
 * and cross-origin iframes. Discovers media without requiring user playback (1DM-style).
 */
public final class IframeStreamResolver {
    private static final String TAG = "Brave_IframeResolver";
    private static final ExecutorService RESOLVER_POOL = Executors.newFixedThreadPool(4);
    private static final Set<String> PROCESSED_URLS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Direct stream extensions: m3u8, mpd, mp4, webm, m4v, mov, mkv, avi, flv, 3gp, ogv, gifv
    private static final Pattern DIRECT_STREAM_PATTERN = Pattern.compile(
            "https?://[^\\s\"'<>]+\\.(?:m3u8|mpd|mp4|webm|m4v|mov|mkv|avi|flv|f4v|3gp|ogv|gifv)(?:\\?[^\\s\"'<>]*)?",
            Pattern.CASE_INSENSITIVE
    );

    // Player configs (JWPlayer, Video.js, Plyr, adult players, etc.)
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
        PROCESSED_URLS.removeIf(key -> key.startsWith(prefix));
    }

    public static void resolveIframeAsync(final String targetUrl, final String referer, final int tabId) {
        if (targetUrl == null || targetUrl.isEmpty()) return;
        final int targetTab = tabId >= 0 ? tabId : 0;
        final long gen = MediaRegistry.getTabGeneration(targetTab);
        final String key = targetTab + ":" + gen + ":" + targetUrl;
        if (PROCESSED_URLS.contains(key)) return;
        PROCESSED_URLS.add(key);

        RESOLVER_POOL.execute(() -> {
            if (gen != MediaRegistry.getTabGeneration(targetTab)) return;
            try {
                Log.i(TAG, "Resolving target for tab " + targetTab + " (gen=" + gen + "): " + targetUrl);
                resolveInternal(targetUrl, referer, targetTab, gen, 0);
            } catch (Throwable t) {
                Log.w(TAG, "Error resolving: " + targetUrl, t);
            }
        });
    }

    private static void resolveInternal(String targetUrl, String referer, int tabId, long gen, int depth) {
        if (depth > 2) return; // Prevent excessive recursion
        if (gen != MediaRegistry.getTabGeneration(tabId)) return;

        String html = fetchContent(targetUrl, referer);
        if (html == null || html.isEmpty()) return;
        if (gen != MediaRegistry.getTabGeneration(tabId)) return;

        String pageTitle = extractPageTitle(html, targetUrl);

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
                        registerFoundStream(src, targetUrl, referer, tabId, gen, pageTitle, poster, res);
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
                registerFoundStream(candidate, targetUrl, referer, tabId, gen, pageTitle, defaultPoster, "");
            }
        }

        // 3. Check for player configs: file: "...", src: "...", videoUrl: "..."
        Matcher configMatcher = CONFIG_URL_PATTERN.matcher(html);
        while (configMatcher.find()) {
            String candidate = configMatcher.group(1);
            if (!resolvedInTag.contains(candidate) && isValidStreamUrl(candidate)) {
                registerFoundStream(candidate, targetUrl, referer, tabId, gen, pageTitle, defaultPoster, "");
            }
        }

        // 4. Dean Edwards packed script unpacking (used by Streamtape, Doodstream, Mixdrop, etc.)
        Matcher packedMatcher = PACKED_PATTERN.matcher(html);
        while (packedMatcher.find()) {
            try {
                String p = packedMatcher.group(1);
                int a = Integer.parseInt(packedMatcher.group(2));
                int c = Integer.parseInt(packedMatcher.group(3));
                String[] k = packedMatcher.group(4).split("\\|");
                String unpacked = unpackDeanEdwards(p, a, c, k);
                if (unpacked != null && !unpacked.isEmpty()) {
                    Matcher unpackedMatcher = DIRECT_STREAM_PATTERN.matcher(unpacked);
                    if (unpackedMatcher.find()) {
                        String stream = unpackedMatcher.group(0);
                        if (isValidStreamUrl(stream)) {
                            registerFoundStream(stream, targetUrl, referer, tabId, gen, pageTitle, defaultPoster, "");
                        }
                    }
                    Matcher unpackedConfig = CONFIG_URL_PATTERN.matcher(unpacked);
                    if (unpackedConfig.find()) {
                        String stream = unpackedConfig.group(1);
                        if (isValidStreamUrl(stream)) {
                            registerFoundStream(stream, targetUrl, referer, tabId, gen, pageTitle, defaultPoster, "");
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
            tryResolveGenericApi(targetUrl, endpoint, html, tabId, gen, pageTitle, referer);
        }

        // 6. Content Images in HTML (e.g. albums, gallery pages)
        try {
            Pattern imgPattern = Pattern.compile("<img[^>]+(?:src|data-src)=[\"'](https?://[^\"']+\\.(?:jpg|jpeg|jfif|png|webp|gif|avif|bmp|heic|heif)[^\"']*)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
            Matcher imgMatcher = imgPattern.matcher(html);
            int imgCount = 0;
            final int targetTab = tabId >= 0 ? tabId : 0;
            while (imgMatcher.find() && imgCount < 100) {
                if (gen != MediaRegistry.getTabGeneration(targetTab)) return;
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
                        MediaItem imgItem = new MediaItem(
                                imgUrl, MediaType.IMAGE, imgUrl, alt.isEmpty() ? pageTitle : alt,
                                lower.contains(".gif") ? "image/gif" : "image/jpeg", 0, w, h, 0,
                                "Mozilla/5.0 (Linux; Android 14; Pixel 6a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
                                "", targetUrl, targetTab, imgUrl, ""
                        );
                        MediaRegistry.addMediaItem(targetTab, gen, imgItem);
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 7. Nested iframes
        Pattern nestedIframePattern = Pattern.compile("<iframe[^>]+(?:src|data-src)=[\"'](https?://[^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher nestedMatcher = nestedIframePattern.matcher(html);
        while (nestedMatcher.find()) {
            if (gen != MediaRegistry.getTabGeneration(tabId)) return;
            String nestedUrl = nestedMatcher.group(1);
            String lower = nestedUrl.toLowerCase(Locale.ROOT);
            if (!lower.contains("google") && !lower.contains("doubleclick") && !lower.contains("facebook")) {
                Log.i(TAG, "Found nested iframe: " + nestedUrl);
                resolveInternal(nestedUrl, targetUrl, tabId, gen, depth + 1);
            }
        }
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
     * Unpacks Dean Edwards JavaScript packing (base-62 dictionary substitution).
     */
    private static String unpackDeanEdwards(String p, int a, int c, String[] k) {
        StringBuilder sb = new StringBuilder();
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
                int val = parseBase62(token);
                if (val >= 0 && val < k.length && !k[val].isEmpty()) {
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

    private static int parseBase62(String s) {
        try {
            int result = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                int digit;
                if (c >= '0' && c <= '9') digit = c - '0';
                else if (c >= 'a' && c <= 'z') digit = c - 'a' + 10;
                else if (c >= 'A' && c <= 'Z') digit = c - 'A' + 36;
                else return -1;
                result = result * 62 + digit;
            }
            return result;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void tryResolveGenericApi(String pageUrl, String endpoint, String html, int tabId, long gen, String pageTitle, String referer) {
        if (gen != MediaRegistry.getTabGeneration(tabId)) return;
        try {
            Uri uri = Uri.parse(pageUrl);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return;

            String fullApiUrl = endpoint.startsWith("http") ? endpoint : uri.getScheme() + "://" + host + (endpoint.startsWith("/") ? endpoint : "/" + endpoint);

            // Extract filecode/id from path (e.g. /e/EI0LkyU6aeJTY, /v/12345, etc.)
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

            // Fallback: extract filecode/id from HTML
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

            String apiResp = postJson(fullApiUrl, body.toString(), pageUrl);
            if (apiResp == null || apiResp.isEmpty()) {
                String formBody = "filecode=" + Uri.encode(filecode) + "&id=" + Uri.encode(filecode) + "&device=android";
                apiResp = postForm(fullApiUrl, formBody, pageUrl);
            }
            if (apiResp != null && !apiResp.isEmpty()) {
                Matcher m = DIRECT_STREAM_PATTERN.matcher(apiResp);
                if (m.find()) {
                    registerFoundStream(m.group(0), pageUrl, referer, tabId, gen, pageTitle, "", "");
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
                        registerFoundStream(stream, pageUrl, referer, tabId, gen, title, thumb, "");
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

    private static void registerFoundStream(String streamUrl, String sourceUrl, String referer, int tabId, long gen, String title, String thumb, String quality) {
        if (streamUrl == null || streamUrl.isEmpty()) return;
        final int targetTab = tabId >= 0 ? tabId : 0;
        if (gen != MediaRegistry.getTabGeneration(targetTab)) return;
        Log.i(TAG, "UNIVERSALLY RESOLVED STREAM: tab=" + targetTab + " (gen=" + gen + "), " + streamUrl + " (quality=" + quality + ", thumb=" + thumb + ")");

        MediaType type = MediaType.VIDEO;
        String lower = streamUrl.toLowerCase(Locale.ROOT);
        if (lower.contains(".m3u8") || lower.contains("/hls/")) {
            type = MediaType.HLS_STREAM;
        } else if (lower.contains(".mpd") || lower.contains("/dash/")) {
            type = MediaType.DASH_STREAM;
        }

        final String effectiveRef = (sourceUrl != null && !sourceUrl.isEmpty()) ? sourceUrl : referer;
        final MediaItem item = new MediaItem(
                streamUrl, type, streamUrl, title, "", 0, 0, 0, 0,
                "Mozilla/5.0 (Linux; Android 14; Pixel 6a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
                "", effectiveRef, targetTab, thumb, quality
        );

        if (type == MediaType.HLS_STREAM) {
            try {
                String playlist = fetchContent(streamUrl, sourceUrl);
                if (gen != MediaRegistry.getTabGeneration(targetTab)) return;
                if (playlist != null && !playlist.isEmpty()) {
                    if (playlist.contains("#EXT-X-KEY:") &&
                            (playlist.contains("SAMPLE-AES") || playlist.contains("KEYFORMAT=\"urn:uuid:"))) {
                        Log.i(TAG, "Ignoring DRM-protected HLS stream: " + streamUrl);
                        return;
                    }
                    List<MediaItem.HlsVariant> variants = HlsParser.parseMasterPlaylist(streamUrl, playlist);
                    Collections.sort(variants);
                    item.variants.addAll(variants);
                }
            } catch (Throwable ignored) {}
        }

        MediaRegistry.addMediaItem(targetTab, gen, item);
        if (type == MediaType.VIDEO && item.sizeBytes <= 0) {
            probeContentLengthAsync(item);
        }
    }

    public static void probeContentLengthAsync(final MediaItem item) {
        if (item == null || item.sizeBytes > 0 || item.url == null || item.type != MediaType.VIDEO) return;
        RESOLVER_POOL.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL u = new URL(item.url);
                conn = (HttpURLConnection) u.openConnection();
                conn.setRequestMethod("HEAD");
                conn.setConnectTimeout(3500);
                conn.setReadTimeout(3500);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 6a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
                if (item.referer != null && !item.referer.isEmpty()) {
                    conn.setRequestProperty("Referer", item.referer);
                }
                long len = conn.getContentLengthLong();
                if (len > 0) {
                    item.sizeBytes = len;
                    Log.d(TAG, "Probed content length for " + item.url + ": " + len + " bytes");
                }
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) conn.disconnect();
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

    private static String fetchContent(String urlStr, String referer) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 6a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            if (referer != null && !referer.isEmpty()) {
                conn.setRequestProperty("Referer", referer);
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                InputStream is = conn.getInputStream();
                String encoding = conn.getHeaderField("Content-Encoding");
                if ("gzip".equalsIgnoreCase(encoding)) {
                    is = new GZIPInputStream(is);
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                    StringBuilder sb = new StringBuilder();
                    char[] buf = new char[4096];
                    int r;
                    int total = 0;
                    while ((r = reader.read(buf)) != -1) {
                        total += r;
                        if (total > 1024 * 1024) break; // 1MB cap
                        sb.append(buf, 0, r);
                    }
                    return sb.toString();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static String postJson(String urlStr, String jsonBody, String referer) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 6a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            if (referer != null && !referer.isEmpty()) {
                conn.setRequestProperty("Referer", referer);
                Uri u = Uri.parse(referer);
                conn.setRequestProperty("Origin", u.getScheme() + "://" + u.getHost());
            }

            byte[] input = jsonBody.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(input.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(input);
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                InputStream is = conn.getInputStream();
                String encoding = conn.getHeaderField("Content-Encoding");
                if ("gzip".equalsIgnoreCase(encoding)) {
                    is = new GZIPInputStream(is);
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                    StringBuilder sb = new StringBuilder();
                    char[] buf = new char[4096];
                    int r;
                    while ((r = reader.read(buf)) != -1) {
                        sb.append(buf, 0, r);
                    }
                    return sb.toString();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static String postForm(String urlStr, String formBody, String referer) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 6a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            if (referer != null && !referer.isEmpty()) {
                conn.setRequestProperty("Referer", referer);
                Uri u = Uri.parse(referer);
                conn.setRequestProperty("Origin", u.getScheme() + "://" + u.getHost());
            }

            byte[] input = formBody.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(input.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(input);
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                InputStream is = conn.getInputStream();
                String encoding = conn.getHeaderField("Content-Encoding");
                if ("gzip".equalsIgnoreCase(encoding)) {
                    is = new GZIPInputStream(is);
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                    StringBuilder sb = new StringBuilder();
                    char[] buf = new char[4096];
                    int r;
                    while ((r = reader.read(buf)) != -1) {
                        sb.append(buf, 0, r);
                    }
                    return sb.toString();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }
}
