package local.muse.newx

import app.crimera.bytecode.RegisterLimit
import app.crimera.bytecode.Target
import app.crimera.bytecode.insertHook
import app.crimera.bytecode.methodReference
import app.crimera.patches.common.requireExactlyOne
import app.crimera.patches.common.semantic.fieldForToStringLabel
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.Match
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import app.morphe.patcher.util.addFreeRegisters
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.numberOfParameterRegisters
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.registersUsed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.util.ReferenceUtil

/*
 * Focal-only inline one-tap copy for NewX (v0.2.7), a bundle-local port of
 * Piko's InlineDownloadButton stack (CustomizeInlineActionsPatch +
 * InlineDownloadButtonPatch + the model resolvers), adapted the same way
 * PostOptionsInjection.kt adapted the post-menu stack:
 *
 *  - No dependsOn, no settings, no Application init hook: the extension is
 *    always enabled (Morphe's patch selection is the toggle) and takes its
 *    Context from ActivityThread at tap time.
 *  - The resolvers keep Piko's semantic anchors (toString labels, instruction
 *    shapes) but assert the exact descriptors verified for 12.43.0-prod.01,
 *    this bundle's only compatibility target; any drift fails the patch loudly
 *    instead of silently dropping the feature.
 *
 * History and scoping: through v0.2.5 the copy entry was appended to EVERY
 * post's action list by a filter in the inline state builder (f1.c). v0.2.6
 * removed it everywhere because the header button covers the timeline — but
 * the opened (focal) post of the detail screen renders through the other
 * post layout (Lcom/x/urt/items/post/a2;->a, driven by the FocalPostState),
 * whose header has no Ask Grok slot, so the focal post was left with no
 * one-tap copy. The user approved bringing the entry back for the focal
 * post only, placed just before the native share entry.
 *
 * Focal scoping is by construction, not by a runtime discriminator: the
 * entry is injected by a wrap hook inside a2.a itself, run on the bar
 * state (r1) immediately before the focal layout's single bar-composable
 * call. a2.a composes focal posts only (its state is the FocalPostState;
 * the timeline layout c.b is a different method and is never hooked here),
 * and the state builder is NOT hooked for the entry anymore, so timeline
 * bars never contain the entry. The renderer / kind / icon / event hooks
 * below are shared machinery, but they only ever see the entry inside
 * focal bars. The wrap is declared with the state's exact type
 * (Lcom/x/inlineactionbar/r1; -> Lcom/x/inlineactionbar/r1;): its
 * move-result writes back into the register feeding the bar composable,
 * whose first parameter is that type (v0.2.5 typing discipline).
 *
 * Icon channel (v0.2.3): the captured icon size carries the classification.
 * Piko's download button marks its entry with a plain negative size and its
 * selector is a binary sign check. This bundle marks the copy entry with a
 * negative size whose lowest mantissa bit is set, and its selector hook is
 * inserted after Piko's selector block when both are present, so it has the
 * last word: copy-marked sizes become the copy icon, Piko's plain-negative
 * mark keeps whatever Piko's selector produced (the download icon), and
 * unmarked sizes pass through. See BUILD.md for the instruction-level proof.
 * The taps dispatch by identity regardless of the icon shown.
 */

private const val INLINE_EXT = "Llocal/muse/newx/extension/misc/InlineCopyButton;"
private const val OBJECT_DESCRIPTOR = "Ljava/lang/Object;"
private const val STRING_DESCRIPTOR = "Ljava/lang/String;"
private const val LIST_DESCRIPTOR = "Ljava/util/List;"
private const val COMPOSER_DESCRIPTOR = "Landroidx/compose/runtime/Composer;"
private const val COMPOSE_MODIFIER_DESCRIPTOR = "Landroidx/compose/ui/Modifier;"
private const val INLINE_ACTION_BAR_SCOPE = "Lcom/x/inlineactionbar/"
private const val SKIP_MARK_ICON_SIZE_LABEL = "muse_skip_mark_icon_size"

private const val ITERABLE_DESCRIPTOR = "Ljava/lang/Iterable;"
private const val IMMUTABLE_LIST_DESCRIPTOR = "Lkotlinx/collections/immutable/b;"
private const val EXPECTED_ENTRIES_CONVERSION =
    "Lcom/x/signing/e;->v(Ljava/lang/Iterable;)Lkotlinx/collections/immutable/b;"
private const val POST_ITEMS_SCOPE = "Lcom/x/urt/items/post/"
private const val FOCAL_LAYOUT_DESCRIPTOR = "Lcom/x/urt/items/post/a2;"
private const val AVAILABLE_POST_DESCRIPTOR = "Lcom/x/urt/items/post/e6;"
private const val BAR_COMPOSABLE_DESCRIPTOR = "Lcom/x/inlineactionbar/w;"
private const val BAR_STATE_DESCRIPTOR = "Lcom/x/inlineactionbar/r1;"
private const val BAR_STATE_FIELD_NAME = "n"

private const val EXPECTED_ENTRY_DESCRIPTOR = "Lcom/x/models/n4;"
private const val EXPECTED_ACTION_TYPE_DESCRIPTOR = "Lcom/x/models/x5;"
private const val EXPECTED_CANONICAL_DESCRIPTOR = "Lcom/x/models/v0;"
private const val EXPECTED_CANONICAL_INTERFACE = "Lcom/x/models/v5;"
private const val EXPECTED_CONTEXTUAL_DESCRIPTOR = "Lcom/x/models/s1;"
private const val EXPECTED_BAR_DESCRIPTOR = "Lcom/x/inlineactionbar/f1;"

private data class InlineEntryModels(
    val entryDescriptor: String,
    val actionTypeField: FieldReference,
    val enabledField: FieldReference,
    val postActionTypeDescriptor: String,
    val entryConstructor: MethodReference,
    val twitterShareField: FieldReference,
)

private data class InlineBarModels(
    val canonicalInterfaceDescriptor: String,
    val inlineActionEntryField: FieldReference,
    val barDescriptor: String,
    val stateBuilder: Match,
    val presenterPostField: FieldReference,
)

