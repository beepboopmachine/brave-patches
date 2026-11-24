package dev.brave.extension.media;

import android.util.Log;
import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HlsParser {
    private static final String TAG = "Brave_HlsParser";

    // Match BANDWIDTH= after start, ':', or ',' (still excludes AVERAGE-BANDWIDTH=)
    private static final Pattern BANDWIDTH_PATTERN = Pattern.compile("(?:^|[:,])BANDWIDTH=(\\d+)");
    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)");
    private static final Pattern CODECS_PATTERN = Pattern.compile("CODECS=\"([^\"]+)\"");
    private static final Pattern AUDIO_GROUP_REF_PATTERN = Pattern.compile("(?:^|[:,])AUDIO=\"([^\"]+)\"");
    private static final Pattern MEDIA_TYPE_PATTERN = Pattern.compile("(?:^|[:,])TYPE=([^,\\s]+)");
    private static final Pattern MEDIA_GROUP_ID_PATTERN = Pattern.compile("(?:^|[:,])GROUP-ID=\"([^\"]+)\"");
    private static final Pattern MEDIA_URI_PATTERN = Pattern.compile("(?:^|[:,])URI=\"([^\"]+)\"");
    private static final Pattern MEDIA_DEFAULT_PATTERN = Pattern.compile("(?:^|[:,])DEFAULT=(YES|NO)");
    private static final Pattern MEDIA_AUTOSELECT_PATTERN = Pattern.compile("(?:^|[:,])AUTOSELECT=(YES|NO)");

    private static final Pattern MEDIA_SEQUENCE_PATTERN = Pattern.compile("#EXT-X-MEDIA-SEQUENCE:(\\d+)");
    private static final Pattern MAP_URI_PATTERN = Pattern.compile("URI=\"([^\"]+)\"");
    private static final Pattern MAP_BYTERANGE_PATTERN = Pattern.compile("BYTERANGE=\"(\\d+)(?:@(\\d+))?\"");
    private static final Pattern BYTERANGE_PATTERN = Pattern.compile("#EXT-X-BYTERANGE:(\\d+)(?:@(\\d+))?");
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
        public final long initSegmentByteRangeLength;
        public final long initSegmentByteRangeOffset;
        public final KeyInfo initSegmentKeyInfo;
        public final long byteRangeLength;
        public final long byteRangeOffset;
        public final boolean discontinuity;

        public SegmentInfo(String url, double durationSec, KeyInfo keyInfo, long sequenceNumber, String initSegmentUrl) {
            this(url, durationSec, keyInfo, sequenceNumber, initSegmentUrl, -1, -1, null, -1, -1, false);
        }

        public SegmentInfo(
                String url,
                double durationSec,
                KeyInfo keyInfo,
                long sequenceNumber,
                String initSegmentUrl,
                long initSegmentByteRangeLength,
                long initSegmentByteRangeOffset,
                KeyInfo initSegmentKeyInfo,
                long byteRangeLength,
                long byteRangeOffset,
                boolean discontinuity
        ) {
            this.url = url;
            this.durationSec = durationSec;
            this.keyInfo = keyInfo;
            this.sequenceNumber = sequenceNumber;
            this.initSegmentUrl = initSegmentUrl;
            this.initSegmentByteRangeLength = initSegmentByteRangeLength;
            this.initSegmentByteRangeOffset = initSegmentByteRangeOffset;
            this.initSegmentKeyInfo = initSegmentKeyInfo;
            this.byteRangeLength = byteRangeLength;
            this.byteRangeOffset = byteRangeOffset;
            this.discontinuity = discontinuity;
        }
    }

    private HlsParser() {}

    public static boolean isIFramesOnlyPlaylist(String playlistText) {
        if (playlistText == null || playlistText.isEmpty()) return false;
        return playlistText.contains("#EXT-X-I-FRAMES-ONLY");
    }

    /**
     * Parses a master playlist to extract quality variants and associated EXT-X-MEDIA audio renditions.
     */
    public static List<MediaItem.HlsVariant> parseMasterPlaylist(String baseUrl, String playlistText) {
        List<MediaItem.HlsVariant> variants = new ArrayList<>();
        if (playlistText == null || playlistText.isEmpty()) return variants;

        // First pass: collect EXT-X-MEDIA TYPE=AUDIO renditions by GROUP-ID
        // Priority: DEFAULT=YES (3) > AUTOSELECT=YES (2) > first available (1)
        Map<String, String> audioGroupUri = new HashMap<>();
        Map<String, Integer> audioGroupPriority = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new StringReader(playlistText))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#EXT-X-MEDIA:")) {
                    Matcher typeM = MEDIA_TYPE_PATTERN.matcher(line);
                    if (!typeM.find() || !"AUDIO".equalsIgnoreCase(typeM.group(1))) continue;
                    Matcher groupM = MEDIA_GROUP_ID_PATTERN.matcher(line);
                    Matcher uriM = MEDIA_URI_PATTERN.matcher(line);
                    if (!groupM.find() || !uriM.find()) continue;
                    String groupId = groupM.group(1);
                    String resolvedAudio = resolveUrl(baseUrl, uriM.group(1));
                    int prio = 1;
                    Matcher autoM = MEDIA_AUTOSELECT_PATTERN.matcher(line);
                    if (autoM.find() && "YES".equalsIgnoreCase(autoM.group(1))) prio = 2;
                    Matcher defM = MEDIA_DEFAULT_PATTERN.matcher(line);
                    if (defM.find() && "YES".equalsIgnoreCase(defM.group(1))) prio = 3;

                    Integer prevPrio = audioGroupPriority.get(groupId);
                    if (prevPrio == null || prio > prevPrio) {
                        audioGroupPriority.put(groupId, prio);
                        audioGroupUri.put(groupId, resolvedAudio);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error scanning EXT-X-MEDIA audio groups", e);
        }

        // Second pass: parse EXT-X-STREAM-INF variants
        try (BufferedReader reader = new BufferedReader(new StringReader(playlistText))) {
            String line;
            int lastBandwidth = 0;
            int lastWidth = 0;
            int lastHeight = 0;
            String lastCodecs = "";
            String lastAudioGroup = "";
            boolean expectVariantUri = false;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    expectVariantUri = true;
                    lastBandwidth = 0;
                    lastWidth = 0;
                    lastHeight = 0;
                    lastCodecs = "";
                    lastAudioGroup = "";

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
                    Matcher aMatch = AUDIO_GROUP_REF_PATTERN.matcher(line);
                    if (aMatch.find()) {
                        lastAudioGroup = aMatch.group(1);
                    }
                } else if (!line.startsWith("#") && !line.isEmpty()) {
                    if (expectVariantUri) {
                        String resolvedUrl = resolveUrl(baseUrl, line);
                        String resolvedAudioUrl = (!lastAudioGroup.isEmpty() && audioGroupUri.containsKey(lastAudioGroup))
                                ? audioGroupUri.get(lastAudioGroup)
                                : "";
                        variants.add(new MediaItem.HlsVariant(
                                resolvedUrl, lastBandwidth, lastWidth, lastHeight, lastCodecs, resolvedAudioUrl
                        ));
                        lastBandwidth = 0;
                        lastWidth = 0;
                        lastHeight = 0;
                        lastCodecs = "";
                        lastAudioGroup = "";
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
     * Parses a media playlist to extract segments, byte ranges, sequence numbers,
     * EXT-X-MAP init sections (with optional byte ranges & encryption keys),
     * EXT-X-DISCONTINUITY markers, and encryption keys.
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
            long currentInitBrLen = -1;
            long currentInitBrOff = -1;
            KeyInfo currentInitKey = null;

            long nextByteRangeLen = -1;
            long nextByteRangeOff = -1;
            long lastByteRangeEnd = 0;
            String lastByteRangeUri = null;
            boolean pendingDiscontinuity = false;
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
                } else if (line.startsWith("#EXT-X-DISCONTINUITY")) {
                    pendingDiscontinuity = true;
                } else if (line.startsWith("#EXT-X-MAP:")) {
                    Matcher mapMatch = MAP_URI_PATTERN.matcher(line);
                    if (mapMatch.find()) {
                        currentInitSegment = resolveUrl(baseUrl, mapMatch.group(1));
                        currentInitKey = currentKey;
                        currentInitBrLen = -1;
                        currentInitBrOff = -1;
                        Matcher mapBr = MAP_BYTERANGE_PATTERN.matcher(line);
                        if (mapBr.find()) {
                            try {
                                currentInitBrLen = Long.parseLong(mapBr.group(1));
                                currentInitBrOff = (mapBr.group(2) != null) ? Long.parseLong(mapBr.group(2)) : 0L;
                            } catch (Exception ignored) {}
                        }
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
                } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                    Matcher brMatch = BYTERANGE_PATTERN.matcher(line);
                    if (brMatch.find()) {
                        try {
                            nextByteRangeLen = Long.parseLong(brMatch.group(1));
                            nextByteRangeOff = (brMatch.group(2) != null)
                                    ? Long.parseLong(brMatch.group(2))
                                    : -1L; // Resolved against previous segment's end when URI line is read
                        } catch (Exception ignored) {
                            nextByteRangeLen = -1;
                            nextByteRangeOff = -1;
                        }
                    }
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
                        long segBrLen = nextByteRangeLen;
                        long segBrOff = nextByteRangeOff;
                        if (segBrLen > 0) {
                            if (segBrOff < 0) {
                                segBrOff = resolvedUrl.equals(lastByteRangeUri) ? lastByteRangeEnd : 0L;
                            }
                            lastByteRangeEnd = segBrOff + segBrLen;
                            lastByteRangeUri = resolvedUrl;
                        }

                        segments.add(new SegmentInfo(
                                resolvedUrl,
                                currentDuration,
                                currentKey,
                                seq,
                                currentInitSegment,
                                currentInitBrLen,
                                currentInitBrOff,
                                currentInitKey,
                                segBrLen,
                                segBrOff,
                                pendingDiscontinuity
                        ));
                        segmentCounter++;
                        currentDuration = 0.0;
                        nextByteRangeLen = -1;
                        nextByteRangeOff = -1;
                        pendingDiscontinuity = false;
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
