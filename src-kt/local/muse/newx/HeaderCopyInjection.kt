package local.muse.newx

import app.crimera.bytecode.insertHook
import app.crimera.bytecode.methodReference
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.registersUsed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Header copy button: the one-tap copy entry moves to the top-right of the
 * post, into the header slot the app reserves for "Ask Grok"
 * (Lcom/x/sensitivemedia/api/c;->b in classes6.dex, 12.43.0-prod.01; see
 * ~/workspace/research_notes/newx-header-copy-button.md for the full layout
 * analysis and BUILD.md v0.2.4 for the coverage determination).
 *
 * The slot's segment (instruction-verified in the stock dex) is:
 *   iget-boolean v4, v1, e6;->b0        (showAskGrokButton gate)
 *   if-eqz v4, ...                      (segment skipped when false)
 *   const v4, 2131963716                (R.string.post_option_ask_grok)
 *   ... new y1(eventSink, 13)           (click lambda -> DidClickAskGrok)
 *   ... foundation clickable ...
 *   sget-object v15, icons/a;->I4       (ic_vector_grok_icon)
 *   ... compose/core/j.f (Icon) ...
 *   invoke-static share/impl/n;->j      (the ⋯ button, right after)
 *
 * Three hooks rewire it, all delegating the Grok-vs-copy decision to the
 * extension (HeaderCopyButton), which remembers the gate value the first
 * hook sees:
 *   1. after the gate read:   v4 = forceShow(v4)          (slot always shows)
 *   2. after the y1 <init>:   v5 = wrapClick(v5, v1)      (taps copy the post;
 *      inserted before the composer's updateRememberedValue store, so the
 *      remembered value on recomposition is already the wrapped one)
 *   3. after the I4 sget:     v15 = selectIcon(v15)       (copy-stroke icon)
 * When the gate is true (Grok flags on AND the post is Grok-eligible), the
 * extension passes all three values through untouched: the Ask Grok button
 * keeps working exactly as stock while this patch is on.
 *
 * Typing discipline (v0.2.5): every hook's extension method is declared
 * with the EXACT dex type of the value it intercepts, because the hook's
 * move-result writes back into the intercepted value's own register and
 * ART types that register by the declared return type. v0.2.4 declared
 * Object in/out and crashed on-device with a VerifyError at the Icon
 * composable (register v15: Object where com.x.icons.b was expected) —
 * the local Morphe CLI apply never runs ART verification, so readback
 * alone did not catch it. The exact types, all pinned below against the
 * stock dex:
 *   forceShow:  (Z)Z — the iget-boolean's own type.
 *   wrapClick:  (Lcom/x/urt/items/post/y1;Lcom/x/urt/items/post/e6;)
 *               Lkotlin/jvm/functions/Function0; — the parameter is the
 *               exact lambda class the <init> just constructed; the return
 *               is Function0, the interface y1 implements and the exact
 *               type the register is check-cast to two instructions later
 *               before the clickable consumes it. (Returning y1 itself is
 *               impossible: the replacement object is not a y1.)
 *   selectIcon: (Lcom/x/icons/b;)Lcom/x/icons/b; — the I4/E2 field type
 *               and the Icon composable's (compose/core/j.f) first
 *               parameter type.
 * The verification suite additionally asserts these descriptor equalities
 * against the patched output (see BUILD.md v0.2.5).
 *
 * The method is resolved semantically (string resource const + I4 sget +
 * post-options call in one e6-led method under the sensitivemedia/api
 * scope), then pinned to the verified owner class; every anchor inside the
 * segment is asserted, so layout drift fails the patch loudly.
 *
 * Coverage note: a second full post layout exists (Lcom/x/urt/items/post/
 * a2;->a) with no Grok slot, and static analysis could not prove which
 * layout renders the main timeline, so v0.2.4/v0.2.5 kept the inline
 * (bottom bar) copy entry as a safety net. On-device evidence settled it
 * (v0.2.5 screenshot, 2026-11-21): the header button DOES appear on home
 * timeline posts, i.e. this layout renders the timeline. The inline entry
 * was therefore removed in v0.2.6 at the user's direction; the registry
 * this button depends on is now fed by PostRegistryInjection. Posts
 * rendered only by the other layout keep the ⋯-menu copy option.
 */