context(BytecodePatchContext)
internal fun injectFocalInlineCopy() {
    val entryModels = resolveInlineEntryModels()
    val barModels = resolveInlineBarModels(entryModels)
    patchInlineCopyBridges(entryModels, barModels)
    patchFocalBarWrap()
    patchInlineKindOverride(entryModels)
    patchInlineRenderer(entryModels)
    patchInlineIconLambda()
    patchInlineEventHandler(entryModels, barModels)
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

context(BytecodePatchContext)
private fun resolveInlineEntryModels(): InlineEntryModels {
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
    val actionTypeField = match.fieldForToStringLabel("InlineActionEntry(actionType=")
    val postActionTypeDescriptor = actionTypeField.type
    if (postActionTypeDescriptor != EXPECTED_ACTION_TYPE_DESCRIPTOR) {
        throw PatchException(
            "NewX post action type changed: expected $EXPECTED_ACTION_TYPE_DESCRIPTOR, " +
                "resolved $postActionTypeDescriptor",
        )
    }
    val entryClass = match.originalClassDef
    // The entry has exactly one boolean instance field: isEnabled.
    val enabledField = entryClass.fields.singleOrNull { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) && field.type == "Z"
    } ?: throw PatchException("Expected one NewX inline-action enabled field in $entryClass")
    val entryConstructor = entryClass.methods.singleOrNull { method ->
        method.name == "<init>" &&
            method.parameterTypes.map(CharSequence::toString) ==
            listOf(postActionTypeDescriptor, "Ljava/lang/Long;", "Z") &&
            method.returnType == "V"
    } ?: throw PatchException("Expected one NewX inline-action constructor in $entryClass")
    val actionTypeClass = classDefBy(postActionTypeDescriptor)
    val twitterShareField = actionTypeClass.fields.singleOrNull { field ->
        AccessFlags.STATIC.isSet(field.accessFlags) &&
            field.type == postActionTypeDescriptor &&
            field.name == "TwitterShare"
    } ?: throw PatchException("Expected one NewX TwitterShare action constant in $actionTypeClass")
    return InlineEntryModels(
        entryDescriptor = entryDescriptor,
        actionTypeField = actionTypeField,
        enabledField = enabledField,
        postActionTypeDescriptor = postActionTypeDescriptor,
        entryConstructor = entryConstructor,
        twitterShareField = twitterShareField,
    )
}

