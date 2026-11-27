package local.muse.newx

import app.crimera.bytecode.Target
import app.crimera.bytecode.insertHook
import app.crimera.bytecode.methodReference
import app.crimera.patches.common.isAssignableTo
import app.crimera.patches.common.isObjectDescriptor
import app.crimera.patches.common.parameterDescriptors
import app.crimera.patches.common.requireExactlyOne
import app.crimera.patches.common.semantic.fieldForToStringLabel
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.numberOfParameterRegisters
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Port of Piko's PostOptionsPatch finalize machinery (newXPostOptionsPatch.finalize),
 * adapted for a standalone bundle:
 *
 *  - The contribution list is not collected through cross-patch dependsOn registration
 *    into a shared patch; the two patches in this bundle register into
 *    PostOptionContributionIndex and whichever of their finalize blocks runs first
 *    performs the injections below with the full snapshot.
 *  - The Application.onCreate hook that initialised Piko's NewXUtils is gone: the
 *    standalone handlers take their Context from the presenter, so no init hook is
 *    needed.
 *  - resolvedNewXInlineActionModels() is replaced by resolvePostActionTypeDescriptor(),
 *    a local toString-label resolution of the InlineActionEntry model (the same anchor
 *    Piko's resolver uses), pinned to the 12.43 result.
 *  - Morphe API level is the Desktop 2.1.1 jar: classDefBy/mutableClassDefBy are
 *    BytecodePatchContext members there, so the helpers below carry the context as a
 *    context receiver instead of importing top-level functions.
 */

private const val POST_OPTIONS_STATE_PREFIX = "PostOptionsState(showOptionsDialog="
private const val POST_OPTIONS_LIST_PREFIX = ", options="
private const val CONTEXT = "Landroid/content/Context;"
private const val OBJECT_DESCRIPTOR = "Ljava/lang/Object;"
private const val LIST_DESCRIPTOR = "Ljava/util/List;"
private const val STRING_DESCRIPTOR = "Ljava/lang/String;"
private const val ADD_OPTION_SIGNATURE = "addOption($LIST_DESCRIPTOR)$LIST_DESCRIPTOR"
private const val LABEL_FOR_SIGNATURE = "labelFor($OBJECT_DESCRIPTOR$OBJECT_DESCRIPTOR)$STRING_DESCRIPTOR"
private const val USES_ICON_SIGNATURE = "usesIcon($OBJECT_DESCRIPTOR)Z"
private const val HANDLE_OPTION_ACTION_SIGNATURE = "handleOptionAction($OBJECT_DESCRIPTOR$OBJECT_DESCRIPTOR)Z"

/**
 * Piko's customize-post-menu filter, referenced by descriptor only: when Piko's own
 * bundle is applied alongside, its filter call can appear in the state constructor and
 * the parameter trace below must recognise it. When Piko is absent the call simply
 * never appears and the trace treats every call as non-filter.
 */
private const val POST_OPTIONS_FILTER_DESCRIPTOR = "Lapp/morphe/extension/newx/misc/PostOptionsFilter;"

/** Post action type of X 12.43.0-prod.01, verified against classes6.dex. */
private const val EXPECTED_POST_ACTION_TYPE_DESCRIPTOR = "Lcom/x/models/x5;"

/** NewX icon resource initialization emits the field assignment within this small block. */
private const val ICON_FIELD_INITIALIZATION_WINDOW = 4

private val OBJECT_MOVE_OPCODES =
    setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

private object PostOptionsStateFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    filters =
        listOf(
            app.morphe.patcher.string(POST_OPTIONS_STATE_PREFIX),
            app.morphe.patcher.string(POST_OPTIONS_LIST_PREFIX),
        ),
)

private object PostOptionsPresenterFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    filters =
        listOf(
            fieldAccess(
                opcode = Opcode.IPUT_OBJECT,
                definingClass = "this",
                type = "Lcom/x/models/timelines/items/",
            ),
            fieldAccess(
                opcode = Opcode.IPUT_OBJECT,
                definingClass = "this",
                type = CONTEXT,
            ),
            fieldAccess(
                opcode = Opcode.IPUT_OBJECT,
                definingClass = "this",
                type = "Lkotlinx/coroutines/channels/",
            ),
        ),
)

