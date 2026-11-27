package local.muse.newx.extension.misc;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import local.muse.newx.extension.utils.NewXUtils;

/**
 * Handles the "Copy post text" post-menu option in NewX.
 *
 * Copies the same payload the legacy Muse patches produced: an author line
 * (display name, handle and post time), the post text (the long note text when
 * the post is a note), then one direct media URL per line.
 *
 * Standalone port of Piko's in-tree handler. The differences: the model reads
 * are reflective (PostModelAccess) instead of patch-time bridges, the option
 * is always enabled (Morphe's patch on/off is the toggle, so there is no
 * settings lookup), and toasts/logging use this bundle's own helpers.
 */
public final class CopyPostTextPostOptionHandler {
    private static final String TAG = "MuseNewXCopy";
    private static final String OPTION_NAME = NewXPostOptionActions.COPY_POST_TEXT_ACTION;
    private static final String URT_POST_CLASS = "com.x.models.timelines.items.UrtTimelinePost";
    private static final String OPTION_LABEL = "ツイートのコピー";

    private CopyPostTextPostOptionHandler() {
    }

    public static List<?> addOption(List<?> groups) {
        return NewXPostOptions.addOption(groups, OPTION_NAME, true);
    }

    public static String labelFor(Object action, Object originalLabel) {
        if (isCopyPostTextAction(action)) return OPTION_LABEL;
        return originalLabel instanceof String ? (String) originalLabel : null;
    }

    public static boolean usesIcon(Object action) {
        return isCopyPostTextAction(action);
    }

    public static boolean handleOptionAction(Object presenter, Object action) {
        if (!isCopyPostTextAction(action)) return false;

        Context context = null;
        try {
            NewXUtils.PresenterData presenterData = NewXUtils.findPresenterData(presenter, URT_POST_CLASS);
            context = presenterData.getContext();
            Object post = presenterData.getValue();
            if (context == null || post == null) {
                NewXUtils.showToastShort(context, "コピーできる内容がありません");
                return true;
            }
            copyPostText(context, post);
            return true;
        } catch (Exception exception) {
            Log.e(TAG, "Failed to handle copy post text option", exception);
            NewXUtils.showToastShort(context, "コピーできる内容がありません");
            return true;
        }
    }

    /**
     * Copies the payload of a post to the clipboard, with the same toasts as the
     * menu option. Entry point for the header one-tap copy button, which hands
     * over the contextual post (resolved through HeaderCopyButton's registry)
     * instead of a timeline post; {@link #buildPayload} and
     * {@link PostModelAccess} accept either shape.
     */
    public static void copyPostText(Context context, Object post) {
        String payload = post == null ? null : buildPayload(post);
        if (payload == null) {
            NewXUtils.showToastShort(context, "コピーできる内容がありません");
            return;
        }
        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("Post", payload));
        NewXUtils.showToastShort(context, "コピーしました");
    }

    private static String buildPayload(Object timelinePost) {
        StringBuilder sb = new StringBuilder();

        String postId = null;
        try {
            postId = NewXUtils.rawSourcePostId(timelinePost);
        } catch (Exception ignored) {
        }

        // Author line: name@handle M/d HH:mm (time derived from the post id, as before).
        try {
            String name = NewXUtils.rawSourceDisplayName(timelinePost);
            String handle = NewXUtils.rawSourceScreenName(timelinePost);
            if (name != null && handle != null) {
                sb.append(name).append('@').append(handle);
                if (postId != null) {
                    long epochMs = (Long.parseLong(postId.trim()) >> 22) + 1288834974657L;
                    String when = new SimpleDateFormat("M/d HH:mm", Locale.US)
                            .format(new Date(epochMs));
                    sb.append(' ').append(when);
                }
                sb.append('\n');
            }
        } catch (Exception ignored) {
        }

        // Post text: the translation currently on screen wins over everything —
        // it is the full displayed text (notes included), captured by
        // DisplayedTranslation at render time. Without a stash hit, the note
        // (long) text when present, otherwise the regular text.
        String text = null;
        Object canonicalPost = PostModelAccess.readCanonicalPost(timelinePost);
        String displayed = DisplayedTranslation.getDisplayedText(postId);
        if (displayed != null && !displayed.trim().isEmpty()) {
            text = displayed;
        } else {
            String noteText = PostModelAccess.readNoteText(canonicalPost);
            if (noteText != null && !noteText.trim().isEmpty()) {
                text = noteText;
            } else {
                text = PostModelAccess.readPostText(timelinePost);
                if (text == null) text = PostModelAccess.readCanonicalText(canonicalPost);
            }
        }
        boolean hasText = text != null && !text.trim().isEmpty();
        if (hasText) {
            sb.append(text
                    .replaceAll("pic\\.x\\.com/\\S+", "")
                    .replaceAll("https?://t\\.co/\\S+", ""));
        }

        // Media URLs, one per line.
        boolean hasMedia = false;
        for (String url : mediaUrls(timelinePost, canonicalPost)) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(url);
            hasMedia = true;
        }

        if (!hasText && !hasMedia) return null;
        return sb.toString();
    }

    private static List<String> mediaUrls(Object timelinePost, Object canonicalPost) {
        List<String> urls = MediaUrlExtractor.extractUrls(PostModelAccess.readPostMedia(canonicalPost));
        if (!urls.isEmpty()) return urls;
        Object reposted = PostModelAccess.readRepostedCanonicalPost(timelinePost);
        return MediaUrlExtractor.extractUrls(PostModelAccess.readPostMedia(reposted));
    }

    private static boolean isCopyPostTextAction(Object action) {
        return NewXPostOptions.isAction(action, OPTION_NAME);
    }
}