context(BytecodePatchContext)
private fun resolveInlineBarModels(entryModels: InlineEntryModels): InlineBarModels {
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
                    elementType = entryModels.entryDescriptor,
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
    return InlineBarModels(
        canonicalInterfaceDescriptor = canonicalInterfaceDescriptor,
        inlineActionEntryField = inlineActionEntryField,
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
// Extension bridges (bodies injected into the extension stubs)
// ---------------------------------------------------------------------------

context(BytecodePatchContext)
private fun patchInlineCopyBridges(entryModels: InlineEntryModels, barModels: InlineBarModels) {
    // The presenter post field is read from the extension class, so it must be public.
    val presenterClass = mutableClassDefBy(barModels.barDescriptor)
    val presenterPostField = presenterClass.fields.singleOrNull { field ->
        field.toString() == barModels.presenterPostField.toString()
    } ?: throw PatchException("NewX inline presenter post field was not found: ${barModels.presenterPostField}")
    presenterPostField.accessFlags =
        (presenterPostField.accessFlags and
            (AccessFlags.PRIVATE.value or AccessFlags.PROTECTED.value).inv()) or
            AccessFlags.PUBLIC.value

    val extensionClass = mutableClassDefBy(INLINE_EXT)

    // getPresenterPost(presenter): check-cast to the bar type, read the field, return it.
    val presenterPostHelper = extensionClass.methods.singleOrNull { method ->
        method.name == "getPresenterPost" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(OBJECT_DESCRIPTOR) &&
            method.returnType == OBJECT_DESCRIPTOR
    } ?: throw PatchException("Expected one NewX inline getPresenterPost bridge in $extensionClass")
    presenterPostHelper.insertHook(0, relocateBranchTargets = false) {
        val value = presenterPostHelper.p0Register
        checkCast(value, barModels.barDescriptor)
        iget(value, value, barModels.presenterPostField)
        returnObject(value)
    }

    // createCopyAction(): new InlineActionEntry(TwitterShare, null, true), returned.
    val createPlaceholder = extensionClass.methods.singleOrNull { method ->
        method.name == "createCopyAction" &&
            method.parameterTypes.isEmpty() &&
            method.returnType == OBJECT_DESCRIPTOR
    } ?: throw PatchException("Expected one NewX inline createCopyAction bridge in $extensionClass")
    val registerCount = createPlaceholder.implementation?.registerCount ?: 0
    val createHelper = if (registerCount >= 4) {
        createPlaceholder
    } else {
        val (expanded, _) = createPlaceholder.addFreeRegisters(4 - registerCount)
        extensionClass.methods.remove(createPlaceholder)
        extensionClass.methods.add(expanded)
        expanded
    }
    createHelper.insertHook(0, relocateBranchTargets = false) {
        newInstance(0, entryModels.entryDescriptor)
        sget(1, entryModels.twitterShareField)
        constInt(2, 0)
        constInt(3, 1)
        invokeDirect(entryModels.entryConstructor, 0, 1, 2, 3)
        returnObject(0)
    }

    // toImmutableEntries(entries): the app's own Iterable -> immutable-list
    // conversion (the same one the state builder applies), so the focal wrap
    // can rebuild the bar state's entry list in exactly the declared type.
    val conversion = resolveEntriesConversion(barModels)
    val conversionHelper = extensionClass.methods.singleOrNull { method ->
        method.name == "toImmutableEntries" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(OBJECT_DESCRIPTOR) &&
            method.returnType == OBJECT_DESCRIPTOR
    } ?: throw PatchException("Expected one NewX inline toImmutableEntries bridge in $extensionClass")
    conversionHelper.insertHook(0, relocateBranchTargets = false) {
        val value = conversionHelper.p0Register
        checkCast(value, ITERABLE_DESCRIPTOR)
        invokeStatic(conversion, value)
        moveResult(value, IMMUTABLE_LIST_DESCRIPTOR)
        returnObject(value)
    }
}

/**
 * The state builder's list conversion, resolved semantically (a static
 * Iterable -> immutable-entries call inside the state builder) and pinned
 * to the verified 12.43 reference.
 */
private fun resolveEntriesConversion(barModels: InlineBarModels): MethodReference {
    val references = barModels.stateBuilder.method.instructions.mapNotNull { instruction ->
        if (instruction.opcode != Opcode.INVOKE_STATIC) return@mapNotNull null
        val reference = instruction.getReference<MethodReference>() ?: return@mapNotNull null
        reference.takeIf {
            reference.parameterTypes.map(CharSequence::toString) == listOf(ITERABLE_DESCRIPTOR) &&
                reference.returnType == IMMUTABLE_LIST_DESCRIPTOR
        }
    }.distinctBy { it.toString() }
    val conversion = references.singleOrNull()
        ?: throw PatchException(
            "Expected one NewX entries conversion in ${barModels.stateBuilder.method}, " +
                "found ${references.size}: ${references.joinToString()}",
        )
    if (conversion.toString() != EXPECTED_ENTRIES_CONVERSION) {
        throw PatchException(
            "NewX entries conversion changed: expected $EXPECTED_ENTRIES_CONVERSION, " +
                "resolved $conversion",
        )
    }
    return conversion
}

// ---------------------------------------------------------------------------
// Focal bar wrap: run the bar state through the extension inside a2.a
// ---------------------------------------------------------------------------

/**
 * The focal layout's bar-state wrap. Resolution is semantic (an e6-led void
 * method under urt/items/post that reads e6's bar-state field and calls the
 * bar composable with it), then pinned to the verified owner
 * (Lcom/x/urt/items/post/a2;->a, the FocalPostState layout). The wrap is
 * inserted immediately before the move that loads the state register into
 * the bar call's argument range, after proving the register still holds
 * the field read: no instruction between the read and the move may write
 * it. The hook is exactly typed (r1 -> r1), matching the field type, the
 * composable's first parameter and the register's downstream use.
 */
context(BytecodePatchContext)
private fun patchFocalBarWrap() {
    val match = requireOneMatch(
        "NewX focal post layout",
        Fingerprint(
            definingClass = POST_ITEMS_SCOPE,
            returnType = "V",
            custom = { method, _ -> method.isFocalLayoutMethod() },
        ).matchAll(),
    )
    if (match.originalClassDef.type != FOCAL_LAYOUT_DESCRIPTOR) {
        throw PatchException(
            "NewX focal layout owner changed: expected $FOCAL_LAYOUT_DESCRIPTOR, " +
                "resolved ${match.originalClassDef.type}",
        )
    }
    val method = match.method
    val instructions = method.instructions.toList()

    // The bar composable call: exactly one, its first parameter is the state.
    val barCallIndices = instructions.mapIndexedNotNull { index, instruction ->
        val reference = instruction.getReference<MethodReference>() ?: return@mapIndexedNotNull null
        index.takeIf {
            (instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE) &&
                reference.definingClass == BAR_COMPOSABLE_DESCRIPTOR &&
                reference.name == "c" &&
                reference.parameterTypes.firstOrNull()?.toString() == BAR_STATE_DESCRIPTOR
        }
    }
    val barCallIndex = barCallIndices.singleOrNull()
        ?: throw PatchException(
            "Expected one NewX focal bar composable call in $method, found ${barCallIndices.size}",
        )
    val barCall = instructions[barCallIndex]
    val rangeStart = (barCall as? com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc)
        ?.startRegister
        ?: throw PatchException("NewX focal bar call is not a range invoke in $method")

    // The move that loads the state into the range start, closest before the call.
    val moveIndex = (barCallIndex - 1 downTo 0).firstOrNull { index ->
        val instruction = instructions[index]
        instruction.opcode in setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16) &&
            (instruction as? TwoRegisterInstruction)?.registerA == rangeStart
    } ?: throw PatchException("NewX focal bar state move was not found in $method")
    val stateRegister = (instructions[moveIndex] as TwoRegisterInstruction).registerB

    // The bar-state field read that produced the register, closest before the move.
    val readIndex = (moveIndex - 1 downTo 0).firstOrNull { index ->
        val instruction = instructions[index]
        val field = instruction.getReference<FieldReference>()
        instruction.opcode == Opcode.IGET_OBJECT &&
            field?.definingClass == AVAILABLE_POST_DESCRIPTOR &&
            field.name == BAR_STATE_FIELD_NAME &&
            field.type == BAR_STATE_DESCRIPTOR &&
            (instruction as? TwoRegisterInstruction)?.registerA == stateRegister
    } ?: throw PatchException("NewX focal bar state read was not found in $method")

    // The register must still hold the read's value at the move: nothing in
    // between may write it.
    val overwritten = (readIndex + 1 until moveIndex).any { index ->
        val instruction = instructions[index]
        if (!instruction.opcode.setsRegister()) return@any false
        val destination = (instruction as? OneRegisterInstruction)?.registerA ?: return@any false
        destination == stateRegister ||
            (instruction.opcode.setsWideRegister() && destination + 1 == stateRegister)
    }
    if (overwritten) {
        throw PatchException(
            "NewX focal bar state register v$stateRegister is overwritten between the " +
                "field read and the bar call in $method",
        )
    }

    method.insertHook(
        moveIndex,
        excludedRegisters = listOf(stateRegister),
        relocateBranchTargets = false,
    ) {
        invokeStatic(
            methodReference("$INLINE_EXT->wrapFocalBar($BAR_STATE_DESCRIPTOR)$BAR_STATE_DESCRIPTOR"),
            stateRegister,
        )
        moveResult(stateRegister, BAR_STATE_DESCRIPTOR)
    }
}

/** Semantic shape of the focal post layout (a2.a): e6-led, reads the bar state, calls the bar. */
private fun Method.isFocalLayoutMethod(): Boolean {
    if (returnType != "V") return false
    if (parameterTypes.firstOrNull()?.toString() != AVAILABLE_POST_DESCRIPTOR) return false
    val methodInstructions = implementation?.instructions?.toList() ?: return false
    var hasBarStateRead = false
    var hasBarCall = false
    for (instruction in methodInstructions) {
        val field = instruction.getReference<FieldReference>()
        if (
            instruction.opcode == Opcode.IGET_OBJECT &&
                field?.definingClass == AVAILABLE_POST_DESCRIPTOR &&
                field.name == BAR_STATE_FIELD_NAME &&
                field.type == BAR_STATE_DESCRIPTOR
        ) {
            hasBarStateRead = true
        }
        val call = instruction.getReference<MethodReference>()
        if (
            call?.definingClass == BAR_COMPOSABLE_DESCRIPTOR &&
                call.name == "c" &&
                call.parameterTypes.firstOrNull()?.toString() == BAR_STATE_DESCRIPTOR
        ) {
            hasBarCall = true
        }
    }
    return hasBarStateRead && hasBarCall
}

// ---------------------------------------------------------------------------
// Kind override: the copy entry's slot is IconOnly, not the wide TwitterShare slot
// ---------------------------------------------------------------------------

context(BytecodePatchContext)
private fun patchInlineKindOverride(entryModels: InlineEntryModels) {
    val layoutMatch = requireOneMatch(
        "NewX inline-action kind layout lambda",
        Fingerprint(
            definingClass = INLINE_ACTION_BAR_SCOPE,
            name = "invoke",
            returnType = OBJECT_DESCRIPTOR,
            filters = listOf(
                fieldAccess(
                    opcode = Opcode.IGET_OBJECT,
                    reference = entryModels.actionTypeField,
                ),
            ),
        ).matchAll().filter { match ->
            match.originalMethod.definingClass.startsWith(INLINE_ACTION_BAR_SCOPE) &&
                match.originalMethod.implementation?.instructions?.any { instruction ->
                    instruction.opcode == Opcode.IGET_OBJECT &&
                        instruction.getReference<FieldReference>()?.toString() ==
                        entryModels.actionTypeField.toString()
                } == true
        },
    )
    val layoutMethod = layoutMatch.method

    // Kind model constructor: <init> with exactly 3 params, the last an enum (the kind).
    val kindConstructor = requireExactlyOne(
        "NewX inline-action kind model constructor",
        layoutMethod.instructions.mapIndexedNotNull { index, instruction ->
            index.takeIf {
                if (instruction.opcode != Opcode.INVOKE_DIRECT) return@takeIf false
                val reference = instruction.getReference<MethodReference>() ?: return@takeIf false
                if (reference.name != "<init>") return@takeIf false
                val parameterTypes = reference.parameterTypes.map(CharSequence::toString)
                if (parameterTypes.size != 3) return@takeIf false
                val kindType = parameterTypes.last()
                if (kindType == "J" || kindType == "D") return@takeIf false
                classDefByOrNull(kindType)?.let { kindClass ->
                    AccessFlags.ENUM.isSet(kindClass.accessFlags)
                } == true
            }?.let { constructorIndex ->
                constructorIndex to layoutMethod.instructions[constructorIndex]
            }
        },
    ).second.getReference<MethodReference>()
        ?: throw PatchException("NewX inline-action kind model constructor has no reference")
    val kindEnumDescriptor = kindConstructor.parameterTypes.last().toString()
    val kindEnumClass = classDefByOrNull(kindEnumDescriptor)
        ?: throw PatchException("NewX inline-action kind enum was not found: $kindEnumDescriptor")
    val iconOnlyField = requireExactlyOne(
        "NewX inline-action IconOnly kind",
        kindEnumClass.fields.filter { field ->
            field.name == "IconOnly" &&
                field.type == kindEnumDescriptor &&
                AccessFlags.STATIC.isSet(field.accessFlags)
        },
    )

    val actionTypeReadIndex = requireExactlyOne(
        "NewX inline-action kind action-type read in $layoutMethod",
        layoutMethod.instructions.mapIndexedNotNull { index, instruction ->
            index.takeIf {
                instruction.opcode == Opcode.IGET_OBJECT &&
                    instruction.getReference<FieldReference>()?.toString() ==
                    entryModels.actionTypeField.toString()
            }
        },
    )
    val entryRegister =
        layoutMethod.getInstruction<TwoRegisterInstruction>(actionTypeReadIndex).registerB

    val entryCasts = layoutMethod.instructions.mapIndexedNotNull { index, instruction ->
        index.takeIf {
            instruction.opcode == Opcode.CHECK_CAST &&
                instruction.getReference<TypeReference>()?.type == entryModels.entryDescriptor &&
                (instruction as? OneRegisterInstruction)?.registerA == entryRegister
        }
    }
    requireExactlyOne(
        "NewX inline-action entry cast for v$entryRegister in $layoutMethod",
        entryCasts,
    )

    val constructorIndex = requireExactlyOne(
        "NewX inline-action kind model construction in $layoutMethod",
        layoutMethod.instructions.mapIndexedNotNull { index, instruction ->
            index.takeIf {
                instruction.opcode == Opcode.INVOKE_DIRECT &&
                    instruction.getReference<MethodReference>()?.toString() ==
                    kindConstructor.toString()
            }
        },
    )
    val constructorInstruction = layoutMethod.getInstruction<Instruction>(constructorIndex)
    val kindRegister = constructorInstruction.registersUsed.last()
    if (kindRegister == entryRegister) {
        throw PatchException("NewX inline-action entry and kind registers collide in $layoutMethod")
    }

    resolveInlineActionKindRenderCall(
        layoutMethod,
        constructorIndex,
        entryRegister,
        entryModels.entryDescriptor,
    )

    val parameterRegisters = layoutMethod.p0Register until
        (layoutMethod.p0Register + layoutMethod.numberOfParameterRegisters)
    layoutMethod.insertHook(
        index = constructorIndex,
        excludedRegisters = parameterRegisters + entryRegister + kindRegister,
        relocateBranchTargets = false,
    ) {
        val scratchRegister = scratchRegister()
        move(scratchRegister, entryRegister, OBJECT_DESCRIPTOR)
        invokeStatic(
            methodReference("$INLINE_EXT->isCopyAction(Ljava/lang/Object;)Z"),
            scratchRegister,
        )
        moveResult(scratchRegister, "Z")
        ifEqz(scratchRegister, Target.Original)
        sget(kindRegister, iconOnlyField)
    }
}

/** Port of Piko's staging-window proof for the kind render call. */
private fun resolveInlineActionKindRenderCall(
    method: Method,
    constructorIndex: Int,
    entryRegister: Int,
    entryDescriptor: String,
): Int {
    val instructions = method.implementation?.instructions?.toList().orEmpty()
    val offsets = instructions.runningFold(0) { offset, instruction -> offset + instruction.codeUnits }
    val incomingTargets = mutableSetOf<Int>()
    instructions.forEachIndexed { index, instruction ->
        val branch = instruction as? com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
            ?: return@forEachIndexed
        val target = offsets[index] + branch.codeOffset
        incomingTargets += target
        if (instruction.opcode == Opcode.PACKED_SWITCH || instruction.opcode == Opcode.SPARSE_SWITCH) {
            val payload = instructions.getOrNull(offsets.indexOf(target))
                as? com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
                ?: throw PatchException("NewX inline-action switch payload was not found: $method")
            payload.switchElements.forEach { incomingTargets += offsets[index] + it.offset }
        }
    }
    method.implementation?.tryBlocks?.forEach { block ->
        block.exceptionHandlers.forEach { incomingTargets += it.handlerCodeAddress }
    }
    return requireExactlyOne(
        "NewX inline-action render call for v$entryRegister in $method",
        instructions.mapIndexedNotNull { index, instruction ->
            val reference = instruction.getReference<MethodReference>() ?: return@mapIndexedNotNull null
            if (index <= constructorIndex ||
                instruction.opcode !in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE) ||
                reference.parameterTypes.firstOrNull()?.toString() != entryDescriptor
            ) return@mapIndexedNotNull null
            if (incomingTargets.any { it > offsets[constructorIndex] && it <= offsets[index] }) {
                return@mapIndexedNotNull null
            }
            if (method.implementation?.tryBlocks?.any { block ->
                    block.startCodeAddress < offsets[index] &&
                        block.startCodeAddress + block.codeUnitCount > offsets[constructorIndex]
                } == true
            ) return@mapIndexedNotNull null
            val aliases = mutableSetOf(entryRegister)
            for (position in constructorIndex + 1 until index) {
                val current = instructions[position]
                if (current is com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction ||
                    !current.opcode.canContinue()
                ) return@mapIndexedNotNull null
                if (current.opcode in setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)) {
                    val move = current as TwoRegisterInstruction
                    val carriesEntry = move.registerB in aliases
                    aliases.remove(move.registerA)
                    if (carriesEntry) aliases += move.registerA
                } else if (current.opcode.setsRegister() && current.opcode != Opcode.CHECK_CAST) {
                    val destination = (current as OneRegisterInstruction).registerA
                    aliases.remove(destination)
                    if (current.opcode.setsWideRegister()) aliases.remove(destination + 1)
                }
            }
            index.takeIf { instruction.registersUsed.firstOrNull() in aliases }
        },
    )
}

