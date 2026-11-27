package local.muse.newx

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import local.muse.newx.NewXConstants.COMPATIBILITY_NEW_X_1243
import local.muse.newx.NewXConstants.EXTENSION_DEX
import local.muse.newx.utils.extendWithBundled
import java.util.LinkedHashMap

/*
 * Standalone NewX copy tools: the same two post-menu options Piko's in-tree build
 * registers ("NewX: Copy post text" / "NewX: Copy media URL"), rebuilt as a
 * self-contained add-on bundle that is applied alongside Piko's own .mpp.
 *
 * Why this file looks different from the in-tree version:
 *  - Morphe loads every patch bundle in its own classloader, so nothing here can
 *    dependsOn a Piko patch, register into Piko's PostOptionContributionIndex, or
 *    call Piko's extension classes. The contribution index below is this bundle's
 *    own, and the finalize injections (PostOptionsInjection.kt) are a port of
 *    Piko's newXPostOptionsPatch.finalize with the contribution list limited to
 *    the two copy tools.
 *  - There are no in-app settings toggles: Morphe's patch on/off is the toggle,
 *    and the extension handlers are always enabled.
 *  - Each patch carries the same finalize block. Morphe runs every execute
 *    before any finalize, so both contributions are registered by the time the
 *    first finalize runs; it consumes the snapshot and injects once, and the
 *    second finalize sees an empty index and does nothing. Enabling only one
 *    of the two patches still injects exactly that one option.
 */

internal data class PostOptionContribution(
    val handlerDescriptor: String,
    val actionName: String,
    val iconResourceName: String,
    val order: Int,
)

internal object PostOptionContributionIndex {
    private val contributions = LinkedHashMap<String, PostOptionContribution>()

    @Synchronized
    fun register(contribution: PostOptionContribution) {
        val existing = contributions[contribution.handlerDescriptor]
        check(existing == null || existing == contribution) {
            "Conflicting NewX post-option contribution: ${contribution.handlerDescriptor}"
        }
        contributions[contribution.handlerDescriptor] = contribution
    }

    @Synchronized
    fun takeSnapshot(): List<PostOptionContribution> {
        val snapshot = contributions.values.sortedBy(PostOptionContribution::order)
        contributions.clear()
        return snapshot
    }
}

private const val COPY_POST_TEXT_HANDLER_DESCRIPTOR =
    "Llocal/muse/newx/extension/misc/CopyPostTextPostOptionHandler;"
private const val COPY_MEDIA_URL_HANDLER_DESCRIPTOR =
    "Llocal/muse/newx/extension/misc/CopyMediaUrlPostOptionHandler;"

internal const val COPY_POST_TEXT_ACTION = "ConversationControlEdu"
internal const val COPY_MEDIA_URL_ACTION = "ViewDebugDialog"

context(BytecodePatchContext)
private fun finalizePostOptions() {
    val contributions = PostOptionContributionIndex.takeSnapshot()
    if (contributions.isEmpty()) return
    injectPostOptions(contributions)
}

/**
 * One-shot guards for the v0.2.0 feature hooks. Each belongs to exactly one
 * patch, but the guard keeps the injection idempotent if Morphe ever runs a
 * finalize twice.
 */
internal object FeatureInjectionState {
    private var copyTextFeaturesClaimed = false
    private var mediaFeaturesClaimed = false

    @Synchronized
    fun claimCopyTextFeatures(): Boolean {
        if (copyTextFeaturesClaimed) return false
        copyTextFeaturesClaimed = true
        return true
    }

    @Synchronized
    fun claimMediaFeatures(): Boolean {
        if (mediaFeaturesClaimed) return false
        mediaFeaturesClaimed = true
        return true
    }
}

/** Header copy button + its post-registry feeder + displayed-translation capture (Copy post text patch). */
context(BytecodePatchContext)
private fun finalizeCopyTextFeatures() {
    if (!FeatureInjectionState.claimCopyTextFeatures()) return
    injectPostRegistry()
    injectHeaderCopy()
    injectDisplayedTranslation()
    injectFocalInlineCopy()
}

/** Media long-press direct-URL copy (Copy media URL patch). */
context(BytecodePatchContext)
private fun finalizeMediaFeatures(longPressActionCode: Int) {
    if (!FeatureInjectionState.claimMediaFeatures()) return
    rewriteLongPressAction(longPressActionCode)
    injectMediaLongPress()
}