private object InlineActionEntryModelFingerprint : Fingerprint(
    definingClass = "Lcom/x/models/",
    name = "toString",
    returnType = STRING_DESCRIPTOR,
    parameters = emptyList(),
    filters = listOf(
        app.morphe.patcher.string("InlineActionEntry(actionType="),
        app.morphe.patcher.string(", count="),
        app.morphe.patcher.string(", isEnabled="),
    ),
)

context(BytecodePatchContext)
internal fun injectPostOptions(contributions: List<PostOptionContribution>) {
    validateActionCarriers(contributions)
    injectOptionList(contributions)
    injectLabelsAndIcons(contributions)
    injectActionHandlers(contributions)
}

/**
 * Own resolution of the post action type, replacing Piko's
 * resolvedNewXInlineActionModels(): the InlineActionEntry model's toString anchor
 * gives the actionType field, whose type is the PostActionType enum. This bundle is
 * pinned to 12.43.0-prod.01, so any other result fails the patch loudly.
 */
context(BytecodePatchContext)
private fun resolvePostActionTypeDescriptor(): String {
    val matches = InlineActionEntryModelFingerprint.matchAll()
    if (matches.size != 1) {
        throw PatchException(
            "Expected one NewX inline-action entry model, found ${matches.size}: " +
                matches.joinToString { it.originalMethod.toString() },
        )
    }
    val actionTypeField = matches.single().fieldForToStringLabel("InlineActionEntry(actionType=")
    if (!actionTypeField.type.startsWith("L")) {
        throw PatchException("NewX inline-action type field is not an object: $actionTypeField")
    }
    if (actionTypeField.type != EXPECTED_POST_ACTION_TYPE_DESCRIPTOR) {
        throw PatchException(
            "NewX post action type changed: expected $EXPECTED_POST_ACTION_TYPE_DESCRIPTOR, " +
                "resolved ${actionTypeField.type}",
        )
    }
    return actionTypeField.type
}

context(BytecodePatchContext)
private fun validateActionCarriers(contributions: List<PostOptionContribution>) {
    val postActionType = resolvePostActionTypeDescriptor()
    val actionType = mutableClassDefBy(postActionType)
    val missing =
        contributions
            .map(PostOptionContribution::actionName)
            .distinct()
            .filter { actionName ->
                actionType.fields.none { field -> field.name == actionName && field.type == postActionType }
            }
    if (missing.isNotEmpty()) {
        throw PatchException("Missing NewX post-menu action carriers: ${missing.joinToString()}")
    }

    val duplicates =
        contributions
            .groupingBy(PostOptionContribution::actionName)
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
    if (duplicates.isNotEmpty()) {
        throw PatchException("Duplicate NewX post-menu action carriers: ${duplicates.joinToString()}")
    }
}

context(BytecodePatchContext)
private fun injectOptionList(contributions: List<PostOptionContribution>) {
    val state = resolvePostOptionsStateConstructor()
    val stateConstructor = state.match.method
    // Handler calls are chained on the options-list parameter, in registration order.
    val listRegister = stateConstructor.p0Register + state.optionsParameterOffset
    stateConstructor.insertHook(0, relocateBranchTargets = false) {
        contributions.forEach { contribution ->
            invokeStatic(
                methodReference("${contribution.handlerDescriptor}->$ADD_OPTION_SIGNATURE"),
                listRegister,
            )
            moveResult(listRegister, LIST_DESCRIPTOR)
        }
    }
}

internal data class PostOptionsStateConstructor(
    val match: Match,
    val optionsField: FieldReference,
    val optionsParameterOffset: Int,
)