// ---------------------------------------------------------------------------
// Renderer: mark the copy entry's icon size (negative sign) at the size read
// ---------------------------------------------------------------------------

/** Semantic shape of the inline-action entry renderer (port of Piko's check). */
private fun Method.isInlineActionEntryRenderer(entryDescriptor: String): Boolean {
    if (returnType != "V") return false
    val params = parameterTypes.map(CharSequence::toString)
    if (params.firstOrNull() != entryDescriptor) return false
    if (params.lastOrNull() != "I") return false
    if (COMPOSER_DESCRIPTOR !in params) return false
    if (COMPOSE_MODIFIER_DESCRIPTOR !in params) return false
    return true
}

/** Slot of the first parameter with the given descriptor (wide types count twice). */
private fun Method.firstParameterSlot(descriptor: String): Int {
    var slot = 0
    for (type in parameterTypes.map(CharSequence::toString)) {
        if (type == descriptor) return slot
        slot += if (type == "J" || type == "D") 2 else 1
    }
    throw PatchException("NewX renderer has no $descriptor parameter: $this")
}

context(BytecodePatchContext)
private fun patchInlineRenderer(entryModels: InlineEntryModels) {
    val rendererModels = InlineRendererModels(
        entryDescriptor = entryModels.entryDescriptor,
        actionTypeField = entryModels.actionTypeField,
        enabledField = entryModels.enabledField,
    )
    // Two renderer shapes exist across releases: a direct static renderer and
    // a captured FunctionN lambda. In 12.43.0-prod.01 the direct composable
    // (Lcom/x/inlineactionbar/w;->d) reads only the action type and hands the
    // entry to captured lambdas, so the captured shape is the one that matches;
    // exactly one method across both shapes may win.
    val staticRenderers = Fingerprint(
        returnType = "V",
        custom = { method, _ -> method.isInlineActionEntryRenderer(entryModels.entryDescriptor) },
        filters = listOf(
            fieldAccess(
                opcode = Opcode.IGET_OBJECT,
                reference = entryModels.actionTypeField,
            ),
            fieldAccess(
                opcode = Opcode.IGET_BOOLEAN,
                reference = entryModels.enabledField,
            ),
        ),
    ).matchAllOrNull().orEmpty()
    val capturedCandidates = capturedInlineActionRendererCandidates(rendererModels)
    val rendererMethod = requireExactlyOne(
        "NewX inline-action entry renderer across direct and captured shapes",
        staticRenderers.map { match -> match.originalMethod } +
            capturedCandidates.map { candidate -> candidate.method },
    )
    val staticRenderer = staticRenderers.singleOrNull { match -> match.originalMethod == rendererMethod }
    if (staticRenderer != null) {
        val method = staticRenderer.method
        if (!AccessFlags.STATIC.isSet(method.accessFlags)) {
            throw PatchException("NewX inline-action entry renderer is not static: $method")
        }
        val (hookIndex, sizeRegister) = resolveInlineIconSizeHook(method)
        method.insertMarkIconSizeHook(
            index = hookIndex,
            actionRegister = method.p0Register,
            sizeRegister = sizeRegister,
            actionTypeField = entryModels.actionTypeField,
            twitterShareActionField = entryModels.twitterShareField,
            excludedRegisters = listOf(method.p0Register),
        )
        return
    }
    val capturedCandidate = requireExactlyOne(
        "NewX captured inline-action renderer candidate",
        capturedCandidates.filter { candidate -> candidate.method == rendererMethod },
    )
    val mutableRenderer = requireExactlyOne(
        "NewX mutable captured inline-action renderer",
        mutableClassDefBy(capturedCandidate.owner.type).methods.filter { method ->
            method.name == capturedCandidate.method.name &&
                method.parameterTypes == capturedCandidate.method.parameterTypes &&
                method.returnType == capturedCandidate.method.returnType
        },
    )
    val capturedRenderer = resolveCapturedInlineActionEntryRenderer(
        mutableRenderer,
        capturedCandidate.owner,
        rendererModels,
    )
    val iconSizeReadIndex = capturedRenderer.iconSizeReadIndex
        ?: throw PatchException("NewX captured inline-action icon-size read was not resolved")
    val iconSizeRegister = capturedRenderer.iconSizeRegister
        ?: throw PatchException("NewX captured inline-action icon-size register was not resolved")
    val hookIndex = iconSizeReadIndex + 1
    if (!capturedRenderer.method.hasOnlyFallThroughInto(hookIndex)) {
        throw PatchException(
            "NewX captured inline-action size hook has an unproven incoming edge: " +
                "${capturedRenderer.method} at $hookIndex",
        )
    }
    capturedRenderer.method.insertMarkIconSizeHook(
        index = hookIndex,
        actionRegister = capturedRenderer.entryRegister,
        sizeRegister = iconSizeRegister,
        actionTypeField = entryModels.actionTypeField,
        twitterShareActionField = entryModels.twitterShareField,
        excludedRegisters = listOf(capturedRenderer.method.p0Register),
    )
}

