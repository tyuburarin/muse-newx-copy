package local.muse.newx.extension.misc;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import local.muse.newx.extension.utils.NewXUtils;
import local.muse.newx.extension.utils.ToStringParser;

/**
 * Extracts direct media URLs from NewX media model objects.
 *
 * Ported from Piko's NewX extension (app.morphe.extension.newx.misc); the only
 * changes are the package and the logger. The media models are read through
 * their {@code toString} representation, the same approach the inline download
 * button uses: images expose {@code imageUrl}, while videos and GIFs expose
 * {@code MediaVariant(url=..., bitRate=..., contentType=...)} entries of which
 * the highest-bitrate mp4 variant wins.
 */
public final class MediaUrlExtractor {
    private static final String TAG = "MuseNewXCopy";

    private MediaUrlExtractor() {
    }

    /**
     * One direct URL per media item, in post order. Images are returned without the
     * size query (the original image), videos and GIFs as the highest-bitrate mp4
     * variant. Items without a usable URL are skipped.
     */
    public static List<String> extractUrls(List<?> media) {
        List<String> urls = new ArrayList<>();
        if (media == null) return urls;

        for (Object item : media) {
            if (item == null) continue;
            try {
                String url = extractUrl(item);
                if (url != null && !url.isEmpty()) urls.add(url);
            } catch (RuntimeException exception) {
                Log.e(TAG, "Failed to extract NewX media URL", exception);
            }
        }
        return urls;
    }

    private static String extractUrl(Object media) {
        String value = media.toString();
        if (value == null) return null;

        if (value.startsWith("MediaContentImage(")) {
            String url = ToStringParser.fieldValue(value, "imageUrl");
            if (!NewXUtils.isHttpUrl(url)) return null;
            int query = url.indexOf('?');
            return query >= 0 ? url.substring(0, query) : url;
        }

        return bestMp4VariantUrl(value);
    }

    /** Highest-bitrate mp4 variant URL in a media model's toString, or null. */
    private static String bestMp4VariantUrl(String value) {
        String prefix = "MediaVariant(url=";
        String bestUrl = null;
        int bestBitRate = -1;
        int offset = 0;
        while (true) {
            int start = value.indexOf(prefix, offset);
            if (start < 0) break;
            start += prefix.length();
            int bitRateStart = value.indexOf(", bitRate=", start);
            if (bitRateStart < 0) break;
            int contentTypeStart = value.indexOf(", contentType=", bitRateStart);
            if (contentTypeStart < 0) break;
            int end = value.indexOf(')', contentTypeStart);
            if (end < 0) break;

            String url = value.substring(start, bitRateStart);
            String bitRateText = value.substring(bitRateStart + ", bitRate=".length(), contentTypeStart);
            String contentType = value.substring(contentTypeStart + ", contentType=".length(), end);
            offset = end + 1;

            if (!isMp4Variant(url, contentType)) continue;
            int bitRate = parseBitRate(bitRateText);
            if (bitRate > bestBitRate) {
                bestBitRate = bitRate;
                bestUrl = url;
            }
        }
        return bestUrl;
    }

    private static boolean isMp4Variant(String url, String contentType) {
        if (!NewXUtils.isHttpUrl(url)) return false;
        return (contentType != null && contentType.equalsIgnoreCase("video/mp4"))
                || NewXUtils.containsIgnoreCaseAscii(url, ".mp4");
    }

    private static int parseBitRate(String text) {
        if (text == null) return -1;
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException exception) {
            return -1;
        }
    }
}
