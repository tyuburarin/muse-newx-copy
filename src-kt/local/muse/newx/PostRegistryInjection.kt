package local.muse.newx

import app.crimera.bytecode.insertHook
import app.crimera.bytecode.methodReference
import app.crimera.patches.common.semantic.fieldForToStringLabel
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.registersUsed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * Post registry feeder for the header copy button (v0.2.6).
 *
 * The header button (HeaderCopyInjection) only receives the post's UI state
 * (e6), never a post model, so its taps resolve the post through the
 * extension registry in HeaderCopyButton (post id -> contextual post).
 * Until v0.2.5 that registry was fed as a side effect of the inline copy
 * entry's list filter in the inline action-bar state builder
 * (Lcom/x/inlineactionbar/f1;->c). The inline entry itself is removed in
 * v0.2.6 (the header button covers the home timeline — on-device evidence,
 * see BUILD.md), so the feeder becomes a dedicated hook:
 *
 *   head of f1.c:  HeaderCopyButton.rememberFromPresenter(this)
 *
 * f1.c is composed for every post whose action bar is built, i.e. the same
 * call frequency the old filter had, and the presenter (f1) is the one
 * object that provably holds the post: its field b is the contextual post
 * (Lcom/x/models/s1;, dex-verified). The extension reads it through the
 * patch-injected getPresenterPost bridge in HeaderCopyButton — the same
 * bridge body the inline filter used, moved to its new owner.
 *
 * Alternatives considered and rejected:
 *  - The header composable itself (sensitivemedia/api/c.b): it receives
 *    only e6 (post id holder h6 in field c, display flags) — no contextual
 *    post reference to remember.
 *  - The displayed-translation track (scribing/urt/c.R): its arguments are
 *    (registry, post id, TranslatedPost) — again no post object.
 *
 * The resolvers below are the state-builder half of the old
 * InlineCopyInjection, kept byte-for-byte in their matching logic (semantic
 * anchors pinned to the verified 12.43.0-prod.01 descriptors); only the
 * injections changed. The entry-model resolution is reduced to the entry
 * descriptor, which the state-builder flow proof still needs as its
 * element type.
 */

private const val HEADER_EXT = "Llocal/muse/newx/extension/misc/HeaderCopyButton;"
private const val OBJECT_DESCRIPTOR = "Ljava/lang/Object;"
private const val STRING_DESCRIPTOR = "Ljava/lang/String;"
private const val COMPOSER_DESCRIPTOR = "Landroidx/compose/runtime/Composer;"
private const val INLINE_ACTION_BAR_SCOPE = "Lcom/x/inlineactionbar/"

private const val EXPECTED_ENTRY_DESCRIPTOR = "Lcom/x/models/n4;"
private const val EXPECTED_CANONICAL_DESCRIPTOR = "Lcom/x/models/v0;"
private const val EXPECTED_CANONICAL_INTERFACE = "Lcom/x/models/v5;"
private const val EXPECTED_CONTEXTUAL_DESCRIPTOR = "Lcom/x/models/s1;"
private const val EXPECTED_BAR_DESCRIPTOR = "Lcom/x/inlineactionbar/f1;"

private data class RegistryBarModels(
    val barDescriptor: String,
    val stateBuilder: Match,
    val presenterPostField: com.android.tools.smali.dexlib2.iface.reference.FieldReference,
)

context(BytecodePatchContext)
internal fun injectPostRegistry() {
    val entryDescriptor = resolveInlineEntryDescriptor()
    val barModels = resolveRegistryBarModels(entryDescriptor)
    patchRegistryBridge(barModels)
    patchRegistryHook(barModels)
}

private fun requireOneMatch(label: String, matches: Collection<Match>): Match {
    if (matches.size == 1) return matches.single()
    throw PatchException(
        "Expected one $label match, found ${matches.size}: " +
            matches.joinToString { it.originalMethod.toString() },
    )
}

// ---------------------------------------------------------------------------
// Model resolution (semantic anchors, pinned to the verified 12.43 results)
// ---------------------------------------------------------------------------

