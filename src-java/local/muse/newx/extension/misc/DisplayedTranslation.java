package local.muse.newx.extension.misc;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import local.muse.newx.extension.utils.ToStringParser;

/**
 * Stash of the translation text each post is currently displaying, captured by the
 * patch-side hook on the scribing registry call
 * {@code Lcom/x/scribing/urt/c;->R(d; h6; l; Composer; I)V}.
 *
 * Both NewX translation presenters (timeline auto-translation and post-detail
 * on-demand translation) call that registry once per composition with the
 * TranslatedPost they are about to show, or null when the original text is shown
 * (for example after the user taps "show original"). The hook forwards the
 * (post id, translated post) pair here, so the copy handler can prefer exactly
 * the text on screen instead of re-deriving the display state from the models —
 * CanonicalPost alone cannot express it (the show/original toggle lives in
 * composition state, not in the model).
 *
 * Entries are keyed by the post id string. A null or blank translation removes
 * the entry, which makes the copy payload fall back to the original text. The
 * map is capped at the most recent {@link #MAX_ENTRIES} posts; a post's
 * translation never changes, so eviction only drops posts scrolled far away.
 */
public final class DisplayedTranslation {
    private static final int MAX_ENTRIES = 64;

    private static final Map<String, String> DISPLAYED =
            Collections.synchronizedMap(
                    new LinkedHashMap<String, String>(16, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                            return size() > MAX_ENTRIES;
                        }
                    });

    private DisplayedTranslation() {
    }

    /**
     * Records the translation currently displayed for a post. Called from the
     * patched registry method with the raw model objects (post id holder and
     * TranslatedPost, either possibly null); all reads are reflective because
     * the model classes are obfuscated app classes.
     */
    public static void track(Object postId, Object translatedPost) {
        try {
            String id = readIdString(postId);
            if (id == null || id.isEmpty()) return;
            if (translatedPost == null) {
                DISPLAYED.remove(id);
                return;
            }
            String text = readTranslatedText(translatedPost);
            if (text == null || text.trim().isEmpty()) {
                DISPLAYED.remove(id);
            } else {
                DISPLAYED.put(id, text);
            }
        } catch (RuntimeException ignored) {
            // Tracking is best-effort: a shape mismatch must never break composition.
        }
    }

    /** The displayed translation for a post id, or null when none is being shown. */
    public static String getDisplayedText(String postId) {
        if (postId == null) return null;
        return DISPLAYED.get(postId);
    }

    /**
     * The id string out of the post-id holder (Lcom/x/models/h6;): its {@code b}
     * field is the id body (verified for 12.43.0-prod.01). Falls back to any
     * single String field, then to parsing the toString form.
     */
    private static String readIdString(Object postId) {
        if (postId == null) return null;
        if (postId instanceof String text) return text;
        Object fieldB = fieldValue(postId, "b");
        if (fieldB instanceof String text) return text;
        String single = null;
        for (Class<?> type = postId.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                Object value = readField(field, postId);
                if (!(value instanceof String text)) continue;
                if (single != null) return ToStringParser.fieldValue(postId.toString(), "id");
                single = text;
            }
        }
        if (single != null) return single;
        return ToStringParser.fieldValue(postId.toString(), "id");
    }

    /** TranslatedPost field {@code a} is the full translation text. */
    private static String readTranslatedText(Object translatedPost) {
        Object fieldA = fieldValue(translatedPost, "a");
        if (fieldA instanceof String text) return text;
        return ToStringParser.fieldValue(translatedPost.toString(), "text");
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