context(BytecodePatchContext)
internal fun resolvePostOptionsStateConstructor(): PostOptionsStateConstructor {
    val stateMatch = requireSingleMatch("NewX post-options state", PostOptionsStateFingerprint.matchAll())
    val optionsField = stateMatch.fieldForToStringLabel(POST_OPTIONS_LIST_PREFIX)
    // The hooks replace this field's value with a List, not an arbitrary concrete collection.
    if (optionsField.type != LIST_DESCRIPTOR) {
        throw PatchException("NewX post-options field no longer accepts List: $optionsField")
    }
    val constructor = requireSingleMatch(
        "NewX post-options state constructor",
        Fingerprint(
            classFingerprint = PostOptionsStateFingerprint,
            name = "<init>",
            returnType = "V",
            filters = listOf(fieldAccess(opcode = Opcode.IPUT_OBJECT, reference = optionsField)),
        ).matchAll(),
    )
    return PostOptionsStateConstructor(
        constructor,
        optionsField,
        postOptionsParameterOffset(constructor.originalMethod, optionsField),
    )
}

/** Trace the actual options-field input; unrelated constructor parameters are not the hook ABI. */
internal fun postOptionsParameterOffset(method: Method, optionsField: FieldReference): Int {
    val code = method.instructions.toList()
    val storeIndex = postOptionsListStoreIndex(method, optionsField)
    if (method.implementation!!.tryBlocks.isNotEmpty() || code.take(storeIndex).any { it is OffsetInstruction }) {
        throw PatchException("NewX post-options constructor has non-linear options initialization: $method")
    }
    val receiver = method.implementation!!.registerCount - method.numberOfParameterRegisters
    val origins = mutableMapOf(receiver to 0)
    var offset = 1
    method.parameterTypes.forEach { type ->
        if (type.toString() == LIST_DESCRIPTOR) origins[receiver + offset] = offset
        offset += if (type.toString() in setOf("J", "D")) 2 else 1
    }
    var filteredInput: Int? = null
    for (instruction in code.take(storeIndex)) {
        val precedingFilterInput = filteredInput
        filteredInput = null
        val reference = instruction.getReference<MethodReference>()
        if (reference != null && instruction.opcode in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)) {
            // Customize-post-menu runs before contribution finalization. Its own filter replaces
            // the list but preserves which constructor parameter supplies it; arbitrary calls do not.
            filteredInput = if (reference.toString() ==
                "$POST_OPTIONS_FILTER_DESCRIPTOR->filter(Ljava/util/List;Ljava/util/Set;)Ljava/util/List;"
            ) origins[instruction.registersUsed.first()] else null
            continue
        }
        val destination = instruction as? OneRegisterInstruction ?: continue
        if (!instruction.opcode.setsRegister()) continue
        val origin = when {
            instruction.opcode in OBJECT_MOVE_OPCODES -> origins[(instruction as TwoRegisterInstruction).registerB]
            instruction.opcode == Opcode.MOVE_RESULT_OBJECT -> precedingFilterInput
            else -> null
        }
        origins.remove(destination.registerA)
        if (instruction.opcode.setsWideRegister()) origins.remove(destination.registerA + 1)
        if (origin != null) origins[destination.registerA] = origin
    }
    val store = code[storeIndex] as TwoRegisterInstruction
    val parameter = origins[store.registerA]
    if (origins[store.registerB] != 0 || parameter == null || parameter == 0) {
        throw PatchException("NewX post-options field is not initialized from a List parameter: $method")
    }
    return parameter
}

internal fun postOptionsListStoreIndex(method: Method, optionsField: FieldReference): Int =
    requireExactlyOne(
        "NewX post-options list store in $method",
        method.instructions.mapIndexedNotNull { index, instruction ->
            index.takeIf {
                instruction.opcode == Opcode.IPUT_OBJECT &&
                    instruction.getReference<FieldReference>()?.toString() == optionsField.toString()
            }
        },
    )

internal data class PostOptionsFeedbackLookup(
    val callIndex: Int,
    val resultIndex: Int,
    val actionRegister: Int,
    val labelRegister: Int,
)