/** Only the entry descriptor survives: the state-builder flow proof's element type. */
context(BytecodePatchContext)
private fun resolveInlineEntryDescriptor(): String {
    val match = requireOneMatch(
        "NewX inline-action entry model",
        Fingerprint(
            definingClass = "Lcom/x/models/",
            name = "toString",
            returnType = STRING_DESCRIPTOR,
            parameters = emptyList(),
            filters = listOf(
                app.morphe.patcher.string("InlineActionEntry(actionType="),
                app.morphe.patcher.string(", count="),
                app.morphe.patcher.string(", isEnabled="),
            ),
        ).matchAll(),
    )
    val entryDescriptor = match.originalClassDef.type
    if (entryDescriptor != EXPECTED_ENTRY_DESCRIPTOR) {
        throw PatchException(
            "NewX inline-action entry changed: expected $EXPECTED_ENTRY_DESCRIPTOR, " +
                "resolved $entryDescriptor",
        )
    }
    return entryDescriptor
}

context(BytecodePatchContext)
private fun resolveRegistryBarModels(entryDescriptor: String): RegistryBarModels {
    val canonicalMatch = requireOneMatch(
        "NewX canonical post model",
        Fingerprint(
            definingClass = "Lcom/x/models/",
            name = "toString",
            returnType = STRING_DESCRIPTOR,
            parameters = emptyList(),
            filters = listOf(app.morphe.patcher.string("CanonicalPost(id=")),
        ).matchAll(),
    )
    val canonicalDescriptor = canonicalMatch.originalClassDef.type
    if (canonicalDescriptor != EXPECTED_CANONICAL_DESCRIPTOR) {
        throw PatchException(
            "NewX canonical post changed: expected $EXPECTED_CANONICAL_DESCRIPTOR, " +
                "resolved $canonicalDescriptor",
        )
    }
    val canonicalClass = classDefBy(canonicalDescriptor)
    val canonicalInterfaceDescriptor = canonicalClass.interfaces.singleOrNull()?.toString()
        ?: throw PatchException("Expected one NewX canonical-post interface in $canonicalClass")
    if (canonicalInterfaceDescriptor != EXPECTED_CANONICAL_INTERFACE) {
        throw PatchException(
            "NewX canonical-post interface changed: expected $EXPECTED_CANONICAL_INTERFACE, " +
                "resolved $canonicalInterfaceDescriptor",
        )
    }
    val inlineActionEntryField = canonicalMatch.fieldForToStringLabel(", inlineActionEntry=")
    if (!inlineActionEntryField.type.startsWith("L")) {
        throw PatchException(
            "NewX canonical-post inline-action field is not an object collection: " +
                inlineActionEntryField,
        )
    }
    val contextualMatch = requireOneMatch(
        "NewX contextual post model",
        Fingerprint(
            definingClass = "Lcom/x/models/",
            name = "toString",
            returnType = STRING_DESCRIPTOR,
            parameters = emptyList(),
            filters = listOf(
                app.morphe.patcher.string("ContextualPost(canonicalPost="),
                app.morphe.patcher.string(", quotedPost="),
            ),
        ).matchAll(),
    )
    val contextualDescriptor = contextualMatch.originalClassDef.type
    if (contextualDescriptor != EXPECTED_CONTEXTUAL_DESCRIPTOR) {
        throw PatchException(
            "NewX contextual post changed: expected $EXPECTED_CONTEXTUAL_DESCRIPTOR, " +
                "resolved $contextualDescriptor",
        )
    }
    val stateBuilder = requireOneMatch(
        "NewX inline action state builder",
        Fingerprint(
            definingClass = INLINE_ACTION_BAR_SCOPE,
            parameters = listOf(COMPOSER_DESCRIPTOR),
            filters = listOf(
                methodCall(
                    definingClass = canonicalInterfaceDescriptor,
                    parameters = emptyList(),
                    returnType = inlineActionEntryField.type,
                ),
                methodCall(smali = "Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z"),
            ),
            custom = { method, _ ->
                method.hasInlineActionCollectionResultFlow(
                    definingClass = canonicalInterfaceDescriptor,
                    collectionType = inlineActionEntryField.type,
                    elementType = entryDescriptor,
                )
            },
        ).matchAll(),
    )
    val barDescriptor = stateBuilder.originalClassDef.type
    if (barDescriptor != EXPECTED_BAR_DESCRIPTOR) {
        throw PatchException(
            "NewX inline action bar changed: expected $EXPECTED_BAR_DESCRIPTOR, " +
                "resolved $barDescriptor",
        )
    }
    val barClass = classDefBy(barDescriptor)
    val presenterPostField = barClass.fields.singleOrNull { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) && field.type == contextualDescriptor
    } ?: throw PatchException("Expected one NewX inline presenter contextual-post field in $barClass")
    return RegistryBarModels(
        barDescriptor = barDescriptor,
        stateBuilder = stateBuilder,
        presenterPostField = presenterPostField,
    )
}

