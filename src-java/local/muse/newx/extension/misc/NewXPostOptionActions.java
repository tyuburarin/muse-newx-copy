package local.muse.newx.extension.misc;

/**
 * Existing PostActionType values used as carriers for custom NewX post-menu options.
 *
 * These are enum names from the target app, not user-facing labels. They must match the
 * action names declared by the corresponding post-option patch contribution. Update
 * compatibility when a target version removes or renames one of these carriers.
 *
 * Ported from Piko's NewX extension (app.morphe.extension.newx.misc).
 */
public final class NewXPostOptionActions {
    public static final String BROWSE_OBJECT_ACTION = "None";
    public static final String FILTERED_REPLIES_ACTION = "ServerFeedbackAction";
    public static final String CRASH_APP_ACTION = "AppealWarning";
    public static final String TRANSLATE_ACTION = "ProtectedRetweetEdu";
    public static final String COPY_POST_TEXT_ACTION = "ConversationControlEdu";
    public static final String COPY_MEDIA_URL_ACTION = "ViewDebugDialog";

    private NewXPostOptionActions() {
    }
}