context(BytecodePatchContext)
internal fun postOptionsFeedbackLookups(
    method: Method,
    feedbackField: FieldReference,
    isMap: (String) -> Boolean,
): List<PostOptionsFeedbackLookup> {
    val code = method.instructions.toList()
    return code.indices.mapNotNull { fieldIndex ->
        val read = code[fieldIndex] as? TwoRegisterInstruction ?: return@mapNotNull null
        if (read.opcode != Opcode.IGET_OBJECT ||
            code[fieldIndex].getReference<FieldReference>()?.toString() != feedbackField.toString()
        ) return@mapNotNull null
        val call = code.getOrNull(fieldIndex + 1) ?: return@mapNotNull null
        val reference = call.getReference<MethodReference>() ?: return@mapNotNull null
        if (call.opcode !in setOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE,
                Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE) ||
            reference.name != "get" || !isMap(reference.definingClass) ||
            reference.parameterTypes.map(CharSequence::toString) != listOf(OBJECT_DESCRIPTOR) ||
            reference.returnType != OBJECT_DESCRIPTOR
        ) return@mapNotNull null
        val arguments = call.registersUsed
        if (arguments.size != 2 || arguments[0] != read.registerA) return@mapNotNull null
        val result = code.getOrNull(fieldIndex + 2) as? OneRegisterInstruction ?: return@mapNotNull null
        if (result.opcode != Opcode.MOVE_RESULT_OBJECT) return@mapNotNull null
        PostOptionsFeedbackLookup(fieldIndex + 1, fieldIndex + 2, arguments[1], result.registerA)
    }
}