context(BytecodePatchContext)
private fun resolveInlineIconSizeHook(
    method: com.android.tools.smali.dexlib2.iface.Method,
): Pair<Int, Int> {
    if ("F" in method.parameterTypes.map(CharSequence::toString)) {
        return 0 to (method.p0Register + method.firstParameterSlot("F"))
    }
    val sizingClass = requireExactlyOne(
        "NewX inline-action sizing parameter in $method",
        method.parameterTypes.mapNotNull { type -> classDefByOrNull(type.toString()) }
            .filter { classDef ->
                classDef.methods.any { candidate ->
                    candidate.name == "toString" && candidate.parameterTypes.isEmpty() &&
                        candidate.implementation?.instructions?.mapNotNull {
                            it.getReference<StringReference>()?.string
                        }?.containsAll(listOf("InlineActionSizing(counterFontSize=", ", iconSize=")) == true
                }
            },
    )
    val sizeField = requireExactlyOne(
        "NewX inline-action sizing float field in ${sizingClass.type}",
        sizingClass.fields.filter { it.type == "F" && !AccessFlags.STATIC.isSet(it.accessFlags) },
    )
    val getter = requireExactlyOne(
        "NewX inline-action icon-size getter in ${sizingClass.type}",
        sizingClass.methods.filter { candidate ->
            val code = candidate.implementation?.instructions?.toList().orEmpty()
            val read = code.getOrNull(0) as? TwoRegisterInstruction
            val returned = code.getOrNull(1) as? OneRegisterInstruction
            candidate.parameterTypes.isEmpty() && candidate.returnType == "F" &&
                !AccessFlags.STATIC.isSet(candidate.accessFlags) && code.size == 2 &&
                code[0].opcode == Opcode.IGET && code[1].opcode == Opcode.RETURN &&
                code[0].getReference<FieldReference>()?.toString() == sizeField.toString() &&
                read != null && read.registerB == candidate.implementation!!.registerCount - 1 &&
                read.registerA == returned?.registerA
        },
    )
    val instructions = method.implementation!!.instructions.toList()
    val sizingRegister = method.p0Register + method.firstParameterSlot(sizingClass.type)
    val callIndex = requireExactlyOne(
        "NewX inline-action icon-size getter call in $method",
        instructions.mapIndexedNotNull { index, instruction ->
            index.takeIf {
                instruction.opcode in setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE) &&
                    instruction.getReference<MethodReference>()?.toString() == getter.toString() &&
                    instruction.registersUsed.singleOrNull() == sizingRegister
            }
        },
    )
    if (instructions.take(callIndex).any { instruction ->
            if (!instruction.opcode.setsRegister()) return@any false
            val destination = (instruction as OneRegisterInstruction).registerA
            listOf(method.p0Register, sizingRegister).any { register ->
                destination == register || (instruction.opcode.setsWideRegister() && destination + 1 == register)
            }
        }
    ) throw PatchException("NewX inline-action sizing or entry parameter overwritten before $getter in $method")
    val result = instructions.getOrNull(callIndex + 1)
    if (result?.opcode != Opcode.MOVE_RESULT) {
        throw PatchException("NewX inline-action size getter has no float result in $method")
    }
    return (callIndex + 2) to (result as OneRegisterInstruction).registerA
}