/** Port of Piko's collection flow proof for the state builder (PostModels.kt). */
private fun Method.hasInlineActionCollectionResultFlow(
    definingClass: String,
    collectionType: String,
    elementType: String,
): Boolean {
    val methodInstructions = implementation?.instructions?.toList() ?: return false

    fun moveResultObjectRegister(index: Int): Int? {
        val moveResult = methodInstructions.getOrNull(index + 1) ?: return null
        if (moveResult.opcode != Opcode.MOVE_RESULT_OBJECT) return null
        return (moveResult as? OneRegisterInstruction)?.registerA
    }

    val accessorCalls = methodInstructions.mapIndexedNotNull { index, instruction ->
        val reference = instruction.getReference<MethodReference>() ?: return@mapIndexedNotNull null
        if (
            reference.definingClass != definingClass ||
                reference.parameterTypes.isNotEmpty() ||
                reference.returnType != collectionType
        ) {
            return@mapIndexedNotNull null
        }
        val resultRegister = moveResultObjectRegister(index) ?: return@mapIndexedNotNull null
        index to resultRegister
    }
    if (accessorCalls.size != 1) return false

    val (accessorIndex, collectionResultRegister) = accessorCalls.single()
    val collectionRegisters = linkedSetOf(collectionResultRegister)
    val iteratorCalls = mutableListOf<Pair<Int, Int>>()
    for (index in accessorIndex + 2 until methodInstructions.size) {
        val instruction = methodInstructions[index]
        val reference = instruction.getReference<MethodReference>()
        if (
            reference != null &&
                reference.definingClass == "Ljava/lang/Iterable;" &&
                reference.name == "iterator" &&
                reference.parameterTypes.isEmpty() &&
                reference.returnType == "Ljava/util/Iterator;" &&
                instruction.registersUsed.firstOrNull() in collectionRegisters
        ) {
            moveResultObjectRegister(index)?.let { iteratorResultRegister ->
                iteratorCalls += index to iteratorResultRegister
            }
        }
        updateObjectAliases(instruction, collectionRegisters)
    }
    if (iteratorCalls.size != 1) return false

    val (iteratorIndex, iteratorResultRegister) = iteratorCalls.single()
    val iteratorRegisters = linkedSetOf(iteratorResultRegister)
    val nextCalls = mutableListOf<Pair<Int, Int>>()
    for (index in iteratorIndex + 2 until methodInstructions.size) {
        val instruction = methodInstructions[index]
        val reference = instruction.getReference<MethodReference>()
        if (
            reference != null &&
                reference.definingClass == "Ljava/util/Iterator;" &&
                reference.name == "next" &&
                reference.parameterTypes.isEmpty() &&
                reference.returnType == OBJECT_DESCRIPTOR &&
                instruction.registersUsed.firstOrNull() in iteratorRegisters
        ) {
            moveResultObjectRegister(index)?.let { nextResultRegister ->
                nextCalls += index to nextResultRegister
            }
        }
        updateObjectAliases(instruction, iteratorRegisters)
    }
    if (nextCalls.size != 1) return false

    val (nextIndex, nextResultRegister) = nextCalls.single()
    val elementRegisters = linkedSetOf(nextResultRegister)
    val elementCastIndices = mutableListOf<Pair<Int, Int>>()
    for (index in nextIndex + 2 until methodInstructions.size) {
        val instruction = methodInstructions[index]
        if (instruction.opcode == Opcode.CHECK_CAST) {
            val castRegister = (instruction as? OneRegisterInstruction)?.registerA
            val castType = instruction.getReference<TypeReference>()?.type
            if (castRegister in elementRegisters && castType == elementType) {
                castRegister?.let { elementCastIndices += index to it }
            }
        }
        updateObjectAliases(instruction, elementRegisters)
    }
    if (elementCastIndices.size != 1) return false

    val (elementCastIndex, elementRegister) = elementCastIndices.single()
    val valueRegisters = linkedSetOf(elementRegister)
    val pendingEntryResults = mutableMapOf<Int, Int>()
    val consumerIndices = mutableListOf<Int>()
    for (index in elementCastIndex + 1 until methodInstructions.size) {
        val instruction = methodInstructions[index]
        val reference = instruction.getReference<MethodReference>()
        val arguments = instruction.registersUsed

        if (
            reference != null &&
                reference.definingClass == "Ljava/util/ArrayList;" &&
                reference.name == "add" &&
                reference.parameterTypes.map(CharSequence::toString) == listOf(OBJECT_DESCRIPTOR) &&
                reference.returnType == "Z" &&
                arguments.size == 2 &&
                arguments[1] in valueRegisters
        ) {
            consumerIndices += index
        }

        if (
            reference != null &&
                reference.returnType == elementType &&
                arguments.any { it in valueRegisters }
        ) {
            moveResultObjectRegister(index)?.let { resultRegister ->
                pendingEntryResults[index + 1] = resultRegister
            }
        }

        if (instruction.opcode == Opcode.MOVE_RESULT_OBJECT) {
            val resultRegister = (instruction as? OneRegisterInstruction)?.registerA
            if (resultRegister != null) {
                valueRegisters.remove(resultRegister)
                if (pendingEntryResults.remove(index) == resultRegister) {
                    valueRegisters += resultRegister
                }
            }
        } else {
            updateObjectAliases(instruction, valueRegisters)
        }
    }
    return consumerIndices.size == 1
}