context(BytecodePatchContext)
private fun injectLabelsAndIcons(contributions: List<PostOptionContribution>) {
    val state = requireSingleMatch("NewX post-options state", PostOptionsStateFingerprint.matchAll())
    val feedbackField = state.fieldForToStringLabel(", feedbackPrompts=")
    if (!isAssignableTo(feedbackField.type, "Ljava/util/Map;")) {
        throw PatchException("NewX post-options feedback prompts are not a Map: $feedbackField")
    }
    val isMap = { type: String -> isAssignableTo(type, "Ljava/util/Map;") }
    val rendererFingerprint =
            Fingerprint(
                filters =
                    listOf(
                        fieldAccess(
                            opcode = Opcode.IGET_OBJECT,
                            reference = feedbackField,
                        ),
                    ),
                custom = { method, _ -> postOptionsFeedbackLookups(method, feedbackField, isMap).size == 1 },
            )
    val originalRenderer = requireSingleMatch(
        "NewX post-options label renderer",
        rendererFingerprint.matchAll(),
    ).method
    val iconCheckRegister = originalRenderer.implementation?.registerCount
        ?: throw PatchException("NewX post-options label renderer has no implementation")
    if (iconCheckRegister > 255) {
        throw PatchException("NewX post-options icon check requires a byte-encodable register")
    }
    // cloneParameters copies the arguments into their old slots. The new parameter slots are
    // unused by the original body, so this hook does not depend on finding a dead live-frame local.
    originalRenderer.cloneParameters()
    rendererFingerprint.clearMatch()
    val renderer = requireSingleMatch(
        "NewX post-options label renderer after parameter preservation",
        rendererFingerprint.matchAll(),
    )
    val lookup = requireExactlyOne("NewX post-options feedback lookup",
        postOptionsFeedbackLookups(renderer.method, feedbackField, isMap))
    val actionRegister = lookup.actionRegister

    fun findIconAssignments(range: IntRange): List<IconAssignment> =
        renderer.method.instructions.withIndex().mapNotNull { (index, instruction) ->
            if (instruction.opcode !in OBJECT_MOVE_OPCODES ||
                index !in range ||
                renderer.method.instructions.getOrNull(index + 1)?.opcode !in
                setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)
            ) {
                return@mapNotNull null
            }
            val assignment = instruction as? TwoRegisterInstruction
                ?: throw PatchException(
                    "NewX post-options icon assignment has no registers at instruction $index",
                )
            val field = renderer.method.instructions.resolveFieldReadOrNull(
                register = assignment.registerB,
                untilIndex = index,
            )?.takeIf { it.type.startsWith("Lcom/x/icons/") } ?: return@mapNotNull null
            IconAssignment(index = index, instruction = assignment, field = field)
        }

    val beforeLookup = findIconAssignments(0 until lookup.callIndex)
    val afterLookup = findIconAssignments((lookup.resultIndex + 1) until renderer.method.instructions.size)
    if (beforeLookup.isNotEmpty() == afterLookup.isNotEmpty()) {
        throw PatchException(
            "Expected NewX post-options icon assignments exclusively before or after lookup " +
                "(before=${beforeLookup.size}, after=${afterLookup.size})",
        )
    }
    val iconAssignments = beforeLookup.ifEmpty { afterLookup }
    val iconResultRegister =
        requireExactlyOne(
            label = "NewX post-options icon result register",
            candidates = iconAssignments.map { assignment -> assignment.instruction.registerA }.distinct(),
        )
    val iconType =
        requireExactlyOne(
            label = "NewX post-options icon type",
            candidates = iconAssignments.map { assignment -> assignment.field.type }.distinct(),
        )

    val iconFields = contributions.associateWith { resolveIconField(it.iconResourceName, iconType) }

    fun app.crimera.bytecode.Block.emitLabelRewrites() {
        contributions.forEach { contribution ->
            invokeStatic(
                methodReference("${contribution.handlerDescriptor}->$LABEL_FOR_SIGNATURE"),
                actionRegister,
                lookup.labelRegister,
            )
            moveResult(lookup.labelRegister, STRING_DESCRIPTOR)
        }
    }

    fun app.crimera.bytecode.Block.emitIconRewrites() {
        // The icon checks share the reserved byte register; if-eqz and move-result both encode it.
        contributions.forEachIndexed { index, contribution ->
            if (index > 0) label(iconContinuationLabel(index))
            invokeStatic(
                methodReference("${contribution.handlerDescriptor}->$USES_ICON_SIGNATURE"),
                actionRegister,
            )
            moveResult(iconCheckRegister, "Z")
            ifEqz(
                iconCheckRegister,
                if (index == contributions.lastIndex) {
                    Target.Original
                } else {
                    Target.Local(iconContinuationLabel(index + 1))
                },
            )
            sget(iconResultRegister, iconFields.getValue(contribution))
        }
    }

    if (beforeLookup.isNotEmpty()) {
        // The label and icon rewrites land in front of the instruction that consumes the resolved
        // label, so they run with both the action and the original label live.
        renderer.method.insertHook(
            index = lookup.resultIndex + 1,
            excludedRegisters = listOf(actionRegister, lookup.labelRegister, iconResultRegister),
            // A branch into the label lookup must run the rewrite too, or the custom options keep
            // their upstream label and icon.
            relocateBranchTargets = true,
        ) {
            emitLabelRewrites()
            emitIconRewrites()
        }
    } else {
        val lastIconAssignmentIndex = afterLookup.maxOf(IconAssignment::index)
        val sheetRowCallIndex =
            requireExactlyOne(
                label = "NewX post-options sheet row constructor call",
                candidates =
                    renderer.method.instructions.withIndex().mapNotNull { (index, instruction) ->
                        if (index <= lastIconAssignmentIndex) return@mapNotNull null
                        if (instruction.opcode !in setOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE)) {
                            return@mapNotNull null
                        }
                        val reference = instruction.getReference<MethodReference>() ?: return@mapNotNull null
                        val parameters = reference.parameterTypes.map(CharSequence::toString)
                        index.takeIf {
                            reference.name == "<init>" &&
                                parameters.take(3) == listOf(OBJECT_DESCRIPTOR, iconType, STRING_DESCRIPTOR) &&
                                iconResultRegister in instruction.registersUsed
                        }
                    },
            )
        // Insert the icon hook first (higher index) so lookup.resultIndex + 1 stays valid.
        renderer.method.insertHook(
            index = sheetRowCallIndex,
            excludedRegisters =
                (renderer.method.instructions[sheetRowCallIndex].registersUsed +
                    listOf(actionRegister, iconResultRegister)).distinct(),
            relocateBranchTargets = true,
        ) {
            emitIconRewrites()
        }
        renderer.method.insertHook(
            index = lookup.resultIndex + 1,
            excludedRegisters = listOf(actionRegister, lookup.labelRegister),
            relocateBranchTargets = true,
        ) {
            emitLabelRewrites()
        }
    }
}