private fun app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.insertMarkIconSizeHook(
    index: Int,
    actionRegister: Int,
    sizeRegister: Int,
    actionTypeField: FieldReference,
    twitterShareActionField: FieldReference,
    excludedRegisters: Collection<Int> = emptyList(),
) {
    if (actionTypeField.type != twitterShareActionField.definingClass) {
        throw PatchException(
            "NewX inline-action type and TwitterShare field disagree: " +
                "$actionTypeField vs $twitterShareActionField",
        )
    }
    insertHook(
        index,
        excludedRegisters = excludedRegisters + actionRegister + sizeRegister,
        relocateBranchTargets = false,
    ) {
        val actionTypeRegister = scratchRegister(RegisterLimit.FOUR_BIT)
        val twitterShareRegister = scratchRegister(RegisterLimit.FOUR_BIT)
        move(actionTypeRegister, actionRegister, OBJECT_DESCRIPTOR)
        iget(actionTypeRegister, actionTypeRegister, actionTypeField)
        sget(twitterShareRegister, twitterShareActionField)
        ifNe(actionTypeRegister, twitterShareRegister, Target.Local(SKIP_MARK_ICON_SIZE_LABEL))

        val entryRegister = scratchRegister(RegisterLimit.BYTE)
        val workRegister = scratchRegister(RegisterLimit.BYTE)
        move(entryRegister, actionRegister, OBJECT_DESCRIPTOR)
        move(workRegister, sizeRegister, "F")
        invokeStatic(
            methodReference("$INLINE_EXT->markIconSize(Ljava/lang/Object;F)F"),
            entryRegister,
            workRegister,
        )
        moveResult(workRegister, "F")
        move(sizeRegister, workRegister, "F")
        label(SKIP_MARK_ICON_SIZE_LABEL)
    }
}

// ---------------------------------------------------------------------------
// Icon lambda: swap the share icon for the copy icon when the size is marked
// ---------------------------------------------------------------------------

context(BytecodePatchContext)
private fun resolveDrawableField(resourceName: String): FieldReference {
    val drawableId = resourceId(ResourceType.DRAWABLE, resourceName)
    val fields = Fingerprint(
        name = "<clinit>",
        returnType = "V",
        parameters = emptyList(),
        filters = listOf(literal(drawableId)),
    ).matchAll().map { match ->
        val literalIndex = requireExactlyOne(
            "NewX $resourceName drawable resource literal in ${match.originalMethod}",
            match.instructionMatches,
        ).index
        val store = requireExactlyOne(
            "NewX $resourceName drawable field store after ${match.originalMethod}",
            match.method.instructions
                .drop(literalIndex + 1)
                .take(4)
                .filter { instruction -> instruction.opcode == Opcode.SPUT_OBJECT },
        )
        store.getReference<FieldReference>()
            ?: throw PatchException("NewX $resourceName drawable field store has no field reference: $store")
    }.distinctBy(FieldReference::toString)
    return requireExactlyOne("NewX $resourceName icon field", fields)
}

