package local.muse.newx.extension.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reflection and toString utilities for the standalone NewX copy tools.
 *
 * Ported from Piko's NewX extension (app.morphe.extension.newx.utils.NewXUtils),
 * reduced to what the copy handlers use: presenter field traversal, the
 * rawSource* identity resolvers (which read the timeline post's toString), the
 * two string predicates MediaUrlExtractor needs, and a toast helper. Piko's
 * activity tracking and Application lifecycle registration are not ported: the
 * handlers take their Context from the presenter, so no init hook is required.
 */
public final class NewXUtils {
    // Presenter shapes are fixed per class; cache the accessible field walk so
    // share/options lookups pay reflection once instead of per invocation.
    private static final ConcurrentHashMap<Class<?>, Field[]> PRESENTER_FIELDS =
            new ConcurrentHashMap<>();

    private NewXUtils() {
    }

    public static PresenterData findPresenterData(Object presenter, String valueTypeName)
            throws IllegalAccessException {
        if (presenter == null) return new PresenterData(null, null);

        Context context = null;
        Object value = null;
        for (Field field : presenterFields(presenter.getClass())) {
            boolean isContext = Context.class.isAssignableFrom(field.getType());
            boolean isValue = (valueTypeName != null && valueTypeName.equals(field.getType().getName()))
                    || ("com.x.models.timelines.items.UrtTimelinePost".equals(valueTypeName)
                    && field.getType().getName().startsWith("com.x.models.timelines.items."));
            if (!isContext && !isValue) continue;

            Object fieldValue = field.get(presenter);
            if (context == null && isContext && fieldValue instanceof Context) {
                context = (Context) fieldValue;
            }
            if (value == null && isValue && fieldValue != null && !isContext) value = fieldValue;
        }
        return new PresenterData(context, value);
    }

    private static Field[] presenterFields(Class<?> presenterClass) {
        Field[] cached = PRESENTER_FIELDS.get(presenterClass);
        if (cached != null) return cached;
        List<Field> fields = new ArrayList<>();
        for (Class<?> type = presenterClass; type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                fields.add(field);
            }
        }
        Field[] resolved = fields.toArray(new Field[0]);
        Field[] raced = PRESENTER_FIELDS.putIfAbsent(presenterClass, resolved);
        return raced != null ? raced : resolved;
    }

    public static final class PresenterData {
        private final Context context;
        private final Object value;

        private PresenterData(Context context, Object value) {
            this.context = context;
            this.value = value;
        }

        public Context getContext() {
            return context;
        }

        public Object getValue() {
            return value;
        }
    }

    /**
     * The current Application as a Context, via ActivityThread reflection. The
     * header copy button and the media long-press handler have no presenter to
     * take a Context from (unlike the post-menu handlers); clipboard writes and
     * toasts work fine with the application context.
     */
    public static Context applicationContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method currentApplication =
                    activityThread.getMethod("currentApplication");
            Object application = currentApplication.invoke(null);
            return application instanceof Context ? (Context) application : null;
        } catch (Exception exception) {
            return null;
        }
    }

    public static boolean isHttpUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /** Case-insensitive ASCII substring search without allocating. Needle must be lowercase. */
    public static boolean containsIgnoreCaseAscii(String text, String lowerNeedle) {
        int limit = text.length() - lowerNeedle.length();
        outer:
        for (int index = 0; index <= limit; index++) {
            for (int needleIndex = 0; needleIndex < lowerNeedle.length(); needleIndex++) {
                char candidate = text.charAt(index + needleIndex);
                if (candidate >= 'A' && candidate <= 'Z') candidate += 'a' - 'A';
                if (candidate != lowerNeedle.charAt(needleIndex)) continue outer;
            }
            return true;
        }
        return false;
    }

    /** Shows a short toast with the presenter's context, on the main thread. */
    public static void showToastShort(Context context, String text) {
        if (context == null || text == null) return;
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
            } else {
                new Handler(Looper.getMainLooper()).post(
                        () -> Toast.makeText(context, text, Toast.LENGTH_SHORT).show());
            }
        } catch (Exception ignored) {
        }
    }

    private static final Pattern STATUS_URL_PATTERN =
            Pattern.compile("https?://(?:[a-zA-Z0-9-]+\\.)*(?:twitter|x|fxtwitter|vxtwitter|fixupx|twx)\\.com/([A-Za-z0-9_]+)/status/\\d+");

    /**
     * Resolves the source post id before filename sanitization. Used where the identifier is
     * substituted into a user-configured template instead of a fixed filename.
     */
    public static String rawSourcePostId(Object post) {
        return rawSourcePostId(postText(post));
    }

    /**
     * Text form of {@link #rawSourcePostId(Object)}, for callers that already hold the toString.
     * A post's toString can be large and is expensive to rebuild per field lookup.
     */
    public static String rawSourcePostId(String postText) {
        String originalPostText = originalRepostedPostText(postText);
        if (originalPostText != null) {
            String originalPostId = ToStringParser.fieldValue(originalPostText, "id");
            if (originalPostId != null) return originalPostId;
        }

        String sourcePostId = sourceMediaField(postText, "sourcePostIdentifier");
        if (sourcePostId != null) return sourcePostId;

        String canonicalText = canonicalPostText(postText);
        String postId = ToStringParser.fieldValue(canonicalText, "id");
        if (postId != null) return postId;

        return ToStringParser.fieldValue(postText, "id");
    }

    /**
     * Resolves the author handle before filename sanitization. Suspended-account posts expose a
     * placeholder author, so the media mention/expanded-URL fallbacks are tried first.
     */
    public static String rawSourceScreenName(Object post) {
        return rawSourceScreenName(postText(post));
    }

    /** Text form of {@link #rawSourceScreenName(Object)}. */
    public static String rawSourceScreenName(String postText) {
        String originalPostText = originalRepostedPostText(postText);
        if (originalPostText != null) {
            String originalScreenName = rawAuthorField(originalPostText, "screenName");
            if (originalScreenName != null) return originalScreenName;
        }

        String sourceMediaScreenName = sourceMediaScreenName(postText);
        if (sourceMediaScreenName != null) return sourceMediaScreenName;

        String canonicalScreenName = rawAuthorField(canonicalPostText(postText), "screenName");
        if (canonicalScreenName != null) return canonicalScreenName;

        return rawAuthorField(postText, "screenName");
    }

    /**
     * Resolves the author display name shown on the post. The author model is polymorphic: only
     * the user-backed variants ({@code MinimalUser}, {@code ProfileUser}) carry a name.
     */
    public static String rawSourceDisplayName(Object post) {
        return rawSourceDisplayName(postText(post));
    }

    /** Text form of {@link #rawSourceDisplayName(Object)}. */
    public static String rawSourceDisplayName(String postText) {
        String originalPostText = originalRepostedPostText(postText);
        if (originalPostText != null) {
            String originalName = rawAuthorField(originalPostText, "name");
            if (originalName != null) return originalName;
        }

        // When the handle comes from the media's source post, the name must come from that same
        // account too; falling through would pair the source handle with the quoting author's name.
        if (sourceMediaScreenName(postText) != null) {
            return sourceMediaField(postText, "sourceUserDisplayName");
        }

        String canonicalName = rawAuthorField(canonicalPostText(postText), "name");
        if (canonicalName != null) return canonicalName;

        return rawAuthorField(postText, "name");
    }

    private static String rawAuthorField(String text, String fieldName) {
        if (text == null) return null;

        String author = ToStringParser.fieldValue(text, "author");
        return author == null ? null : ToStringParser.fieldValue(author, fieldName);
    }

    private static String postText(Object post) {
        return post == null ? null : post.toString();
    }

    private static String canonicalPostText(String postText) {
        if (postText == null) return null;

        String canonicalPost = ToStringParser.fieldValue(postText, "canonicalPost");
        return canonicalPost != null ? canonicalPost : postText;
    }

    private static String originalRepostedPostText(String postText) {
        if (postText == null) return null;

        String repostedPost = ToStringParser.fieldValue(postText, "rePostedPost");
        if (repostedPost == null) return null;

        String canonicalPost = ToStringParser.fieldValue(repostedPost, "canonicalPost");
        return canonicalPost != null ? canonicalPost : repostedPost;
    }

    /**
     * Author handle of the media's source post, resolved only when the media carries source
     * lineage. Shared by the handle and display-name resolvers so both describe the same account.
     */
    private static String sourceMediaScreenName(String postText) {
        if (sourceMediaField(postText, "sourcePostIdentifier") == null) return null;

        String mentionScreenName = firstMentionScreenName(postText);
        return mentionScreenName != null ? mentionScreenName : mediaExpandedUrlScreenName(postText);
    }

    private static String firstMentionScreenName(String text) {
        String entityList = ToStringParser.fieldValue(text, "entityList");
        if (entityList == null) return null;

        String mentions = ToStringParser.fieldValue(entityList, "mentions");
        return mentions == null ? null : ToStringParser.fieldValue(mentions, "screenName");
    }

    private static String mediaExpandedUrlScreenName(String text) {
        if (text == null) return null;

        String entityList = ToStringParser.fieldValue(text, "entityList");
        String searchScope = entityList != null ? entityList : text;
        String expandedUrl = ToStringParser.fieldValue(searchScope, "expandedUrl");
        String screenName = screenNameFromUrl(expandedUrl);
        return screenName != null ? screenName : screenNameFromUrl(searchScope);
    }

    private static String screenNameFromUrl(String url) {
        if (url == null) return null;

        Matcher matcher = STATUS_URL_PATTERN.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String sourceMediaField(String text, String fieldName) {
        if (text == null) return null;

        String sourceInfo = ToStringParser.fieldValue(text, "sourceInfo");
        return sourceInfo == null ? null : ToStringParser.fieldValue(sourceInfo, fieldName);
    }
}