private data class IconAssignment(
    val index: Int,
    val instruction: TwoRegisterInstruction,
    val field: FieldReference,
)

/** Name of the label that starts the icon check of the contribution at [index]. */
private fun iconContinuationLabel(index: Int): String = "piko_newx_post_option_icon_$index"

context(BytecodePatchContext)
private fun injectActionHandlers(contributions: List<PostOptionContribution>) {
    val presenter = requireSingleMatch("NewX post-options presenter", PostOptionsPresenterFingerprint.matchAll())
    val eventHandlerClass =
        requireSingleMatch(
            "NewX post-options event handler class",
            Fingerprint(
                name = "<init>",
                returnType = "V",
                // The constructor stores the presenter in its field, which the instruction index finds.
                filters = listOf(fieldAccess(opcode = Opcode.IPUT_OBJECT, type = presenter.originalClassDef.type)),
                custom = { method, _ ->
                    val parameters = method.parameterDescriptors()
                    parameters.size == 6 &&
                        parameters.firstOrNull() == presenter.originalClassDef.type &&
                        parameters.drop(1).all(String::isObjectDescriptor)
                },
            ).matchAll(),
        )
    val eventHandler =
        requireSingleMatch(
            "NewX post-options event handler",
            Fingerprint(
                classFingerprint = Fingerprint(definingClass = eventHandlerClass.originalClassDef.type),
                parameters = listOf("Ljava/lang/Object;"),
                returnType = "Ljava/lang/Object;",
            ).matchAll(),
        )
    val presenterField =
        eventHandler.originalClassDef.fields.singleOrNull { it.type == presenter.originalClassDef.type }
            ?: throw PatchException("NewX post-options event handler has no unique presenter field")

    val ordinalCandidates = eventHandler.method.instructions.withIndex().filter { (_, instruction) ->
        val methodRef = instruction.getReference<MethodReference>() ?: return@filter false
        instruction.opcode == Opcode.INVOKE_VIRTUAL &&
            methodRef.definingClass == "Ljava/lang/Enum;" &&
            methodRef.name == "ordinal" &&
            methodRef.parameterTypes.isEmpty() &&
            methodRef.returnType == "I" &&
            instruction.registersUsed.size == 1
    }
    if (ordinalCandidates.size != 1) {
        throw PatchException(
            "Expected one NewX confirmed post-option Enum.ordinal, found " +
                "${ordinalCandidates.size}: ${ordinalCandidates.joinToString()}",
        )
    }
    val ordinalIndex = ordinalCandidates.single().index
    val ordinalInstruction = eventHandler.method.instructions[ordinalIndex]
    val clickActionRegister = ordinalInstruction.registersUsed.singleOrNull()
        ?: throw PatchException("NewX confirmed post-option action has no register")
    val unitField = resolveKotlinUnitField()
    val handler = eventHandler.method
    val receiverRegister = handler.p0Register

    handler.insertHook(
        index = ordinalIndex,
        excludedRegisters = listOf(clickActionRegister),
        // A branch that lands on the action dispatch must run the check too, or the custom
        // options fall through to the original handler and do nothing.
        relocateBranchTargets = true,
    ) {
        // One dead register carries the presenter, the Boolean result and the Unit return value.
        val checkRegister = scratchRegister()
        contributions.forEachIndexed { index, contribution ->
            if (index > 0) label(actionContinuationLabel(index))
            move(checkRegister, receiverRegister, OBJECT_DESCRIPTOR)
            iget(checkRegister, checkRegister, presenterField)
            invokeStatic(
                methodReference("${contribution.handlerDescriptor}->$HANDLE_OPTION_ACTION_SIGNATURE"),
                checkRegister,
                clickActionRegister,
            )
            moveResult(checkRegister, "Z")
            ifEqz(
                checkRegister,
                if (index == contributions.lastIndex) {
                    Target.Original
                } else {
                    Target.Local(actionContinuationLabel(index + 1))
                },
            )
            sget(checkRegister, unitField)
            returnObject(checkRegister)
        }
    }
}

