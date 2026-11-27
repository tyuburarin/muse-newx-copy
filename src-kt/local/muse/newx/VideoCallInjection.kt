package local.muse.newx

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.string
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * "Allow video playback during calls" for NewX (12.43), the port of the old-X
 * (12.19.1) Muse patch. Full investigation: the old Twitter AV player is
 * gone; NewX plays through media3 ExoPlayer with X's own audio-focus lease
 * system (Lcom/x/audiofocus/) and a per-session VideoAudioFocus coordinator
 * (Lcom/x/media/playback/audiofocus/).
 *
 * The gate (instruction-verified in classes6.dex):
 *  - audiofocus/d.F (a Player.Listener callback) computes "audible"
 *    (playing + audio track + volume up) and calls the manager
 *    audiofocus/j.b(playerListener, audible) on change.
 *  - j.b, when audible, requests focus through article/y.M ->
 *    audiofocus/d.b(Function1) -> AudioManager.requestAudioFocus. The
 *    request really runs and its result is real.
 *  - If the result is false (e.g. a call holds focus), j.b logs
 *    "onAudible: focus denied in state=..., pausing the video" and calls
 *    Player.pause() — the video never plays. That denial branch is the
 *    exact analogue of the old u.N gate: the same lease denial is non-fatal
 *    elsewhere (a muted timeline autoplay never requests focus at all).
 *
 * The patch neutralizes ONLY that branch: the move-result of the focus
 * request in j.b is replaced with a constant "granted" for this consumer's
 * branch decision. The request itself still runs, the lease's internal
 * bookkeeping keeps the real result, no other consumer (audio player,
 * Spaces, Grok voice) is touched, and nothing steals the call's focus —
 * a denied request means X holds nothing, exactly like the timeline's
 * muted playback. It does not hide any error message; it removes the
 * pause that the denial triggered.
 */

private const val VIDEO_FOCUS_SCOPE = "Lcom/x/media/playback/audiofocus/"
private const val EXPECTED_MANAGER_DESCRIPTOR = "Lcom/x/media/playback/audiofocus/j;"
private const val PLAYER_LISTENER_DESCRIPTOR = "Lcom/x/media/playback/audiofocus/d;"
private const val LEASE_HOLDER_DESCRIPTOR = "Lcom/x/article/y;"
private const val MEDIA3_PLAYER_DESCRIPTOR = "Landroidx/media3/common/k1;"

context(BytecodePatchContext)
internal fun injectVideoCallGate() {
    val match = Fingerprint(
        definingClass = VIDEO_FOCUS_SCOPE,
        returnType = "V",
        parameters = listOf(PLAYER_LISTENER_DESCRIPTOR, "Z"),
        filters = listOf(
            string("onAudible: focus denied in state="),
            string(", pausing the video"),
            string("onAudible: focus granted"),
        ),
    ).matchAll().singleOrNull()
        ?: throw PatchException(
            "Expected one NewX VideoAudioFocus audible handler " +
                "($PLAYER_LISTENER_DESCRIPTOR, Z)V in $VIDEO_FOCUS_SCOPE",
        )

    val managerClass = match.originalClassDef.type
    if (managerClass != EXPECTED_MANAGER_DESCRIPTOR) {
        throw PatchException(
            "NewX VideoAudioFocus manager changed: expected $EXPECTED_MANAGER_DESCRIPTOR, " +
                "resolved $managerClass",
        )
    }

    val method = match.method
    val instructions = method.instructions

    // The focus request: invoke-virtual to the lease holder's acquire method
    // (article/y.M(Function1)Z -> audiofocus/d.b -> requestAudioFocus).
    val requestIndexes = instructions.mapIndexedNotNull { index, instruction ->
        val reference = instruction.getReference<MethodReference>() ?: return@mapIndexedNotNull null
        index.takeIf {
            instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                reference.definingClass == LEASE_HOLDER_DESCRIPTOR &&
                reference.name == "M" &&
                reference.returnType == "Z" &&
                reference.parameterTypes.size == 1
        }
    }
    if (requestIndexes.size != 1) {
        throw PatchException(
            "Expected one NewX audio-focus lease request in $method, found ${requestIndexes.size}",
        )
    }
    val requestIndex = requestIndexes.single()

    // Its move-result, shortly after the call.
    val moveResultIndex = (requestIndex + 1..minOf(requestIndex + 3, instructions.size - 1))
        .firstOrNull { instructions[it].opcode == Opcode.MOVE_RESULT }
        ?: throw PatchException("NewX audio-focus request in $method has no move-result")
    val resultRegister = (instructions[moveResultIndex] as? OneRegisterInstruction)?.registerA
        ?: throw PatchException("NewX audio-focus move-result in $method has no register")

    // The denial branch: an if-nez on the result register shortly after
    // (granted -> skip the denial block that pauses the player).
    val branchIndex = (moveResultIndex + 1..minOf(moveResultIndex + 5, instructions.size - 1))
        .firstOrNull { index ->
            val instruction = instructions[index]
            instruction.opcode == Opcode.IF_NEZ &&
                (instruction as? OneRegisterInstruction)?.registerA == resultRegister
        }
        ?: throw PatchException(
            "NewX audio-focus denial branch (if-nez on v$resultRegister) not found in $method",
        )

    // The denial block must be the one that pauses the player.
    val hasPlayerPause = instructions.any { instruction ->
        val reference = instruction.getReference<MethodReference>()
        instruction.opcode == Opcode.INVOKE_INTERFACE &&
            reference?.definingClass == MEDIA3_PLAYER_DESCRIPTOR &&
            reference.name == "pause" &&
            reference.parameterTypes.isEmpty() &&
            reference.returnType == "V"
    }
    if (!hasPlayerPause) {
        throw PatchException("NewX VideoAudioFocus handler in $method never pauses the player")
    }

    // Neutralize only the branch decision at this consumer: the request has
    // already run above; replacing its move-result with a constant makes the
    // if-nez take the granted path. The lease, the AudioManager result and
    // every other consumer keep the real outcome.
    val constInstruction = if (resultRegister < 16) {
        "const/4 v$resultRegister, 0x1"
    } else {
        "const/16 v$resultRegister, 0x1"
    }
    method.replaceInstruction(moveResultIndex, constInstruction)
}
