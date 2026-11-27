package local.muse.newx.extension.misc;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import kotlin.Unit;
import kotlin.jvm.functions.Function0;

import com.x.icons.b;
import com.x.urt.items.post.e6;
import com.x.urt.items.post.y1;

import local.muse.newx.extension.utils.NewXUtils;
import local.muse.newx.extension.utils.ToStringParser;

/**
 * The one-tap copy button at the top-right of a post, in the header slot the
 * app reserves for "Ask Grok" (NewX post layout
 * {@code Lcom/x/sensitivemedia/api/c;->b}, 12.43.0-prod.01).
 *
 * The patch rewires that slot's three pieces:
 *  - {@link #forceShow} runs right after the slot's visibility gate
 *    (AvailablePost.showAskGrokButton) is read: the slot is always shown, and
 *    the original gate value is remembered for the rest of the segment.
 *  - {@link #selectIcon} runs right after the Grok icon is loaded: when Grok
 *    is not actually being shown, the copy icon (icons/a.E2,
 *    ic_vector_copy_stroke) replaces it.
 *  - {@link #wrapClick} runs right after the slot's click lambda is built:
 *    when Grok is not actually being shown, taps run {@link CopyAction}
 *    instead of sending the Ask Grok event.
 *
 * Grok coexistence: the gate value is the app's own final decision (feature
 * flags AND per-post eligibility), and composition of one post's header
 * segment is contiguous on the UI thread, so the value {@link #forceShow}
 * stores is always the one the following {@link #selectIcon} /
 * {@link #wrapClick} calls of the same segment read. When the gate is true,
 * all three hooks pass the native values through untouched and the Ask Grok
 * button behaves exactly as stock.
 *
 * The header composable only receives the post's UI state (e6), not a post
 * model, so a tap resolves the post through the registry below: a hook at
 * the head of the inline action-bar state builder (which runs for every
 * composed post in the same patch) remembers each post's contextual post
 * by id via {@link #rememberFromPresenter}, and the tap looks it up by
 * the id read from the UI state. The payload itself is built by
 * {@link CopyPostTextPostOptionHandler}, exactly like the menu option,
 * displayed-translation stash included.
 *
 * Typing discipline (v0.2.5): the three hook methods are declared with the
 * EXACT dex types of the values they intercept. The patch writes each
 * hook's result back into the intercepted value's own register with a
 * move-result, and ART's verifier types that register by the hook's
 * declared return type — an Object return poisons the register for every
 * later typed use (v0.2.4 crashed with a VerifyError: the icon register,
 * fed by an {@code icons/a.I4} sget of type {@code com.x.icons.b}, was
 * Object-typed by the time it reached the Icon composable). Hence:
 * {@link #selectIcon} is {@code (Lcom/x/icons/b;)Lcom/x/icons/b;} (the
 * sget's field type and the Icon composable's parameter type), and
 * {@link #wrapClick} takes the exact lambda class constructed at the site
 * ({@code com.x.urt.items.post.y1}) and returns {@code Function0} — the
 * interface y1 implements and the exact type the very next typed use
 * check-casts the register to before handing it to the clickable. The app
 * types are named through compile-only stubs (see stubs/); they are never
 * packaged into the extension dex.
 */
public final class HeaderCopyButton {
    private static final String TAG = "MuseNewXCopy";
    private static final String ICONS_CLASS = "com.x.icons.a";
    private static final String COPY_ICON_FIELD = "E2";
    private static final int MAX_POSTS = 128;

    /** The Ask Grok gate of the header segment currently being composed. */
    private static volatile boolean grokSlotShown;

    private static volatile Object cachedCopyIcon;