/** Name of the label that starts the action check of the contribution at [index]. */
private fun actionContinuationLabel(index: Int): String = "piko_newx_post_option_action_$index"

context(BytecodePatchContext)
private fun resolveIconField(resourceName: String, iconType: String): FieldReference {
    val drawableId = resourceId(ResourceType.DRAWABLE, resourceName)
    val fields =
        Fingerprint(
            name = "<clinit>",
            returnType = "V",
            parameters = emptyList(),
            filters = listOf(literal(drawableId)),
        ).matchAll().mapNotNull { match ->
            val literalIndex = match.instructionMatches.single().index
            val fieldCandidates = match.method.instructions
                .drop(literalIndex + 1)
                .take(ICON_FIELD_INITIALIZATION_WINDOW)
                .mapNotNull { instruction ->
                    if (instruction.opcode != Opcode.SPUT_OBJECT) return@mapNotNull null
                    instruction.getReference<FieldReference>()?.takeIf { field -> field.type == iconType }
                }
            if (fieldCandidates.size != 1) {
                throw PatchException(
                    "Expected one $resourceName icon field after resource literal, found " +
                        "${fieldCandidates.size}: ${fieldCandidates.joinToString()}",
                )
            }
            fieldCandidates.single()
        }.distinctBy(FieldReference::toString)
    return requireExactlyOne("NewX $resourceName icon field", fields)
}

private fun List<Instruction>.resolveFieldRead(
    register: Int,
    untilIndex: Int,
): FieldReference {
    var trackedRegister = register
    for (index in untilIndex - 1 downTo 0) {
        val instruction = this[index]
        val field = instruction.getReference<FieldReference>()
        val destination = when (instruction.opcode) {
            Opcode.SGET_OBJECT -> (instruction as? OneRegisterInstruction)?.registerA
            Opcode.IGET_OBJECT -> (instruction as? TwoRegisterInstruction)?.registerA
            else -> null
        }
        if (destination == trackedRegister && field != null) return field

        if (instruction.opcode in OBJECT_MOVE_OPCODES) {
            val move = instruction as? TwoRegisterInstruction
                ?: throw PatchException("NewX post-options icon move has no registers at instruction $index")
            if (move.registerA == trackedRegister) {
                trackedRegister = move.registerB
                continue
            }
        }
        val writesTrackedRegister = when (instruction.opcode) {
            Opcode.SGET_OBJECT -> (instruction as? OneRegisterInstruction)?.registerA == trackedRegister
            Opcode.IGET_OBJECT,
            Opcode.MOVE_RESULT_OBJECT,
            -> (instruction as? OneRegisterInstruction)?.registerA == trackedRegister ||
                (instruction as? TwoRegisterInstruction)?.registerA == trackedRegister
            else -> false
        }
        if (writesTrackedRegister) break
    }
    throw PatchException(
        "NewX post-options icon assignment has no reaching field for v$register before instruction $untilIndex",
    )
}

private fun List<Instruction>.resolveFieldReadOrNull(
    register: Int,
    untilIndex: Int,
): FieldReference? =
    try {
        resolveFieldRead(register, untilIndex)
    } catch (_: PatchException) {
        null
    }

context(BytecodePatchContext)
private fun resolveKotlinUnitField(): FieldReference {
    val match =
        requireSingleMatch(
            "Kotlin Unit initializer",
            Fingerprint(
                definingClass = "Lkotlin/Unit;",
                name = "<clinit>",
                returnType = "V",
                parameters = emptyList(),
                filters =
                    listOf(
                        fieldAccess(
                            opcode = Opcode.SPUT_OBJECT,
                            definingClass = "Lkotlin/Unit;",
                            type = "Lkotlin/Unit;",
                        ),
                    ),
            ).matchAll(),
        )
    return match.instructionMatches.single().instruction.getReference<FieldReference>()
        ?: throw PatchException("Kotlin Unit singleton field was not found")
}

private fun requireSingleMatch(label: String, matches: Collection<Match>): Match {
    if (matches.size == 1) return matches.single()
    throw PatchException(
        "Expected one $label match, found ${matches.size}: " +
            matches.joinToString { it.originalMethod.toString() },
    )
}
