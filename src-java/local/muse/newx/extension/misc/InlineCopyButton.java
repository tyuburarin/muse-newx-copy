package local.muse.newx.extension.misc;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import com.x.inlineactionbar.r1;

import local.muse.newx.extension.utils.NewXUtils;

/**
 * The one-tap copy entry in the NewX inline action bar (the row of icons under
 * a post), scoped to the FOCAL post of the post-detail screen since v0.2.7.
 * Standalone port of Piko's InlineDownloadButton stack, reduced to the copy
 * payload:
 *
 *  - Until v0.2.5 the entry was appended to every post's action list by a
 *    state-builder filter; v0.2.6 removed it everywhere (the header button
 *    covers the timeline), which left the opened (focal) post with no one-tap
 *    copy at all: the focal post renders through the OTHER post layout
 *    ({@code Lcom/x/urt/items/post/a2;->a}, driven by the FocalPostState),
 *    whose header has no Ask Grok slot to repurpose.
 *  - The focal layout therefore gets the entry back at the bottom instead:
 *    a hook in {@code a2.a} runs the bar state through {@link #wrapFocalBar}
 *    right before the bar composable consumes it, and the wrap returns a
 *    copy of the state whose entry list carries the copy entry inserted
 *    just before the native share entry. The state the app built (and every
 *    other surface's bar) is never touched: the wrap is focal-only by
 *    construction, because {@code a2.a} composes nothing but focal posts.
 *  - The entry is a singleton carrying the TwitterShare action type, the only
 *    carrier with a renderer icon branch and click wiring. It is identified
 *    everywhere by identity ({@code ==}), never by action type, so the native
 *    share entry is never mistaken for it.
 *  - The renderer hook calls {@link #markIconSize}: for the copy entry the
 *    icon size is negated — the sign is the one bit the icon lambda can see —
 *    and the lambda's patched branch calls {@link #selectIcon} to swap in the
 *    copy icon when the captured size is negative. The layout lambda forces
 *    the entry's kind to IconOnly through {@link #isCopyAction}.
 *  - The event-handler head hook calls {@link #handleEvent}: only an event
 *    carrying the singleton is consumed, and it copies the post text through
 *    {@link CopyPostTextPostOptionHandler} (which prefers the displayed
 *    translation, like the menu option).
 *
 * {@link #createCopyAction()}, {@link #getPresenterPost(Object)} and
 * {@link #toImmutableEntries(Object)} are stubs whose bodies the patch
 * injects: the entry constructor, the presenter's contextual-post field and
 * the app's own list-to-immutable conversion are obfuscated app pieces the
 * extension cannot name at compile time. {@link #wrapFocalBar} is declared
 * with the bar state's exact dex type (compile-only stub, see stubs/) for
 * the same verifier reason as HeaderCopyButton's hooks: its result is
 * written back into the intercepted register with a move-result, and ART
 * types that register by the declared return type.
 */
public final class InlineCopyButton {
    private static final String TAG = "MuseNewXCopy";
    private static final String ACTION_TYPE_CLASS = "com.x.models.x5";
    private static final String SHARE_ACTION_NAME = "TwitterShare";
    private static final String IMMUTABLE_LIST_CLASS = "kotlinx.collections.immutable.b";
    private static final String POPUP_STATE_CLASS = "com.x.inlineactionbar.q1";

    // The entry holds only stable, non-post-specific values, so one instance is
    // reused across every post; its identity is the classification key.
    private static volatile Object cachedCopyAction;
    private static volatile Object cachedShareActionType;

    private InlineCopyButton() {
    }

    /**
     * Focal-only entry injection, hooked in the focal post layout
     * ({@code Lcom/x/urt/items/post/a2;->a}) immediately before the inline
     * action-bar composable consumes the state the presenter built: returns
     * a copy of the state whose entries carry the copy entry just before
     * the native share entry (appended at the end when the bar has no share
     * entry). The app's own state instance is never mutated, and any
     * failure returns the incoming state untouched — the worst case is a
     * stock focal bar, never a crash.
     */
    public static r1 wrapFocalBar(r1 state) {
        if (state == null) return null;
        try {
            Object entriesValue = fieldValue(state, "a");
            if (!(entriesValue instanceof List)) return state;
            List<?> entries = (List<?>) entriesValue;
            Object copyAction = getOrCreateCopyAction();
            if (copyAction == null) return state;
            int shareIndex = -1;
            for (int index = 0; index < entries.size(); index++) {
                Object entry = entries.get(index);
                if (entry == copyAction) return state; // already wrapped
                if (shareIndex < 0 && isShareEntry(entry)) shareIndex = index;
            }
            List<Object> merged = new ArrayList<>(entries.size() + 1);
            merged.addAll(entries);
            if (shareIndex >= 0) {
                merged.add(shareIndex, copyAction);
            } else {
                merged.add(copyAction);
            }
            Object immutableEntries = toImmutableEntries(merged);
            if (!(immutableEntries instanceof List)) return state;
            Constructor<?> constructor = state.getClass().getDeclaredConstructor(
                    Class.forName(IMMUTABLE_LIST_CLASS),
                    String.class,
                    Class.forName(POPUP_STATE_CLASS),
                    boolean.class,
                    String.class,
                    kotlin.jvm.functions.Function1.class);
            constructor.setAccessible(true);
            Object wrapped = constructor.newInstance(
                    immutableEntries,
                    fieldValue(state, "b"),
                    fieldValue(state, "c"),
                    fieldValue(state, "d"),
                    fieldValue(state, "e"),
                    fieldValue(state, "f"));
            return (r1) wrapped;
        } catch (Throwable throwable) {
            Log.e(TAG, "Failed to add the focal inline copy action", throwable);
            return state;
        }
    }