private const val HEADER_EXT = "Llocal/muse/newx/extension/misc/HeaderCopyButton;"
private const val EXPECTED_HEADER_OWNER = "Lcom/x/sensitivemedia/api/c;"
private const val HEADER_SCOPE = "Lcom/x/sensitivemedia/api/"
private const val AVAILABLE_POST_DESCRIPTOR = "Lcom/x/urt/items/post/e6;"
private const val POST_OPTIONS_DESCRIPTOR = "Lcom/x/share/impl/n;"
private const val ICONS_DESCRIPTOR = "Lcom/x/icons/a;"
private const val ICON_TYPE_DESCRIPTOR = "Lcom/x/icons/b;"
private const val GROK_ICON_FIELD_NAME = "I4"
private const val COPY_ICON_FIELD_NAME = "E2"
private const val GATE_FIELD_NAME = "b0"
private const val CLICK_LAMBDA_DESCRIPTOR = "Lcom/x/urt/items/post/y1;"
private const val FUNCTION0_DESCRIPTOR = "Lkotlin/jvm/functions/Function0;"
private const val FUNCTION1_DESCRIPTOR = "Lkotlin/jvm/functions/Function1;"
private const val ASK_GROK_RES_ID = 2131963716 // 0x7f132f44, R.string.post_option_ask_grok
private const val ASK_GROK_CLICK_DISCRIMINATOR = 13

/** Semantic shape of the post-header method carrying the Ask Grok slot. */
private fun Method.isAskGrokHeaderMethod(): Boolean {
    if (returnType != "V") return false
    if (parameterTypes.firstOrNull()?.toString() != AVAILABLE_POST_DESCRIPTOR) return false
    val methodInstructions = implementation?.instructions?.toList() ?: return false
    var hasStringResource = false
    var hasGrokIcon = false
    var hasPostOptionsCall = false
    for (instruction in methodInstructions) {
        if ((instruction as? NarrowLiteralInstruction)?.narrowLiteral == ASK_GROK_RES_ID) {
            hasStringResource = true
        }
        val field = instruction.getReference<FieldReference>()
        if (
            instruction.opcode == Opcode.SGET_OBJECT &&
                field?.definingClass == ICONS_DESCRIPTOR &&
                field.name == GROK_ICON_FIELD_NAME
        ) {
            hasGrokIcon = true
        }
        val call = instruction.getReference<MethodReference>()
        if (call?.definingClass == POST_OPTIONS_DESCRIPTOR && call.name == "j") {
            hasPostOptionsCall = true
        }
    }
    return hasStringResource && hasGrokIcon && hasPostOptionsCall
}

/** True when [initIndex]'s lambda construction carries the Ask Grok discriminator (13). */
private fun List<Instruction>.isAskGrokClickInit(initIndex: Int): Boolean {
    val init = this[initIndex]
    val registers = init.registersUsed
    if (registers.size != 3) return false
    val instanceRegister = registers[0]
    val discriminatorRegister = registers[2]
    var hasNewInstance = false
    var hasDiscriminator = false
    for (index in maxOf(0, initIndex - 4) until initIndex) {
        val instruction = this[index]
        if (
            instruction.opcode == Opcode.NEW_INSTANCE &&
                (instruction as? OneRegisterInstruction)?.registerA == instanceRegister &&
                instruction.getReference<com.android.tools.smali.dexlib2.iface.reference.TypeReference>()
                    ?.type == CLICK_LAMBDA_DESCRIPTOR
        ) {
            hasNewInstance = true
        }
        if (
            (instruction as? NarrowLiteralInstruction)?.narrowLiteral == ASK_GROK_CLICK_DISCRIMINATOR &&
                (instruction as? OneRegisterInstruction)?.registerA == discriminatorRegister
        ) {
            hasDiscriminator = true
        }
    }
    return hasNewInstance && hasDiscriminator
}

