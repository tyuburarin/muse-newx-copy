package local.muse.newx.extension.misc;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;

import java.util.List;

import local.muse.newx.extension.utils.NewXUtils;

/**
 * Handles the "Copy media URL" post-menu option in NewX.
 *
 * Copies the direct twimg URLs of the post's media, one per line: original
 * images, and the highest-bitrate mp4 variant for videos and GIFs. This is the
 * NewX counterpart of the legacy Muse "Copy media URL" patch.
 *
 * Standalone port of Piko's in-tree handler; see CopyPostTextPostOptionHandler
 * for the list of differences from the in-tree version.
 */
public final class CopyMediaUrlPostOptionHandler {
    private static final String TAG = "MuseNewXCopy";
    private static final String OPTION_NAME = NewXPostOptionActions.COPY_MEDIA_URL_ACTION;
    private static final String URT_POST_CLASS = "com.x.models.timelines.items.UrtTimelinePost";
    private static final String OPTION_LABEL = "メディアURLをコピー";

    private CopyMediaUrlPostOptionHandler() {
    }

    public static List<?> addOption(List<?> groups) {
        return NewXPostOptions.addOption(groups, OPTION_NAME, true);
    }

    public static String labelFor(Object action, Object originalLabel) {
        if (isCopyMediaUrlAction(action)) return OPTION_LABEL;
        return originalLabel instanceof String ? (String) originalLabel : null;
    }

    public static boolean usesIcon(Object action) {
        return isCopyMediaUrlAction(action);
    }

    public static boolean handleOptionAction(Object presenter, Object action) {
        if (!isCopyMediaUrlAction(action)) return false;

        Context context = null;
        try {
            NewXUtils.PresenterData presenterData = NewXUtils.findPresenterData(presenter, URT_POST_CLASS);
            context = presenterData.getContext();
            Object post = presenterData.getValue();
            if (context == null || post == null) {
                NewXUtils.showToastShort(context, "メディアURLがありません");
                return true;
            }

            List<String> urls = mediaUrls(post);
            if (urls.isEmpty()) {
                NewXUtils.showToastShort(context, "メディアURLがありません");
                return true;
            }

            ClipboardManager clipboard =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return true;
            clipboard.setPrimaryClip(ClipData.newPlainText("メディアURL", String.join("\n", urls)));
            NewXUtils.showToastShort(context, "メディアURLをコピーしました");
            return true;
        } catch (Exception exception) {
            Log.e(TAG, "Failed to handle copy media URL option", exception);
            NewXUtils.showToastShort(context, "メディアURLがありません");
            return true;
        }
    }

    private static List<String> mediaUrls(Object timelinePost) {
        Object canonicalPost = PostModelAccess.readCanonicalPost(timelinePost);
        List<String> urls = MediaUrlExtractor.extractUrls(PostModelAccess.readPostMedia(canonicalPost));
        if (!urls.isEmpty()) return urls;
        Object reposted = PostModelAccess.readRepostedCanonicalPost(timelinePost);
        return MediaUrlExtractor.extractUrls(PostModelAccess.readPostMedia(reposted));
    }

    private static boolean isCopyMediaUrlAction(Object action) {
        return NewXPostOptions.isAction(action, OPTION_NAME);
    }
}