context(BytecodePatchContext)
private fun patchInlineIconLambda() {
    val shareIconField = resolveDrawableField("ic_vector_share")
    val copyIconField = resolveDrawableField("ic_vector_copy_stroke")
    if (shareIconField.type != copyIconField.type) {
        throw PatchException("NewX inline icon types differ")
    }
    val iconRenderer = requireOneMatch(
        "NewX TwitterShare icon lambda",
        Fingerprint(
            filters = listOf(
                fieldAccess(opcode = Opcode.IGET, definingClass = "this", type = "F"),
                fieldAccess(opcode = Opcode.SGET_OBJECT, reference = shareIconField),
            ),
        ).matchAll(),
    )
    iconRenderer.method.apply {
        val iconAccess = requireExactlyOne(
            "NewX TwitterShare icon access",
            iconRenderer.instructionMatches.filter { match ->
                match.instruction.opcode == Opcode.SGET_OBJECT &&
                    match.instruction.getReference<FieldReference>()?.toString() ==
                    shareIconField.toString()
            },
        )
        // Co-application tolerance: Piko's download button hooks this same
        // lambda in its execute phase and inserts its own selectIcon block,
        // which reads the SAME size field a second time (after the icon
        // access). Count distinct fields, not instructions: only the field
        // identity is needed here, and the branch-local access below still
        // has to be exactly one.
        val sizeField = requireExactlyOne(
            "NewX TwitterShare icon size field",
            iconRenderer.instructionMatches
                .filter { match ->
                    val field = match.instruction.getReference<FieldReference>()
                    match.instruction.opcode == Opcode.IGET && field?.type == "F"
                }
                .mapNotNull { match -> match.instruction.getReference<FieldReference>() }
                .distinctBy { field -> field.toString() },
        )
        val branchStart = instructions
            .subList(0, iconAccess.index)
            .indexOfLast { instruction -> instruction.opcode == Opcode.RETURN_OBJECT } + 1
        val branchLocalSizeAccesses = instructions.mapIndexedNotNull { index, instruction ->
            index.takeIf {
                index in branchStart until iconAccess.index &&
                    instruction.opcode == Opcode.IGET &&
                    instruction.getReference<FieldReference>()?.toString() == sizeField.toString()
            }?.let { accessIndex -> accessIndex to instruction }
        }
        val packedSwitchIndexes = instructions.mapIndexedNotNull { index, instruction ->
            index.takeIf {
                index < iconAccess.index && instruction.opcode == Opcode.PACKED_SWITCH
            }
        }
        val preSwitchHoistedSizeAccesses = packedSwitchIndexes.flatMap { switchIndex ->
            instructions.mapIndexedNotNull { index, instruction ->
                index.takeIf {
                    index < switchIndex &&
                        instruction.opcode == Opcode.IGET &&
                        instruction.getReference<FieldReference>()?.toString() == sizeField.toString()
                }?.let { accessIndex -> accessIndex to instruction }
            }
        }
        val sizeAccess = requireExactlyOne(
            "NewX TwitterShare branch-local or pre-switch-hoisted size access",
            branchLocalSizeAccesses + preSwitchHoistedSizeAccesses,
        )
        val sizeRegister = (sizeAccess.second as? OneRegisterInstruction)?.registerA
            ?: throw PatchException("NewX share icon size access has no register")
        val sizeModifierCalls = instructions.mapIndexedNotNull { index, instruction ->
            if (index in branchStart until iconAccess.index &&
                instruction.opcode == Opcode.INVOKE_STATIC
            ) {
                val methodRef = instruction.getReference<MethodReference>()
                if (methodRef?.parameterTypes?.map { it.toString() } ==
                    listOf(COMPOSE_MODIFIER_DESCRIPTOR, "F") &&
                    methodRef.returnType == COMPOSE_MODIFIER_DESCRIPTOR
                ) {
                    index to instruction
                } else null
            } else null
        }
        val sizeModifierCall = requireExactlyOne(
            "NewX TwitterShare branch size modifier call",
            sizeModifierCalls,
        )
        val iconRegister = (iconAccess.instruction as? OneRegisterInstruction)?.registerA
            ?: throw PatchException("NewX TwitterShare icon access has no register")
        val overwrittenReceiver = instructions.take(iconAccess.index).mapIndexedNotNull { index, instruction ->
            if (!instruction.opcode.setsRegister()) return@mapIndexedNotNull null
            val destination = (instruction as? OneRegisterInstruction)?.registerA
                ?: return@mapIndexedNotNull null
            if (destination == p0Register ||
                (instruction.opcode.setsWideRegister() && destination + 1 == p0Register)
            ) {
                index
            } else {
                null
            }
        }
        if (overwrittenReceiver.isNotEmpty()) {
            throw PatchException(
                "NewX TwitterShare icon lambda receiver is overwritten before icon selection " +
                    "in $this at ${overwrittenReceiver.joinToString()}",
            )
        }
        val parameterRegisterStart = p0Register
        val parameterRegisters = parameterRegisterStart until
            (parameterRegisterStart + numberOfParameterRegisters)

        // Co-application ordering: when Piko's download button is present, its
        // selectIcon block already sits right after the icon access (inserted
        // in its execute phase). Its selector is a binary sign check, so it
        // claims ANY negative size — including this bundle's marked copy
        // entry — and if it runs last, the copy entry renders as a download
        // icon (the v0.2.2 on-device bug). Insert this bundle's selector
        // AFTER Piko's block (past its move-result) so it has the last word:
        // it restores the copy icon only for the copy-marked size (sign +
        // lowest mantissa bit, see InlineCopyButton.markIconSize) and passes
        // Piko's own result through untouched for Piko's plain-negative mark.
        // Solo, no foreign selector exists and the stock anchor is used.
        val foreignSelectIndex = instructions.mapIndexedNotNull { index, instruction ->
            if (index <= iconAccess.index || instruction.opcode != Opcode.INVOKE_STATIC) {
                return@mapIndexedNotNull null
            }
            val ref = instruction.getReference<MethodReference>() ?: return@mapIndexedNotNull null
            index.takeIf {
                ref.name == "selectIcon" &&
                    ref.definingClass != INLINE_EXT &&
                    ref.parameterTypes.map { it.toString() } ==
                    listOf("Ljava/lang/Object;", "F", "Ljava/lang/Object;") &&
                    ref.returnType == "Ljava/lang/Object;"
            }
        }.firstOrNull()
        val selectInsertIndex = if (foreignSelectIndex == null) {
            iconAccess.index + 1
        } else {
            // Land just past the foreign selector's move-result; its trailing
            // check-cast then applies to this bundle's result as well (same
            // icon type), and this block adds its own check-cast regardless.
            val moveResultIndex = (foreignSelectIndex + 1..minOf(foreignSelectIndex + 3, instructions.size - 1))
                .firstOrNull { instructions[it].opcode == Opcode.MOVE_RESULT_OBJECT }
                ?: throw PatchException(
                    "NewX foreign selectIcon at $foreignSelectIndex in $this has no move-result to chain after",
                )
            moveResultIndex + 1
        }

        // Mutate from the higher index first so the earlier size-modifier index remains valid.
        insertHook(
            index = selectInsertIndex,
            excludedRegisters = parameterRegisters + iconRegister,
            relocateBranchTargets = false,
        ) {
            val receiverRegister = scratchRegister(RegisterLimit.FOUR_BIT)
            val markedSizeRegister = scratchRegister(RegisterLimit.FOUR_BIT)
            val copyIconRegister = scratchRegister()
            move(receiverRegister, p0Register, OBJECT_DESCRIPTOR)
            iget(markedSizeRegister, receiverRegister, sizeField)
            sget(copyIconRegister, copyIconField)
            invokeStatic(
                methodReference("$INLINE_EXT->selectIcon(Ljava/lang/Object;FLjava/lang/Object;)Ljava/lang/Object;"),
                iconRegister,
                markedSizeRegister,
                copyIconRegister,
            )
            moveResult(iconRegister, OBJECT_DESCRIPTOR)
            checkCast(iconRegister, shareIconField.type)
        }

        insertHook(
            index = sizeModifierCall.first,
            excludedRegisters = parameterRegisters + sizeRegister,
            relocateBranchTargets = false,
        ) {
            val displaySizeRegister = scratchRegister()
            move(displaySizeRegister, sizeRegister, "F")
            invokeStatic(
                methodReference("Ljava/lang/Math;->abs(F)F"),
                displaySizeRegister,
            )
            moveResult(displaySizeRegister, "F")
            move(sizeRegister, displaySizeRegister, "F")
        }
    }
}

