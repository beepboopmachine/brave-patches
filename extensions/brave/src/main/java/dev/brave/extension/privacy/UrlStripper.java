package dev.brave.extension.privacy;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UrlStripper {
    private static final Set<String> TRACKING_EXACT = new HashSet<>(Arrays.asList(
        "gclid", "gbraid", "wbraid", "fbclid", "msclkid", "twclid", "dclid",
        "igshid", "mc_eid", "vero_id", "_openstat"
    ));

    private static final Pattern URL_PATTERN = Pattern.compile("(?i)https?://[^\\s]+");

    /**
     * Sanitizes a URL or a text string containing a URL by stripping known tracking parameters.
     */
    public static String sanitize(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        // If the whole string is a single pure URL (starts with http(s):// and no whitespace)
        String lowerTrimmed = input.trim().toLowerCase(Locale.ROOT);
        boolean isDirectUrl = (lowerTrimmed.startsWith("http://") || lowerTrimmed.startsWith("https://"))
                && !containsWhitespace(input.trim());

        if (isDirectUrl) {
            return sanitizeSingleUrl(input.trim());
        }

        // If the string contains a URL mixed with text (e.g. "Page Title https://example.com/...")
        Matcher matcher = URL_PATTERN.matcher(input);
        if (matcher.find()) {
            StringBuffer sb = new StringBuffer();
            do {
                String matchedUrl = matcher.group();
                String sanitizedUrl = sanitizeSingleUrl(matchedUrl);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(sanitizedUrl));
            } while (matcher.find());
            matcher.appendTail(sb);
            return sb.toString();
        }

        return input;
    }

    private static boolean containsWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return true;
        }
        return false;
    }

    private static String sanitizeSingleUrl(String url) {
        try {
            int hashIdx = url.indexOf('#');
            String fragment = "";
            String urlBeforeHash = url;
            if (hashIdx >= 0) {
                fragment = url.substring(hashIdx);
                urlBeforeHash = url.substring(0, hashIdx);
            }

            int queryIdx = urlBeforeHash.indexOf('?');
            if (queryIdx < 0) {
                return url;
            }

            String base = urlBeforeHash.substring(0, queryIdx);
            String queryString = urlBeforeHash.substring(queryIdx + 1);
            if (queryString.isEmpty()) {
                return base + fragment;
            }

            String baseLower = base.toLowerCase(Locale.ROOT);
            boolean isMediaHost = baseLower.contains("youtube.com") 
                    || baseLower.contains("youtu.be") 
                    || baseLower.contains("spotify.com");

            String[] pairs = queryString.split("&");
            StringBuilder newQuery = new StringBuilder();
            for (String pair : pairs) {
                if (pair.isEmpty()) continue;
                int eqIdx = pair.indexOf('=');
                String key = eqIdx >= 0 ? pair.substring(0, eqIdx) : pair;
                if (isTrackingParam(key, isMediaHost)) {
                    continue;
                }
                if (newQuery.length() > 0) {
                    newQuery.append('&');
                }
                newQuery.append(pair);
            }

            if (newQuery.length() > 0) {
                return base + "?" + newQuery.toString() + fragment;
            } else {
                return base + fragment;
            }
        } catch (Throwable t) {
            return url;
        }
    }

    private static boolean isTrackingParam(String key, boolean isMediaHost) {
        if (key == null) return false;
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.startsWith("utm_")) {
            return true;
        }
        if (TRACKING_EXACT.contains(lower)) {
            return true;
        }
        // Scope "si" parameter strictly to YouTube / Spotify share links
        if ("si".equals(lower) && isMediaHost) {
            return true;
        }
        return false;
    }

    /**
     * Safely reads and sanitizes the URL field ('d') on ShareParams (bih) instance.
     */
    public static void sanitizeShareObject(Object shareParams) {
        if (shareParams == null) return;
        try {
            Field[] fields = shareParams.getClass().getDeclaredFields();
            for (Field f : fields) {
                if ("d".equals(f.getName()) && f.getType() == String.class) {
                    f.setAccessible(true);
                    String currentUrl = (String) f.get(shareParams);
                    if (currentUrl != null) {
                        f.set(shareParams, sanitize(currentUrl));
                    }
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }
}