    /** Recent contextual posts by post id, filled by the state-builder hook. */
    private static final Map<String, Object> RECENT_POSTS =
            Collections.synchronizedMap(
                    new LinkedHashMap<String, Object>(16, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                            return size() > MAX_POSTS;
                        }
                    });

    /** Post id per contextual-post instance, so the filter parses each post once. */
    private static final Map<Object, String> POST_IDS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private HeaderCopyButton() {
    }

    /** After the gate read: always show the slot; remember the real gate. */
    public static boolean forceShow(boolean grokShown) {
        grokSlotShown = grokShown;
        return true;
    }

    /** After the Grok icon load: swap in the copy icon unless Grok is shown. */
    public static b selectIcon(b grokIcon) {
        if (grokSlotShown) return grokIcon;
        b copyIcon = copyIcon();
        return copyIcon != null ? copyIcon : grokIcon;
    }

    /** After the click lambda is built: swap in the copy action unless Grok is shown. */
    public static Function0 wrapClick(y1 nativeClick, e6 availablePost) {
        if (grokSlotShown) return nativeClick;
        return new CopyAction(availablePost);
    }

    /**
     * Registry feeder, hooked at the head of the inline action-bar state
     * builder ({@code Lcom/x/inlineactionbar/f1;->c}), which is composed for
     * every post: reads the presenter's contextual post through the
     * patch-injected bridge and remembers it for later header taps. Until
     * v0.2.5 the registry was fed as a side effect of the inline copy
     * entry's list filter; the inline entry is gone in v0.2.6, so the
     * feeder is now this dedicated hook on the same presenter object.
     */
    public static void rememberFromPresenter(Object presenter) {
        try {
            rememberPost(getPresenterPost(presenter));
        } catch (RuntimeException ignored) {
            // Registry upkeep is best-effort: a miss only costs the header tap.
        }
    }

    /**
     * Remembers a post's contextual post for later header taps. The parse of
     * the post id is paid once per post instance.
     */
    public static void rememberPost(Object contextualPost) {
        if (contextualPost == null) return;
        try {
            String postId = POST_IDS.get(contextualPost);
            if (postId == null) {
                postId = NewXUtils.rawSourcePostId(contextualPost);
                if (postId == null || postId.isEmpty()) return;
                POST_IDS.put(contextualPost, postId);
            }
            RECENT_POSTS.put(postId, contextualPost);
        } catch (RuntimeException ignored) {
            // Registry upkeep is best-effort: a miss only costs the header tap.
        }
    }

    // Patch-injected: reads the presenter's contextual-post field.
    private static Object getPresenterPost(Object presenter) {
        throw new IllegalStateException(
                "NewX header registry bridge was not patched: getPresenterPost");
    }

    /** The copy icon (icons/a.E2, ic_vector_copy_stroke), read reflectively. */
    private static b copyIcon() {
        Object icon = cachedCopyIcon;
        if (icon == null) {
            try {
                Field field = Class.forName(ICONS_CLASS).getDeclaredField(COPY_ICON_FIELD);
                field.setAccessible(true);
                icon = field.get(null);
                if (icon != null) cachedCopyIcon = icon;
            } catch (Exception exception) {
                Log.e(TAG, "Failed to resolve the header copy icon", exception);
                return null;
            }
        }
        // E2 is declared Lcom/x/icons/b; in the app (pinned at patch time);
        // the instanceof keeps a drifted field from ever breaking the cast.
        return icon instanceof b ? (b) icon : null;
    }

    /** The replacement click handler: copies the post this header belongs to. */
    private static final class CopyAction implements Function0 {
        private final Object availablePost;

        private CopyAction(Object availablePost) {
            this.availablePost = availablePost;
        }

        @Override
        public Object invoke() {
            try {
                Context context = NewXUtils.applicationContext();
                String postId = readPostId(availablePost);
                Object post = postId == null ? null : RECENT_POSTS.get(postId);
                if (context == null || post == null) {
                    NewXUtils.showToastShort(context, "コピーできる内容がありません");
                } else {
                    CopyPostTextPostOptionHandler.copyPostText(context, post);
                }
            } catch (RuntimeException exception) {
                Log.e(TAG, "Failed to handle the header copy action", exception);
            }
            return Unit.INSTANCE;
        }
    }

    /**
     * The post id out of the header's UI state: e6 field {@code c} is the post
     * id holder (Lcom/x/models/h6;), whose {@code b} field is the id string —
     * the same reads {@link DisplayedTranslation} performs on the holder.
     */
    private static String readPostId(Object availablePost) {
        if (availablePost == null) return null;
        Object holder = fieldValue(availablePost, "c");
        if (holder == null) return null;
        if (holder instanceof String text) return text;
        Object fieldB = fieldValue(holder, "b");
        if (fieldB instanceof String text) return text;
        String single = null;
        for (Class<?> type = holder.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                    continue;
                }
                Object value = readField(field, holder);
                if (!(value instanceof String text)) continue;
                if (single != null) return ToStringParser.fieldValue(holder.toString(), "id");
                single = text;
            }
        }
        if (single != null) return single;
        return ToStringParser.fieldValue(holder.toString(), "id");
    }

    private static Object fieldValue(Object owner, String name) {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getName().equals(name)) return readField(field, owner);
            }
        }
        return null;
    }

    private static Object readField(Field field, Object owner) {
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (Exception exception) {
            return null;
        }
    }
}