/**
 * Maps the mediaLongPressAction option value to the extension constant.
 *
 * Morphe Manager 2.6.0 passes the option's TITLE (the values map's value),
 * not its key, when the user picks an entry in the UI — on v0.2.3 every UI
 * selection therefore failed with "Invalid mediaLongPressAction value".
 * Accept both layers: the keys (copy/download/choose, case-insensitive,
 * trimmed — the CLI form) and the exact titles defined below
 * (リンクをコピー / ダウンロード / 毎回選ぶ), plus the コピー alias for
 * copy. Backward compatibility (v0.2.6): the retired third mode's key
 * `both` and its titles コピー＋ダウンロード / コピー+ダウンロード are
 * accepted as aliases for choose (code 2) — `both` used to run copy AND
 * download together; it now means "ask every time", like choose.
 */
private fun longPressActionCode(optionValue: String?): Int =
    when (val normalized = optionValue?.trim()?.lowercase()) {
        null, "", "copy", "リンクをコピー", "コピー" -> 0
        "download", "ダウンロード" -> 1
        "choose", "毎回選ぶ", "both", "コピー＋ダウンロード", "コピー+ダウンロード" -> 2
        else -> throw PatchException(
            "Invalid mediaLongPressAction value: \"$optionValue\" (normalized: \"$normalized\"). " +
                "Allowed values are the keys copy, download, choose (legacy: both), or their titles " +
                "リンクをコピー, ダウンロード, 毎回選ぶ (legacy: コピー＋ダウンロード).",
        )
    }

@Suppress("unused")
val newXCopyPostTextPatch =
    bytecodePatch(
        name = "NewX: Copy post text",
        description = "Copies a post with one tap on the copy icon at the post's top-right " +
            "(the slot the app reserves for Ask Grok; when Grok would actually be shown, the " +
            "slot stays Grok), on the copy icon just left of the share icon in the opened " +
            "(focal) post's bottom action bar, or via the post-menu option: an author " +
            "line, the displayed text (the translation when one is shown, otherwise the post " +
            "text or long note text) and one direct media URL per line.",
        default = true,
    ) {
        compatibleWith(COMPATIBILITY_NEW_X_1243)
        extendWithBundled(EXTENSION_DEX)

        execute {
            PostOptionContributionIndex.register(
                PostOptionContribution(
                    handlerDescriptor = COPY_POST_TEXT_HANDLER_DESCRIPTOR,
                    actionName = COPY_POST_TEXT_ACTION,
                    iconResourceName = "ic_vector_copy_stroke",
                    order = 285,
                ),
            )
        }

        finalize {
            finalizePostOptions()
            finalizeCopyTextFeatures()
        }
    }

@Suppress("unused")
val newXCopyMediaUrlPatch =
    bytecodePatch(
        name = "NewX: Copy media URL",
        description = "Copies direct media URLs by long-pressing a post's image or video " +
            "(or the post-menu option): original images, and the highest-bitrate mp4 " +
            "variant for videos and GIFs.",
        default = true,
    ) {
        compatibleWith(COMPATIBILITY_NEW_X_1243)
        extendWithBundled(EXTENSION_DEX)

        val mediaLongPressAction by stringOption(
            key = "mediaLongPressAction",
            default = "copy",
            values = mapOf(
                "copy" to "リンクをコピー",
                "download" to "ダウンロード",
                "choose" to "毎回選ぶ",
            ),
            title = "メディア長押しの動作",
            description = "ポストの画像・動画を長押ししたときの動作です。" +
                "copy=直リンクをコピー、download=DownloadManagerでDownloadsに保存、" +
                "choose=毎回「リンクをコピー／ダウンロード」の選択ダイアログを表示。" +
                "変更するには再パッチが必要です。" +
                "（⋯メニューの「メディアURLをコピー」は常にコピーです）",
            required = true,
        )

        execute {
            PostOptionContributionIndex.register(
                PostOptionContribution(
                    handlerDescriptor = COPY_MEDIA_URL_HANDLER_DESCRIPTOR,
                    actionName = COPY_MEDIA_URL_ACTION,
                    iconResourceName = "ic_vector_link",
                    order = 290,
                ),
            )
        }

        finalize {
            finalizePostOptions()
            finalizeMediaFeatures(longPressActionCode(mediaLongPressAction))
        }
    }

@Suppress("unused")
val newXVideoDuringCallsPatch =
    bytecodePatch(
        name = "NewX: Allow video playback during calls",
        description = "Lets videos keep playing while another app (e.g. a call) is using " +
            "audio: NewX's VideoAudioFocus manager pauses the player when its audio-focus " +
            "request is denied; this patch neutralizes only that denial branch. The focus " +
            "request itself still runs and the call's audio is never taken over.",
        default = true,
    ) {
        compatibleWith(COMPATIBILITY_NEW_X_1243)

        execute {
            injectVideoCallGate()
        }
    }