    /** True when the entry's action type is the native TwitterShare. */
    private static boolean isShareEntry(Object entry) {
        if (entry == null) return false;
        Object shareActionType = shareActionType();
        return shareActionType != null && fieldValue(entry, "a") == shareActionType;
    }

    /** The x5.TwitterShare singleton, read reflectively and cached. */
    private static Object shareActionType() {
        Object actionType = cachedShareActionType;
        if (actionType == null) {
            try {
                Field field = Class.forName(ACTION_TYPE_CLASS).getDeclaredField(SHARE_ACTION_NAME);
                field.setAccessible(true);
                actionType = field.get(null);
                if (actionType != null) cachedShareActionType = actionType;
            } catch (Exception exception) {
                Log.e(TAG, "Failed to resolve the share action type", exception);
            }
        }
        return actionType;
    }

    private static Object fieldValue(Object owner, String name) {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getName().equals(name) && !Modifier.isStatic(field.getModifiers())) {
                    try {
                        field.setAccessible(true);
                        return field.get(owner);
                    } catch (Exception exception) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Encodes the copy classification in the size captured by the share icon
     * lambda. The channel to the lambda is a single float, and Piko's download
     * button (when co-applied) already uses "negative" as its own mark, read
     * by a binary sign check. The copy mark is therefore negative with the
     * lowest mantissa bit set: Piko's plain mark is {@code -abs(size)}, whose
     * lowest mantissa bit is 0 for any ordinary icon size, while this mark's
     * is 1. The magnitude changes by at most 1 ulp (~2e-6 at 24f), so the
     * layout's {@code Math.abs} reads back the original size for all
     * practical purposes.
     */
    public static float markIconSize(Object action, float iconSize) {
        if (!isCopyAction(action)) return iconSize;
        int bits = Float.floatToRawIntBits(Math.abs(iconSize));
        bits |= 0x80000000; // negative
        bits |= 0x00000001; // copy-mark bit
        return Float.intBitsToFloat(bits);
    }

    /**
     * Picks the copy icon only when the captured size carries the copy mark
     * (negative with the lowest mantissa bit set). Anything else returns the
     * incoming icon unchanged — crucially, when Piko's selector ran first and
     * already swapped in the download icon for its own entry, that result is
     * preserved. This bundle's selector hook is inserted after Piko's when
     * both are present, so this is the last word for every entry.
     */
    public static Object selectIcon(Object nativeIcon, float markedIconSize, Object copyIcon) {
        return isCopyMarked(markedIconSize) ? copyIcon : nativeIcon;
    }

    /** True only for the copy mark set by {@link #markIconSize}. */
    static boolean isCopyMarked(float markedIconSize) {
        int bits = Float.floatToRawIntBits(markedIconSize);
        return bits < 0 && (bits & 0x00000001) != 0;
    }

    /** Identity classification of the injected copy entry. */
    public static boolean isCopyAction(Object candidate) {
        return candidate != null && candidate == cachedCopyAction;
    }

    /**
     * Head of the inline event handler. Returns true (consuming the event)
     * only when the event carries the copy entry; anything else falls through
     * to the native handler untouched. A long-press is treated as a tap.
     */
    public static boolean handleEvent(Object presenter, Object event, boolean longPress) {
        Object action = findActionEntry(event);
        if (!isCopyAction(action)) return false;

        try {
            Context context = NewXUtils.applicationContext();
            Object post = getPresenterPost(presenter);
            if (context == null || post == null) {
                NewXUtils.showToastShort(context, "コピーできる内容がありません");
                return true;
            }
            CopyPostTextPostOptionHandler.copyPostText(context, post);
            return true;
        } catch (RuntimeException exception) {
            Log.e(TAG, "Failed to handle inline copy action", exception);
            return true;
        }
    }

    private static Object findActionEntry(Object event) {
        if (event == null) return null;
        try {
            for (Class<?> type = event.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    field.setAccessible(true);
                    Object value = field.get(event);
                    if (isCopyAction(value)) return value;
                }
            }
        } catch (IllegalAccessException | RuntimeException exception) {
            Log.e(TAG, "Failed to read the NewX inline action event", exception);
        }
        return null;
    }

    private static Object getOrCreateCopyAction() {
        Object action = cachedCopyAction;
        if (action != null) return action;
        synchronized (InlineCopyButton.class) {
            action = cachedCopyAction;
            if (action == null) {
                action = createCopyAction();
                registerCopyAction(action);
            }
        }
        return action;
    }

    static void registerCopyAction(Object action) {
        if (action == null) throw unpatchedBridge("createCopyAction returned null");
        cachedCopyAction = action;
    }

    // Patch-injected: builds the InlineActionEntry(TwitterShare, null, true).
    private static Object createCopyAction() {
        throw unpatchedBridge("createCopyAction");
    }

    // Patch-injected: reads the presenter's contextual-post field.
    private static Object getPresenterPost(Object presenter) {
        throw unpatchedBridge("getPresenterPost");
    }

    // Patch-injected: the app's own Iterable -> immutable-entries conversion
    // (the same conversion the inline state builder applies), so the wrapped
    // focal state carries exactly the list type the bar state declares.
    private static Object toImmutableEntries(Object entries) {
        throw unpatchedBridge("toImmutableEntries");
    }

    private static IllegalStateException unpatchedBridge(String bridgeName) {
        return new IllegalStateException("NewX inline copy bridge was not patched: " + bridgeName);
    }
}