context(BytecodePatchContext)
internal fun injectHeaderCopy() {
    // The extension reads the copy icon out of icons/a.E2 reflectively at
    // runtime; pin the field's existence and shape here so drift fails loudly.
    val iconsClass = classDefByOrNull(ICONS_DESCRIPTOR)
        ?: throw PatchException("NewX icon holder was not found: $ICONS_DESCRIPTOR")
    val copyIconField = iconsClass.fields.singleOrNull { field ->
        field.name == COPY_ICON_FIELD_NAME &&
            AccessFlags.STATIC.isSet(field.accessFlags) &&
            field.type == ICON_TYPE_DESCRIPTOR
    } ?: throw PatchException(
        "NewX copy icon field $COPY_ICON_FIELD_NAME ($ICON_TYPE_DESCRIPTOR) was not found " +
            "in $ICONS_DESCRIPTOR",
    )
    // The Grok icon field must carry the same type: selectIcon is declared
    // (ICON_TYPE)ICON_TYPE, so the sget it intercepts and the value it can
    // return (the E2 copy icon above) must both be exactly that type.
    val grokIconField = iconsClass.fields.singleOrNull { field ->
        field.name == GROK_ICON_FIELD_NAME &&
            AccessFlags.STATIC.isSet(field.accessFlags) &&
            field.type == ICON_TYPE_DESCRIPTOR
    } ?: throw PatchException(
        "NewX Grok icon field $GROK_ICON_FIELD_NAME ($ICON_TYPE_DESCRIPTOR) was not found " +
            "in $ICONS_DESCRIPTOR",
    )

    // wrapClick returns Function0: the click lambda class must actually
    // implement it (it is also the type the register is check-cast to
    // before the clickable consumes it), or the typed hook would poison
    // the register in the opposite direction.
    val clickLambdaClass = classDefByOrNull(CLICK_LAMBDA_DESCRIPTOR)
        ?: throw PatchException("NewX header click lambda was not found: $CLICK_LAMBDA_DESCRIPTOR")
    if (clickLambdaClass.interfaces.none { it.toString() == FUNCTION0_DESCRIPTOR }) {
        throw PatchException(
            "NewX header click lambda $CLICK_LAMBDA_DESCRIPTOR no longer implements " +
                FUNCTION0_DESCRIPTOR,
        )
    }

    val match = Fingerprint(
        definingClass = HEADER_SCOPE,
        returnType = "V",
        custom = { method, _ -> method.isAskGrokHeaderMethod() },
    ).matchAll().singleOrNull()
        ?: throw PatchException(
            "Expected one NewX Ask Grok header method in $HEADER_SCOPE, found none or several",
        )
    if (match.originalClassDef.type != EXPECTED_HEADER_OWNER) {
        throw PatchException(
            "NewX Ask Grok header owner changed: expected $EXPECTED_HEADER_OWNER, " +
                "resolved ${match.originalClassDef.type}",
        )
    }

    val method = match.method
    val instructions = method.instructions.toList()

    val constIndex = instructions.indices.singleOrNull { index ->
        (instructions[index] as? NarrowLiteralInstruction)?.narrowLiteral == ASK_GROK_RES_ID
    } ?: throw PatchException(
        "Expected one post_option_ask_grok resource const in $method",
    )

    // The gate: the single e6.b0 read in the window before the string const.
    val gateCandidates = (maxOf(0, constIndex - 24) until constIndex).filter { index ->
        val instruction = instructions[index]
        val field = instruction.getReference<FieldReference>()
        instruction.opcode == Opcode.IGET_BOOLEAN &&
            field?.definingClass == AVAILABLE_POST_DESCRIPTOR &&
            field.name == GATE_FIELD_NAME &&
            field.type == "Z"
    }
    if (gateCandidates.size != 1) {
        throw PatchException(
            "Expected one Ask Grok gate read before the string resource in $method, " +
                "found ${gateCandidates.size}",
        )
    }
    val gateIndex = gateCandidates.single()
    val gateInstruction = instructions[gateIndex] as TwoRegisterInstruction
    val gateRegister = gateInstruction.registerA
    val postRegister = gateInstruction.registerB

    // The click lambda: the first y1.<init>(Function1, B) after the string
    // const whose discriminator argument is 13 (DidClickAskGrok).
    val clickInitIndex = (constIndex + 1 until instructions.size).firstOrNull { index ->
        val instruction = instructions[index]
        val reference = instruction.getReference<MethodReference>()
        instruction.opcode == Opcode.INVOKE_DIRECT &&
            reference?.name == "<init>" &&
            reference.definingClass == CLICK_LAMBDA_DESCRIPTOR &&
            reference.parameterTypes.map(CharSequence::toString) ==
            listOf(FUNCTION1_DESCRIPTOR, "B") &&
            instructions.isAskGrokClickInit(index)
    } ?: throw PatchException(
        "Expected the Ask Grok click lambda construction after the string resource in $method",
    )
    val clickInstanceRegister = instructions[clickInitIndex].registersUsed.first()

    // The icon: the method's single I4 sget.
    val iconCandidates = instructions.indices.filter { index ->
        val instruction = instructions[index]
        val field = instruction.getReference<FieldReference>()
        instruction.opcode == Opcode.SGET_OBJECT &&
            field?.definingClass == ICONS_DESCRIPTOR &&
            field.name == GROK_ICON_FIELD_NAME
    }
    if (iconCandidates.size != 1) {
        throw PatchException(
            "Expected one Grok icon access in $method, found ${iconCandidates.size}",
        )
    }
    val iconIndex = iconCandidates.single()
    val iconRegister = (instructions[iconIndex] as OneRegisterInstruction).registerA

    if (!(gateIndex < constIndex && constIndex < clickInitIndex && clickInitIndex < iconIndex)) {
        throw PatchException(
            "NewX Ask Grok segment anchors are out of order in $method: gate@$gateIndex, " +
                "string@$constIndex, click@$clickInitIndex, icon@$iconIndex",
        )
    }

    // Insert in descending index order so the earlier anchors stay valid.
    // Branch targets stay on the original instructions (relocate = false):
    // a path that jumps straight to the composer's remembered-value store
    // reuses the already-wrapped lambda from the first composition, and a
    // path that skips the gate read keeps the stock gate — both correct.
    method.insertHook(
        iconIndex + 1,
        excludedRegisters = listOf(iconRegister),
        relocateBranchTargets = false,
    ) {
        invokeStatic(
            methodReference("$HEADER_EXT->selectIcon($ICON_TYPE_DESCRIPTOR)$ICON_TYPE_DESCRIPTOR"),
            iconRegister,
        )
        moveResult(iconRegister, ICON_TYPE_DESCRIPTOR)
    }
    method.insertHook(
        clickInitIndex + 1,
        excludedRegisters = listOf(clickInstanceRegister, postRegister),
        relocateBranchTargets = false,
    ) {
        invokeStatic(
            methodReference(
                "$HEADER_EXT->wrapClick($CLICK_LAMBDA_DESCRIPTOR$AVAILABLE_POST_DESCRIPTOR)" +
                    FUNCTION0_DESCRIPTOR,
            ),
            clickInstanceRegister,
            postRegister,
        )
        moveResult(clickInstanceRegister, FUNCTION0_DESCRIPTOR)
    }
    method.insertHook(
        gateIndex + 1,
        excludedRegisters = listOf(gateRegister, postRegister),
        relocateBranchTargets = false,
    ) {
        invokeStatic(
            methodReference("$HEADER_EXT->forceShow(Z)Z"),
            gateRegister,
        )
        moveResult(gateRegister, "Z")
    }
}