// ---------------------------------------------------------------------------
// Event handler: intercept taps on the copy entry at the head
// ---------------------------------------------------------------------------

context(BytecodePatchContext)
private fun patchInlineEventHandler(entryModels: InlineEntryModels, barModels: InlineBarModels) {
    val inlineEventHandler = requireOneMatch(
        "NewX inline-action event handler",
        Fingerprint(
            definingClass = barModels.barDescriptor,
            returnType = "V",
            filters = listOf(
                fieldAccess(
                    opcode = Opcode.IGET_OBJECT,
                    definingClass = entryModels.entryDescriptor,
                    type = entryModels.postActionTypeDescriptor,
                ),
                methodCall(
                    definingClass = "Ljava/lang/Enum;",
                    name = "ordinal",
                    parameters = emptyList(),
                    returnType = "I",
                ),
            ),
        ).matchAll(),
    )
    val longPressEventType = resolveInlineLongPressEventType(
        inlineEventHandler.originalMethod,
        entryModels.entryDescriptor,
    )
    inlineEventHandler.method.apply {
        if (!AccessFlags.STATIC.isSet(accessFlags)) {
            throw PatchException("NewX inline-action event handler is not static: $this")
        }
        if (parameterTypes.firstOrNull().toString() != barModels.barDescriptor) {
            throw PatchException("NewX inline event handler presenter parameter changed: $this")
        }
        val eventParameter = parameterTypes.dropLast(1).sumOf { type ->
            if (type.toString() == "J" || type.toString() == "D") 2 else 1
        }
        val eventRegister = p0Register + eventParameter
        insertHook(0, relocateBranchTargets = false) {
            val presenterRegister = scratchRegister()
            val eventScratch = scratchRegister()
            move(presenterRegister, p0Register, OBJECT_DESCRIPTOR)
            move(eventScratch, eventRegister, OBJECT_DESCRIPTOR)
            val longPressRegister = scratchRegister()
            instanceOf(longPressRegister, eventScratch, longPressEventType)
            invokeStatic(
                methodReference("$INLINE_EXT->handleEvent(Ljava/lang/Object;Ljava/lang/Object;Z)Z"),
                presenterRegister,
                eventScratch,
                longPressRegister,
            )
            moveResult(presenterRegister, "Z")
            ifEqz(presenterRegister, Target.Original)
            returnVoid()
        }
    }
}

/**
 * Resolves the semantic long-click event type consumed by the inline handler
 * (port of Piko's resolver): the event class whose toString is anchored on
 * "DidLongClickInlineActionEntry(inlineActionEntry=" and whose payload field
 * is the action entry.
 */
context(BytecodePatchContext)
private fun resolveInlineLongPressEventType(
    eventHandler: Method,
    actionEntryDescriptor: String,
): String {
    val eventInterfaceDescriptor = eventHandler.parameterTypes.lastOrNull()?.toString()
        ?: throw PatchException("NewX inline event handler has no event parameter: $eventHandler")
    val instructions = eventHandler.implementation?.instructions?.toList()
        ?: throw PatchException("NewX inline event handler has no implementation: $eventHandler")

    val label = "DidLongClickInlineActionEntry(inlineActionEntry="
    val eventClass = requireExactlyOne(
        "NewX inline-action long-press event",
        instructions
            .filter { instruction -> instruction.opcode == Opcode.INSTANCE_OF }
            .mapNotNull { instruction -> instruction.getReference<TypeReference>()?.type }
            .distinct()
            .mapNotNull { type -> classDefByOrNull(type) }
            .filter { candidate ->
                candidate.interfaces.any { descriptor ->
                    descriptor.toString() == eventInterfaceDescriptor
                } && candidate.methods.any { method ->
                    method.name == "toString" && method.parameterTypes.isEmpty() &&
                        method.implementation?.instructions?.any {
                            it.getReference<StringReference>()?.string == label
                        } == true
                }
            },
    )
    val description = requireExactlyOne(
        "NewX inline-action long-press description in ${eventClass.type}",
        eventClass.methods.filter { it.name == "toString" && it.parameterTypes.isEmpty() },
    )
    val entryField = description.fieldForToStringLabel(label)
    if (entryField.type != actionEntryDescriptor) {
        throw PatchException("NewX inline-action long-press payload is not an action entry: $entryField")
    }
    val entryGetters = eventClass.methods.filter { method ->
        val code = method.implementation?.instructions?.toList().orEmpty()
        val read = code.getOrNull(0) as? TwoRegisterInstruction
        val returned = code.getOrNull(1) as? OneRegisterInstruction
        method.parameterTypes.isEmpty() && method.returnType == actionEntryDescriptor &&
            !AccessFlags.STATIC.isSet(method.accessFlags) && code.size == 2 &&
            code[0].opcode == Opcode.IGET_OBJECT && code[1].opcode == Opcode.RETURN_OBJECT &&
            code[0].getReference<FieldReference>()?.toString() == entryField.toString() &&
            read != null && read.registerB == method.implementation!!.registerCount - 1 &&
            read.registerA == returned?.registerA
    }.map { it.toString() }.toSet()
    requireExactlyOne(
        "NewX inline-action long-press payload consumer in $eventHandler",
        instructions.mapIndexedNotNull { index, instruction ->
            index.takeIf {
                (instruction.opcode == Opcode.IGET_OBJECT &&
                    instruction.getReference<FieldReference>()?.toString() == entryField.toString()) ||
                    (instruction.opcode in setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE) &&
                        instruction.getReference<MethodReference>()?.toString() in entryGetters)
            }
        },
    )
    val constructor = requireExactlyOne(
        "NewX inline-action long-press constructor in ${eventClass.type}",
        eventClass.methods.filter {
            it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf(actionEntryDescriptor)
        },
    )
    requireOneMatch(
        "NewX inline-action long-press callback dispatch",
        Fingerprint(
            filters = listOf(
                newInstance(constructor.definingClass),
                methodCall(reference = constructor, location = MatchAfterImmediately()),
                methodCall(
                    smali = "Lkotlin/jvm/functions/Function1;->invoke(Ljava/lang/Object;)Ljava/lang/Object;",
                    location = MatchAfterImmediately(),
                ),
            ),
        ).matchAll(),
    )
    return eventClass.type
}
