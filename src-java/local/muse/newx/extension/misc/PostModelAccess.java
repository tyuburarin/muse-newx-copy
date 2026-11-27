package local.muse.newx.extension.misc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import local.muse.newx.extension.utils.ToStringParser;

/**
 * Reflective reads of the NewX post models, replacing the patch-time bridges the
 * in-tree Piko build generates.
 *
 * The in-tree bridges resolve, at patch time, the chain
 * timeline post -&gt; postResult -&gt; contextual post -&gt; canonical post
 * (and the reposted post's canonical post) plus the canonical post's media list,
 * the timeline post's text getter and the note (long post) text. This standalone
 * bundle cannot run those bridges (they are generated against Piko's own model
 * resolution patches, in another bundle), so the same chain is walked here by
 * type instead of by resolved field reference:
 *
 *  - the canonical post class is the one verified for 12.43.0-prod.01,
 *    {@code com.x.models.v0};
 *  - the contextual post is the timeline post's field value that itself holds a
 *    direct field of the canonical type (its postResult);
 *  - the canonical post is that direct field; the reposted canonical post is
 *    reached through any other contextual field whose value holds one;
 *  - text, media and note reads prefer the field names verified for this
 *    release (CanonicalPost b = text, d = media, l = notePost; note body
 *    d = text) and fall back to type scans and toString parsing.
 *
 * Every read is best-effort: any mismatch returns null and the handlers fall
 * back to less complete payloads, mirroring the in-tree handlers' treatment of
 * a throwing bridge.
 */
public final class PostModelAccess {
    private static final String CANONICAL_POST_CLASS = "com.x.models.v0";
    private static final String NOTES_PACKAGE_PREFIX = "com.x.models.notes.";

    private PostModelAccess() {
    }

    /** Timeline post -&gt; contextual post -&gt; canonical post, or null. */
    public static Object readCanonicalPost(Object timelinePost) {
        Object contextual = contextualPost(timelinePost);
        return contextual == null ? null : directCanonicalPost(contextual);
    }

    /** Timeline post -&gt; contextual post -&gt; reposted post -&gt; its canonical post, or null. */
    public static Object readRepostedCanonicalPost(Object timelinePost) {
        Object contextual = contextualPost(timelinePost);
        if (contextual == null) return null;
        Object canonical = directCanonicalPost(contextual);
        for (Object value : instanceFieldValues(contextual)) {
            if (value == null || value == canonical) continue;
            Object repostedCanonical = directCanonicalPost(value);
            if (repostedCanonical != null && repostedCanonical != canonical) {
                return repostedCanonical;
            }
        }
        return null;
    }

    /** The timeline post's text, through its generated getText() accessor. */
    public static String readPostText(Object timelinePost) {
        if (timelinePost == null) return null;
        try {
            Method getter = timelinePost.getClass().getMethod("getText");
            Object value = getter.invoke(timelinePost);
            return value instanceof String text ? text : null;
        } catch (Exception exception) {
            return null;
        }
    }

    /** The canonical post's text (CanonicalPost field b), or null. */
    public static String readCanonicalText(Object canonicalPost) {
        if (canonicalPost == null) return null;
        Object value = fieldValue(canonicalPost, "b");
        if (value instanceof String text) return text;
        return ToStringParser.fieldValue(canonicalPost.toString(), "text");
    }

    /** The canonical post's media list (CanonicalPost field d), or null. */
    public static List<?> readPostMedia(Object canonicalPost) {
        if (canonicalPost == null) return null;
        Object value = fieldValue(canonicalPost, "d");
        if (value instanceof List<?> list) return list;
        for (Field field : instanceFields(canonicalPost.getClass())) {
            if (!List.class.isAssignableFrom(field.getType())) continue;
            Object candidate = readField(field, canonicalPost);
            if (!(candidate instanceof List<?> list) || list.isEmpty()) continue;
            Object first = list.get(0);
            if (first != null && first.toString().startsWith("MediaContent")) return list;
        }
        return null;
    }

    /**
     * The note (long post) text of a canonical post, or null when the post is not
     * a note or the chain cannot be read. Best-effort, like the in-tree bridge:
     * CanonicalPost.l is the NotePost holder; its noteTweetResult is the body,
     * whose text field carries the long text.
     */
    public static String readNoteText(Object canonicalPost) {
        if (canonicalPost == null) return null;
        try {
            Object notePost = null;
            for (Field field : instanceFields(canonicalPost.getClass())) {
                if (field.getType().getName().startsWith(NOTES_PACKAGE_PREFIX)) {
                    notePost = readField(field, canonicalPost);
                    if (notePost != null) break;
                }
            }
            if (notePost == null) return null;

            Object body = null;
            for (Field field : instanceFields(notePost.getClass())) {
                Object value = readField(field, notePost);
                if (value != null && value != notePost
                        && value.getClass().getName().startsWith(NOTES_PACKAGE_PREFIX)) {
                    body = value;
                    break;
                }
            }
            if (body == null) return null;

            Object text = fieldValue(body, "d");
            if (text instanceof String string) return string;

            String single = null;
            for (Field field : instanceFields(body.getClass())) {
                if (field.getType() != String.class) continue;
                Object value = readField(field, body);
                if (!(value instanceof String string)) continue;
                if (single != null) {
                    single = null;
                    break;
                }
                single = string;
            }
            if (single != null) return single;
            return ToStringParser.fieldValue(body.toString(), "text");
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * The timeline post's result holder: the direct field value that itself has a
     * direct field of the canonical post type. The timeline post may also be (or
     * wrap) the contextual post directly, which the first check covers.
     */
    private static Object contextualPost(Object timelinePost) {
        if (timelinePost == null) return null;
        if (directCanonicalPost(timelinePost) != null) return timelinePost;
        for (Object value : instanceFieldValues(timelinePost)) {
            if (value != null && directCanonicalPost(value) != null) return value;
        }
        return null;
    }

    /** The value of owner's direct field whose declared type is the canonical post type. */
    private static Object directCanonicalPost(Object owner) {
        for (Field field : instanceFields(owner.getClass())) {
            if (!CANONICAL_POST_CLASS.equals(field.getType().getName())) continue;
            return readField(field, owner);
        }
        return null;
    }

    private static Object fieldValue(Object owner, String name) {
        for (Field field : instanceFields(owner.getClass())) {
            if (field.getName().equals(name)) return readField(field, owner);
        }
        return null;
    }

    private static List<Object> instanceFieldValues(Object owner) {
        List<Object> values = new ArrayList<>();
        for (Field field : instanceFields(owner.getClass())) {
            values.add(readField(field, owner));
        }
        return values;
    }

    private static List<Field> instanceFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                fields.add(field);
            }
        }
        return fields;
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
