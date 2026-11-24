package dev.brave.extension.media;

import android.util.Log;
import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HlsParser {
    private static final String TAG = "Brave_HlsParser";

    // Anchored regexes to avoid matching AVERAGE-BANDWIDTH
    private static final Pattern BANDWIDTH_PATTERN = Pattern.compile("(?:^|,)BANDWIDTH=(\\d+)");
    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");
    private static final Pattern CODECS_PATTERN = Pattern.compile("CODECS=\"([^\"]+)\"");
    private static final Pattern MEDIA_SEQUENCE_PATTERN = Pattern.compile("#EXT-X-MEDIA-SEQUENCE:(\\d+)");
    private static final Pattern MAP_URI_PATTERN = Pattern.compile("#EXT-X-MAP:URI=\"([^\"]+)\"");
    private static final Pattern KEY_METHOD_PATTERN = Pattern.compile("METHOD=([^,\\s]+)");
    private static final Pattern KEY_URI_PATTERN = Pattern.compile("URI=\"([^\"]+)\"");
    private static final Pattern KEY_IV_PATTERN = Pattern.compile("IV=([^,\\s]+)");

    public static class KeyInfo {
        public final String method;
        public final String uri;
        public final String ivHex;

        public KeyInfo(String method, String uri, String ivHex) {
            this.method = method != null ? method : "NONE";
            this.uri = uri;
            this.ivHex = ivHex;
        }

        public boolean isAes128() {
            return "AES-128".equalsIgnoreCase(method);
        }

        public boolean isDrm() {
            return "SAMPLE-AES".equalsIgnoreCase(method) || "SAMPLE-AES-CTR".equalsIgnoreCase(method)
                    || (uri != null && uri.contains("urn:uuid:"));
        }
    }

    public static class SegmentInfo {
        public final String url;
        public final double durationSec;
        public final KeyInfo keyInfo;
        public final long sequenceNumber;
        public final String initSegmentUrl;

        public SegmentInfo(String url, double durationSec, KeyInfo keyInfo, long sequenceNumber, String initSegmentUrl) {
            this.url = url;
            this.durationSec = durationSec;
            this.keyInfo = keyInfo;
            this.sequenceNumber = sequenceNumber;
            this.initSegmentUrl = initSegmentUrl;
        }
    }

    private HlsParser() {}

    /**
     * Parses a master playlist to extract quality variants.
     */
    public static List<MediaItem.HlsVariant> parseMasterPlaylist(String baseUrl, String playlistText) {
        List<MediaItem.HlsVariant> variants = new ArrayList<>();
        if (playlistText == null || playlistText.isEmpty()) return variants;

        try (BufferedReader reader = new BufferedReader(new StringReader(playlistText))) {
            String line;
            int lastBandwidth = 0;
            int lastWidth = 0;
            int lastHeight = 0;
            String lastCodecs = "";
            boolean expectVariantUri = false;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    expectVariantUri = true;
                    Matcher bMatch = BANDWIDTH_PATTERN.matcher(line);
                    if (bMatch.find()) {
                        try { lastBandwidth = Integer.parseInt(bMatch.group(1)); } catch (Exception ignored) {}
                    }
                    Matcher rMatch = RESOLUTION_PATTERN.matcher(line);
                    if (rMatch.find()) {
                        try {
                            lastWidth = Integer.parseInt(rMatch.group(1));
                            lastHeight = Integer.parseInt(rMatch.group(2));
                        } catch (Exception ignored) {}
                    }
                    Matcher cMatch = CODECS_PATTERN.matcher(line);
                    if (cMatch.find()) {
                        lastCodecs = cMatch.group(1);
                    }
                } else if (!line.startsWith("#") && !line.isEmpty()) {
                    if (expectVariantUri) {
                        String resolvedUrl = resolveUrl(baseUrl, line);
                        variants.add(new MediaItem.HlsVariant(resolvedUrl, lastBandwidth, lastWidth, lastHeight, lastCodecs));
                        lastBandwidth = 0;
                        lastWidth = 0;
                        lastHeight = 0;
                        lastCodecs = "";
                        expectVariantUri = false;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing master playlist", e);
        }

        return variants;
    }

    /**
     * Parses a media playlist to extract chunk segments, sequence numbers, and encryption keys.
     */
    public static List<SegmentInfo> parseMediaPlaylist(String baseUrl, String playlistText) {
        List<SegmentInfo> segments = new ArrayList<>();
        if (playlistText == null || playlistText.isEmpty()) return segments;

        try (BufferedReader reader = new BufferedReader(new StringReader(playlistText))) {
            String line;
            double currentDuration = 0.0;
            KeyInfo currentKey = null;
            long mediaSequence = 0;
            long segmentCounter = 0;
            String currentInitSegment = null;
            boolean expectSegmentUri = false;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    Matcher seqMatch = MEDIA_SEQUENCE_PATTERN.matcher(line);
                    if (seqMatch.find()) {
                        try {
                            mediaSequence = Long.parseLong(seqMatch.group(1));
                        } catch (Exception ignored) {}
                    }
                } else if (line.startsWith("#EXT-X-MAP:")) {
                    Matcher mapMatch = MAP_URI_PATTERN.matcher(line);
                    if (mapMatch.find()) {
                        currentInitSegment = resolveUrl(baseUrl, mapMatch.group(1));
                    }
                } else if (line.startsWith("#EXT-X-KEY:")) {
                    String method = "NONE";
                    String keyUri = null;
                    String iv = null;

                    Matcher mMatch = KEY_METHOD_PATTERN.matcher(line);
                    if (mMatch.find()) method = mMatch.group(1);

                    Matcher uMatch = KEY_URI_PATTERN.matcher(line);
                    if (uMatch.find()) keyUri = resolveUrl(baseUrl, uMatch.group(1));

                    Matcher iMatch = KEY_IV_PATTERN.matcher(line);
                    if (iMatch.find()) iv = iMatch.group(1);

                    currentKey = new KeyInfo(method, keyUri, iv);
                } else if (line.startsWith("#EXTINF:")) {
                    expectSegmentUri = true;
                    int commaIdx = line.indexOf(',');
                    String durStr = (commaIdx != -1) ? line.substring(8, commaIdx) : line.substring(8);
                    try {
                        currentDuration = Double.parseDouble(durStr.trim());
                    } catch (Exception ignored) {
                        currentDuration = 0.0;
                    }
                } else if (!line.startsWith("#") && !line.isEmpty()) {
                    if (expectSegmentUri) {
                        String resolvedUrl = resolveUrl(baseUrl, line);
                        long seq = mediaSequence + segmentCounter;
                        segments.add(new SegmentInfo(resolvedUrl, currentDuration, currentKey, seq, currentInitSegment));
                        segmentCounter++;
                        currentDuration = 0.0;
                        expectSegmentUri = false;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing media playlist", e);
        }

        return segments;
    }

    public static String resolveUrl(String baseUrl, String path) {
        if (path == null || path.isEmpty()) return "";
        try {
            if (path.startsWith("//")) {
                // Protocol-relative
                URL base = new URL(baseUrl);
                return base.getProtocol() + ":" + path;
            }
            if (path.startsWith("http://") || path.startsWith("https://")) {
                return path;
            }
            URL base = new URL(baseUrl);
            return new URL(base, path).toString();
        } catch (Exception e) {
            return path;
        }
    }
}