private fun updateObjectAliases(
    instruction: Instruction,
    registers: MutableSet<Int>,
) {
    when (instruction.opcode) {
        Opcode.MOVE_OBJECT,
        Opcode.MOVE_OBJECT_FROM16,
        Opcode.MOVE_OBJECT_16,
        -> {
            val move = instruction as? TwoRegisterInstruction ?: return
            registers.remove(move.registerA)
            if (move.registerB in registers) registers += move.registerA
        }
        Opcode.MOVE_RESULT_OBJECT -> {
            (instruction as? OneRegisterInstruction)?.registerA?.let(registers::remove)
        }
        else -> Unit
    }
}

// ---------------------------------------------------------------------------
// Bridge: HeaderCopyButton.getPresenterPost reads the presenter's post field
// ---------------------------------------------------------------------------

context(BytecodePatchContext)
private fun patchRegistryBridge(barModels: RegistryBarModels) {
    // The presenter post field is read from the extension class, so it must be public.
    val presenterClass = mutableClassDefBy(barModels.barDescriptor)
    val presenterPostField = presenterClass.fields.singleOrNull { field ->
        field.toString() == barModels.presenterPostField.toString()
    } ?: throw PatchException("NewX inline presenter post field was not found: ${barModels.presenterPostField}")
    presenterPostField.accessFlags =
        (presenterPostField.accessFlags and
            (AccessFlags.PRIVATE.value or AccessFlags.PROTECTED.value).inv()) or
            AccessFlags.PUBLIC.value

    val extensionClass = mutableClassDefBy(HEADER_EXT)

    // getPresenterPost(presenter): check-cast to the bar type, read the field, return it.
    val presenterPostHelper = extensionClass.methods.singleOrNull { method ->
        method.name == "getPresenterPost" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(OBJECT_DESCRIPTOR) &&
            method.returnType == OBJECT_DESCRIPTOR
    } ?: throw PatchException("Expected one NewX header getPresenterPost bridge in $extensionClass")
    presenterPostHelper.insertHook(0, relocateBranchTargets = false) {
        val value = presenterPostHelper.p0Register
        checkCast(value, barModels.barDescriptor)
        iget(value, value, barModels.presenterPostField)
        returnObject(value)
    }
}

// ---------------------------------------------------------------------------
// Feeder hook: remember the presenter's post at the head of the state builder
// ---------------------------------------------------------------------------

context(BytecodePatchContext)
private fun patchRegistryHook(barModels: RegistryBarModels) {
    val method = barModels.stateBuilder.method
    if (AccessFlags.STATIC.isSet(method.accessFlags)) {
        throw PatchException("NewX inline action state builder is unexpectedly static: $method")
    }
    // The presenter instance (p0) is the only argument the feeder needs: the
    // extension reads its contextual-post field through the bridge above.
    // No scratch registers and no list mutation — unlike the removed inline
    // filter, this hook leaves the built action list completely untouched.
    method.insertHook(0, relocateBranchTargets = false) {
        invokeStatic(
            methodReference("$HEADER_EXT->rememberFromPresenter(Ljava/lang/Object;)V"),
            method.p0Register,
        )
    }
}
