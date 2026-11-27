package local.muse.newx

import app.crimera.patches.common.requireExactlyOne
import app.crimera.patches.common.requireAtMostOne
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.registersUsed
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.ExceptionHandler
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.TryBlock
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod

private const val OBJECT_DESCRIPTOR = "Ljava/lang/Object;"
private const val SIZING_TO_STRING_PREFIX = "InlineActionSizing(counterFontSize="
private const val SIZING_ICON_SIZE_LABEL = ", iconSize="
private const val UNKNOWN_VALUE = -1
private const val PARAMETER_VALUE = -2

/*
 * Port of Piko's InlineActionRendererResolver.kt (captured-lambda renderer
 * resolution), adapted for the standalone bundle: the model triple is the
 * bundle's own InlineRendererModels and classDefByOrNull is Morphe 2.1.1's
 * global function. In 12.43.0-prod.01 the direct renderer
 * (Lcom/x/inlineactionbar/w;->d) only reads the entry's action type and hands
 * the entry to captured FunctionN lambdas; the lambda that reads the enabled
 * flag and calls the type-to-tint renderer is the one whose icon-size read
 * gets the markIconSize hook.
 */

internal data class InlineRendererModels(
    val entryDescriptor: String,
    val actionTypeField: FieldReference,
    val enabledField: FieldReference,
) {
    /** The PostActionType descriptor is the action-type field's type. */
    val postActionTypeDescriptor: String get() = actionTypeField.type
}


internal data class ResolvedCapturedInlineActionRenderer(
    val method: MutableMethod,
    val entryRegister: Int,
    val iconSizeReadIndex: Int?,
    val iconSizeRegister: Int?,
    val tintReadIndex: Int,
    val tintRegister: Int,
    val contentTintReadIndex: Int?,
    val contentTintRegister: Int?,
    val tintRenderer: MethodReference,
)

/** Recognizes the captured FunctionN implementation by its entry-field reads and renderer call. */
internal fun Method.isCapturedInlineActionEntryRenderer(
    owner: ClassDef,
    models: InlineRendererModels,
): Boolean {
    val parameters = parameterTypes.map(CharSequence::toString)
    if (AccessFlags.STATIC.isSet(accessFlags) || returnType != OBJECT_DESCRIPTOR ||
        parameters.isEmpty() || parameters.any { it != OBJECT_DESCRIPTOR }
    ) return false

    val functionArity = owner.interfaces.mapNotNull { type ->
        Regex("Lkotlin/jvm/functions/Function(\\d+);").matchEntire(type.toString())
            ?.groupValues?.get(1)?.toIntOrNull()
    }
    if (functionArity != listOf(parameters.size)) return false

    val code = implementation?.instructions?.toList() ?: return false
    val captureReads = code.mapIndexedNotNull { index, instruction ->
        val field = instruction.getReference<FieldReference>() ?: return@mapIndexedNotNull null
        if (instruction.opcode != Opcode.IGET_OBJECT || field.type != models.entryDescriptor ||
            field.definingClass != owner.type
        ) return@mapIndexedNotNull null
        val registers = instruction as? TwoRegisterInstruction ?: return@mapIndexedNotNull null
        index to registers
    }.filter { (index, registers) ->
        this.provesParameterValue(index, registers.registerB, p0Register)
    }
    if (captureReads.size != 1) return false

    val (captureIndex, capture) = captureReads.single()
    val actionTypeReads = code.mapIndexedNotNull { index, instruction ->
        if (instruction.opcode != Opcode.IGET_OBJECT ||
            instruction.getReference<FieldReference>()?.toString() != models.actionTypeField.toString()
        ) return@mapIndexedNotNull null
        val read = instruction as? TwoRegisterInstruction ?: return@mapIndexedNotNull null
        index to read
    }.filter { (index, read) ->
        this.provesValueFromInstruction(captureIndex, capture.registerA, index, read.registerB)
    }
    val enabledReads = code.mapIndexedNotNull { index, instruction ->
        if (instruction.opcode != Opcode.IGET_BOOLEAN ||
            instruction.getReference<FieldReference>()?.toString() != models.enabledField.toString()
        ) return@mapIndexedNotNull null
        val read = instruction as? TwoRegisterInstruction ?: return@mapIndexedNotNull null
        index to read
    }.filter { (index, read) ->
        this.provesValueFromInstruction(captureIndex, capture.registerA, index, read.registerB)
    }
    if (enabledReads.size != 1) return false

    val actionTypeTintTuples = actionTypeReads.flatMap { actionTypeRead ->
        code.mapIndexedNotNull { index, instruction ->
            if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) {
                return@mapIndexedNotNull null
            }
            val reference = instruction.getReference<MethodReference>() ?: return@mapIndexedNotNull null
            if (reference.returnType != "V" ||
                reference.parameterTypes.firstOrNull()?.toString() != models.postActionTypeDescriptor
            ) return@mapIndexedNotNull null
            val actionTypeRegister = instruction.argumentRegister(reference, 0) ?: return@mapIndexedNotNull null
            (actionTypeRead to (index to reference)).takeIf {
                this.provesValueFromInstruction(
                    actionTypeRead.first,
                    actionTypeRead.second.registerA,
                    index,
                    actionTypeRegister,
                )
            }
        }
    }
    return actionTypeTintTuples.size == 1
}

internal fun resolveCapturedInlineActionEntryRenderer(
    method: MutableMethod,
    owner: ClassDef,
    models: InlineRendererModels,
    resolveIconSize: Boolean = true,
): ResolvedCapturedInlineActionRenderer {
    val code = method.instructions.toList()
    val captureRead = requireExactlyOne(
        "NewX captured inline-action entry field read in $method",
        code.mapIndexedNotNull { index, instruction ->
            val field = instruction.getReference<FieldReference>() ?: return@mapIndexedNotNull null
            if (instruction.opcode != Opcode.IGET_OBJECT ||
                field.definingClass != owner.type || field.type != models.entryDescriptor
            ) return@mapIndexedNotNull null
            val registers = instruction as? TwoRegisterInstruction ?: return@mapIndexedNotNull null
            (index to registers).takeIf { (_, read) ->
                method.provesParameterValue(index, read.registerB, method.p0Register)
            }
        },
    )
    val entryRegister = captureRead.second.registerA
    val actionTypeReads =
        code.mapIndexedNotNull { index, instruction ->
            if (instruction.opcode != Opcode.IGET_OBJECT ||
                instruction.getReference<FieldReference>()?.toString() != models.actionTypeField.toString()
            ) return@mapIndexedNotNull null
            val read = instruction as? TwoRegisterInstruction ?: return@mapIndexedNotNull null
            (index to read).takeIf { (readIndex, registers) ->
                method.provesValueFromInstruction(captureRead.first, entryRegister, readIndex, registers.registerB)
            }
        }
    requireExactlyOne(
        "NewX captured inline-action enabled read in $method",
        code.mapIndexedNotNull { index, instruction ->
            if (instruction.opcode != Opcode.IGET_BOOLEAN ||
                instruction.getReference<FieldReference>()?.toString() != models.enabledField.toString()
            ) return@mapIndexedNotNull null
            val read = instruction as? TwoRegisterInstruction ?: return@mapIndexedNotNull null
            index.takeIf { method.provesValueFromInstruction(captureRead.first, entryRegister, index, read.registerB) }
        },
    )

    val actionTypeTintTuples = actionTypeReads.flatMap { actionTypeRead ->
        code.mapIndexedNotNull { index, instruction ->
            if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) {
                return@mapIndexedNotNull null
            }
            val reference = instruction.getReference<MethodReference>() ?: return@mapIndexedNotNull null
            if (reference.returnType != "V" ||
                reference.parameterTypes.firstOrNull()?.toString() != models.postActionTypeDescriptor
            ) return@mapIndexedNotNull null
            val actionTypeRegister = instruction.argumentRegister(reference, 0)
                ?: throw PatchException("NewX captured inline-action renderer has no action-type argument: $instruction")
            (actionTypeRead to (index to reference)).takeIf { (_, tintCall) ->
                method.provesValueFromInstruction(
                    actionTypeRead.first,
                    actionTypeRead.second.registerA,
                    tintCall.first,
                    actionTypeRegister,
                )
            }
        }
    }
    val (_, tintCall) = requireExactlyOne(
        "NewX captured inline-action type-to-tint renderer tuple in $method",
        actionTypeTintTuples,
    )

    val sizingClass = requireExactlyOne(
        "NewX captured inline-action sizing model in ${owner.type}",
        owner.fields.filter { field -> !AccessFlags.STATIC.isSet(field.accessFlags) }
            .mapNotNull { field -> classDefByOrNull(field.type) }
            .distinctBy { definition -> definition.type }
            .filter { definition -> definition.isInlineActionSizingModel() },
    )
    val sizingCaptureField = requireExactlyOne(
        "NewX captured inline-action sizing field in ${owner.type}",
        owner.fields.filter { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) && field.type == sizingClass.type
        },
    )
    val iconSizeRead = if (!resolveIconSize) null else {
        val sizingCaptureRead = requireExactlyOne(
            "NewX captured inline-action sizing read in $method",
            code.mapIndexedNotNull { index, instruction ->
                if (instruction.opcode != Opcode.IGET_OBJECT ||
                    instruction.getReference<FieldReference>()?.toString() != sizingCaptureField.toString()
                ) return@mapIndexedNotNull null
                val read = instruction as? TwoRegisterInstruction ?: return@mapIndexedNotNull null
                index.takeIf { method.provesParameterValue(index, read.registerB, method.p0Register) }
                    ?.let { it to read.registerA }
            },
        )
        val iconSizeReads = sizingClass.fields.filter { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) && field.type == "F"
        }.flatMap { sizeField ->
            code.mapIndexedNotNull { index, instruction ->
                if (instruction.opcode != Opcode.IGET ||
                    instruction.getReference<FieldReference>()?.toString() != sizeField.toString()
                ) return@mapIndexedNotNull null
                val read = instruction as? TwoRegisterInstruction ?: return@mapIndexedNotNull null
                if (!method.provesValueFromInstruction(
                        sizingCaptureRead.first,
                        sizingCaptureRead.second,
                        index,
                        read.registerB,
                    )
                ) return@mapIndexedNotNull null
                index to read.registerA
            }
        }
        val floatParameters = tintCall.second.parameterTypes.map(CharSequence::toString).indices
            .filter { parameterIndex -> tintCall.second.parameterTypes[parameterIndex].toString() == "F" }
            .mapNotNull { parameterIndex ->
                val argument = code[tintCall.first].argumentRegister(tintCall.second, parameterIndex)
                    ?: return@mapNotNull null
                parameterIndex to argument
            }
        val iconSizeProvenanceFlow = iconSizeReads.flatMap { (readIndex, sizeRegister) ->
            floatParameters.mapNotNull { (parameterIndex, argument) ->
                Triple(readIndex, sizeRegister, parameterIndex).takeIf {
                    method.provesValueFromInstruction(readIndex, sizeRegister, tintCall.first, argument)
                }
            }
        }
        // Fallback: register identity between the size read and the float
        // argument, for co-applied shapes the provenance proof cannot carry.
        val iconSizeRegisterFlow = iconSizeReads.flatMap { (readIndex, sizeRegister) ->
            floatParameters.mapNotNull { (parameterIndex, argument) ->
                Triple(readIndex, sizeRegister, parameterIndex).takeIf { argument == sizeRegister }
            }
        }
        val iconSizeFlow = when {
            iconSizeProvenanceFlow.size == 1 -> iconSizeProvenanceFlow.single()
            iconSizeProvenanceFlow.isEmpty() && iconSizeRegisterFlow.size == 1 ->
                iconSizeRegisterFlow.single()
            else -> throw PatchException(
                "NewX captured inline-action icon-size flow from sizing state to the renderer in $method: " +
                    "provenance candidates $iconSizeProvenanceFlow, " +
                    "register-identity candidates $iconSizeRegisterFlow, " +
                    "size reads $iconSizeReads, float parameters $floatParameters, " +
                    "tint call at ${tintCall.first} to ${tintCall.second}",
            )
        }
        (iconSizeFlow.first to iconSizeFlow.second).also { resolvedSizeRead ->
            if (!method.provesValueFromInstruction(captureRead.first, entryRegister, resolvedSizeRead.first, entryRegister)) {
                throw PatchException("NewX captured inline-action entry does not reach the size hook in $method")
            }
        }
    }

    val constructorCapture = resolveCapturedConstructor(owner, sizingCaptureField, models.entryDescriptor)
    val tintRead = resolveCapturedWideFieldRead(method, owner, constructorCapture.tintField)
    val tintWideParameters = tintCall.second.parameterTypes.map(CharSequence::toString).indices
        .filter { index -> tintCall.second.parameterTypes[index].toString() == "J" }
        .mapNotNull { parameterIndex ->
            val register = code[tintCall.first].argumentRegister(tintCall.second, parameterIndex)
                ?: return@mapNotNull null
            parameterIndex to register
        }
    val tintProvenanceCandidates = tintWideParameters.filter { (_, register) ->
        method.provesValueFromInstruction(tintRead.first, tintRead.second, tintCall.first, register)
    }
    // Fallback: in-place transforms (see carriedResultValue) write back into
    // the tint register itself, so register identity with the tint field
    // read is an equally strong identification when a co-applied patch's
    // inserted code defeats the provenance proof in a shape not yet carried.
    val tintRegisterCandidates = tintWideParameters.filter { (_, register) -> register == tintRead.second }
    val tintArgument = when {
        tintProvenanceCandidates.size == 1 -> tintProvenanceCandidates.single()
        tintProvenanceCandidates.isEmpty() && tintRegisterCandidates.size == 1 ->
            tintRegisterCandidates.single()
        else -> throw PatchException(
            "NewX captured inline-action tint argument in $method: " +
                "provenance candidates $tintProvenanceCandidates, " +
                "register-identity candidates $tintRegisterCandidates, " +
                "wide parameters (index to register) $tintWideParameters, " +
                "tint read at ${tintRead.first} into v${tintRead.second}, " +
                "tint call at ${tintCall.first} to ${tintCall.second}",
        )
    }
    val contentTint = constructorCapture.contentTintField?.let { contentField ->
        val wideArguments = tintCall.second.parameterTypes.map(CharSequence::toString).indices
            .filter { index -> tintCall.second.parameterTypes[index].toString() == "J" }
        if (wideArguments.size < 3) return@let null
        val contentRead = resolveCapturedWideFieldRead(method, owner, contentField)
        val thirdWideParameter = wideArguments[2]
        val thirdWideRegister = code[tintCall.first].argumentRegister(tintCall.second, thirdWideParameter)
            ?: throw PatchException("NewX captured inline-action count-tint argument is missing: $method")
        // Same register-identity fallback as the tint argument: an in-place
        // transform inserted after the content-tint read (Piko Theme hooks
        // this read too) writes back into the same register.
        if (!method.provesValueFromInstruction(contentRead.first, contentRead.second, tintCall.first, thirdWideRegister) &&
            thirdWideRegister != contentRead.second
        ) {
            throw PatchException(
                "NewX captured inline-action count tint does not reach the renderer: $method " +
                    "(content read at ${contentRead.first} into v${contentRead.second}, " +
                    "third wide argument register v$thirdWideRegister at ${tintCall.first})",
            )
        }
        contentRead
    }

    if (tintArgument.first >= tintCall.second.parameterTypes.size) {
        throw PatchException("NewX captured inline-action renderer argument resolution is invalid: $method")
    }
    return ResolvedCapturedInlineActionRenderer(
        method = method,
        entryRegister = entryRegister,
        iconSizeReadIndex = iconSizeRead?.first,
        iconSizeRegister = iconSizeRead?.second,
        tintReadIndex = tintRead.first,
        tintRegister = tintRead.second,
        contentTintReadIndex = contentTint?.first,
        contentTintRegister = contentTint?.second,
        tintRenderer = tintCall.second,
    )
}

internal data class CapturedRendererCandidate(
    val method: Method,
    val owner: ClassDef,
)

internal fun capturedInlineActionRendererCandidates(
    models: InlineRendererModels,
): List<CapturedRendererCandidate> =
    // Each renderer reads the enabled flag of the entry once, so only those methods are checked.
    Fingerprint(
        filters = listOf(fieldAccess(models.enabledField, Opcode.IGET_BOOLEAN)),
    ).matchAllOrNull().orEmpty().filter { match ->
        match.originalMethod.isCapturedInlineActionEntryRenderer(match.originalClassDef, models)
    }.map { match -> CapturedRendererCandidate(match.originalMethod, match.originalClassDef) }

private data class CapturedConstructorFields(
    val tintField: FieldReference,
    val contentTintField: FieldReference?,
)

private fun resolveCapturedConstructor(
    owner: ClassDef,
    sizingCaptureField: FieldReference,
    entryDescriptor: String,
): CapturedConstructorFields {
    val entryFields = owner.fields.filter { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) && field.type == entryDescriptor
    }
    val entryField = requireExactlyOne("NewX captured inline-action entry field in ${owner.type}", entryFields)
    val sizingClass = classDefByOrNull(sizingCaptureField.type)
        ?: throw PatchException("NewX captured inline-action sizing class was not found: ${sizingCaptureField.type}")
    val constructors = owner.methods.filter { constructor ->
        if (constructor.name != "<init>" || constructor.returnType != "V") return@filter false
        val parameters = constructor.parameterTypes.map(CharSequence::toString)
        val entryParameter = parameters.indexOf(entryDescriptor)
        val sizingParameter = parameters.indexOf(sizingClass.type)
        val wideParameters = parameters.indices.filter { index -> parameters[index] == "J" }
        entryParameter >= 0 && sizingParameter >= 0 && wideParameters.isNotEmpty() &&
            constructorParameterField(constructor, entryParameter, entryField) != null &&
            constructorParameterField(constructor, sizingParameter, sizingCaptureField) != null
    }
    val constructor = requireExactlyOne("NewX captured inline-action constructor in ${owner.type}", constructors)
    val wideParameters = constructor.parameterTypes.map(CharSequence::toString).indices
        .filter { index -> constructor.parameterTypes[index].toString() == "J" }
    val wideFieldsByCaptureOrder = wideParameters.mapIndexed { captureOrder, parameterIndex ->
        val fields = owner.fields.filter { field ->
            !AccessFlags.STATIC.isSet(field.accessFlags) && field.type == "J" &&
                constructorParameterField(constructor, parameterIndex, field) != null
        }
        captureOrder to requireExactlyOne(
            "NewX captured inline-action wide field for parameter $parameterIndex in ${owner.type}",
            fields,
        )
    }
    return CapturedConstructorFields(
        tintField = requireExactlyOne(
            "NewX captured inline-action tint field in ${owner.type}",
            wideFieldsByCaptureOrder.filter { (captureOrder, _) -> captureOrder == 0 }
                .map { (_, field) -> field },
        ),
        contentTintField = requireAtMostOne(
            "NewX captured inline-action count-tint field in ${owner.type}",
            wideFieldsByCaptureOrder.filter { (captureOrder, _) -> captureOrder == 1 }
                .map { (_, field) -> field },
        ),
    )
}

private fun constructorParameterField(
    constructor: Method,
    parameterIndex: Int,
    field: FieldReference,
): FieldReference? {
    val parameterRegister = constructor.parameterRegister(parameterIndex)
    val opcode = if (field.type == "J" || field.type == "D") Opcode.IPUT_WIDE else Opcode.IPUT_OBJECT
    val matchingWrites = constructor.implementation?.instructions?.withIndex()?.filter { indexed ->
        val instruction = indexed.value
        if (instruction.opcode != opcode ||
            instruction.getReference<FieldReference>()?.toString() != field.toString()
        ) return@filter false
        val registers = instruction as? TwoRegisterInstruction ?: return@filter false
        constructor.provesParameterValue(indexed.index, registers.registerA, parameterRegister) &&
            constructor.provesParameterValue(indexed.index, registers.registerB, constructor.p0Register)
    }.orEmpty()
    return field.takeIf { matchingWrites.size == 1 }
}

private fun resolveCapturedWideFieldRead(
    method: MutableMethod,
    owner: ClassDef,
    field: FieldReference,
): Pair<Int, Int> {
    val reads = method.instructions.withIndex().mapNotNull { indexed ->
        if (indexed.value.opcode != Opcode.IGET_WIDE ||
            indexed.value.getReference<FieldReference>()?.toString() != field.toString()
        ) return@mapNotNull null
        val read = indexed.value as? TwoRegisterInstruction ?: return@mapNotNull null
        indexed.index.takeIf { method.provesParameterValue(indexed.index, read.registerB, method.p0Register) }
            ?.let { it to read.registerA }
    }
    if (field.definingClass != owner.type) throw PatchException("NewX captured inline-action field owner changed: $field")
    return requireExactlyOne("NewX captured inline-action wide read $field in $method", reads)
}

private fun ClassDef.isInlineActionSizingModel(): Boolean =
    methods.any { method ->
        method.name == "toString" && method.parameterTypes.isEmpty() &&
            method.implementation?.instructions?.any { instruction ->
                instruction.getReference<com.android.tools.smali.dexlib2.iface.reference.StringReference>()?.string ==
                    SIZING_TO_STRING_PREFIX
            } == true && method.implementation?.instructions?.any { instruction ->
                instruction.getReference<com.android.tools.smali.dexlib2.iface.reference.StringReference>()?.string ==
                    SIZING_ICON_SIZE_LABEL
            } == true
    }

private fun Method.parameterRegister(parameterIndex: Int): Int {
    if (parameterIndex !in parameterTypes.indices) {
        throw PatchException("NewX captured inline-action parameter index is out of range: $parameterIndex in $this")
    }
    val firstParameterRegister = p0Register + if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    return firstParameterRegister + parameterTypes.take(parameterIndex).sumOf { parameter ->
        if (parameter.toString() == "J" || parameter.toString() == "D") 2 else 1
    }
}

internal fun Method.provesParameterValue(
    targetIndex: Int,
    targetRegister: Int,
    parameterRegister: Int,
): Boolean = valueProvenanceAt(targetIndex, targetRegister, parameterRegister, null)

internal fun Method.provesValueFromInstruction(
    sourceIndex: Int,
    sourceRegister: Int,
    targetIndex: Int,
    targetRegister: Int,
): Boolean = valueProvenanceAt(targetIndex, targetRegister, sourceRegister, sourceIndex)

/** A branch-local load need not dominate a shared renderer's other color-selection branches. */
internal fun Method.provesValueFromInstructionOnSourcePaths(
    sourceIndex: Int,
    sourceRegister: Int,
    targetIndex: Int,
    targetRegister: Int,
): Boolean = valueProvenanceAt(targetIndex, targetRegister, sourceRegister, sourceIndex, sourcePathsOnly = true)

private fun Method.valueProvenanceAt(
    targetIndex: Int,
    targetRegister: Int,
    originRegister: Int,
    originInstruction: Int?,
    sourcePathsOnly: Boolean = false,
): Boolean {
    val implementation = implementation ?: return false
    val code = implementation.instructions.toList()
    if (targetIndex !in code.indices || originRegister !in 0 until implementation.registerCount ||
        originInstruction != null && originInstruction !in code.indices
    ) return false
    // The expected token is the origin plus the requested word: a wide origin tokens its low and high words.
    val originWord = originInstruction?.let { index -> code[index].sourceWordOffset(originRegister) ?: return false } ?: 0
    val edges = code.controlFlowEdges(implementation.tryBlocks) ?: return false
    val input = arrayOfNulls<IntArray>(code.size)
    val initial = IntArray(implementation.registerCount) { UNKNOWN_VALUE }
    if (originInstruction == null) initial[originRegister] = PARAMETER_VALUE
    val startIndex = if (sourcePathsOnly) originInstruction ?: return false else 0
    input[startIndex] = initial
    val work = ArrayDeque<Int>()
    work.add(startIndex)
    while (work.isNotEmpty()) {
        val index = work.removeFirst()
        val inState = input[index] ?: continue
        val outState = inState.copyOf()
        val instruction = code[index]
        val destination = instruction.destinationRegisterOrNull()
        if (destination != null) {
            if (instruction.opcode == Opcode.CHECK_CAST) {
                // CHECK_CAST refines the verifier type without changing the object identity.
            } else {
                val movedFrom = instruction.moveSourceRegister()
                // Co-application tolerance: hooks other patches insert into
                // this same renderer (in their execute phase, before this
                // bundle's finalize) pass a tracked value through an extension
                // call and move the result back. Two idioms are carried:
                //  - markIconSize(Object, F)F (Piko's download/copy marking):
                //    the returned float is the input float or its negation.
                //  - an in-place transform whose first argument register is
                //    the move-result destination (Piko's Theme patch rewrites
                //    the captured tints exactly this way: invoke-static
                //    inlineActionTint(J)J with (reg, reg+1), then
                //    move-result-wide reg). The result is a function of that
                //    argument alone, so its derivation is unchanged.
                // For provenance — "does this value derive from the tracked
                // origin" — both are the identity on the carried argument.
                // Without this, the value would go UNKNOWN at that move-result
                // and the flow proofs would fail whenever those patches are
                // co-applied (the Theme one broke the tint-argument proof).
                val carriedValue = instruction.carriedResultValue(code, index, inState)
                val movedValue = carriedValue?.first
                    ?: movedFrom?.let { inState.getOrElse(it) { UNKNOWN_VALUE } }
                    ?: UNKNOWN_VALUE
                outState[destination] = movedValue
                if (instruction.opcode.setsWideRegister() && destination + 1 < outState.size) {
                    // A wide move carries its high word; a carried wide result
                    // carries the high word of its source argument; every
                    // other wide write leaves it unknown.
                    val movedHigh = carriedValue?.second
                        ?: movedFrom?.takeIf { instruction.opcode in WIDE_MOVE_OPCODES }
                            ?.let { inState.getOrElse(it + 1) { UNKNOWN_VALUE } }
                        ?: UNKNOWN_VALUE
                    outState[destination + 1] = movedHigh
                }
            }
        }
        if (originInstruction == index && destination != null) {
            outState[destination] = index
            if (instruction.opcode.setsWideRegister() && destination + 1 < outState.size) {
                outState[destination + 1] = index + 1
            }
        }
        edges[index].forEach { edge ->
            val next = if (edge.exceptional) inState else outState
            val prior = input[edge.target]
            if (prior == null) {
                input[edge.target] = next.copyOf()
                work.add(edge.target)
                return@forEach
            }
            var changed = false
            for (register in prior.indices) {
                if (prior[register] == next[register]) continue
                if (prior[register] != UNKNOWN_VALUE) {
                    prior[register] = UNKNOWN_VALUE
                    changed = true
                }
            }
            if (changed) work.add(edge.target)
        }
    }
    val state = input[targetIndex] ?: return false
    val expected = originInstruction?.let { index -> index + originWord } ?: PARAMETER_VALUE
    return state.getOrElse(targetRegister) { UNKNOWN_VALUE } == expected
}

/**
 * If this instruction is the move-result of a call whose result derives from
 * a single tracked argument, returns that argument's provenance token (low
 * word, and the high word for wide results); otherwise null. Two idioms:
 *
 *  - `markIconSize(Object, F)F` (the inline icon-marking hook shared by
 *    Piko's download button and this bundle's copy button): the F argument
 *    is the second register of the invoke-static (no receiver word).
 *  - An in-place transform: the invoke's first argument register is the
 *    move-result destination itself, e.g. Piko Theme's
 *    `inlineActionTint(J)J` invoked with (reg, reg+1) followed by
 *    move-result-wide reg. Whatever the transform computes, the result
 *    derives from that one argument.
 */
private fun Instruction.carriedResultValue(
    code: List<Instruction>,
    index: Int,
    inState: IntArray,
): Pair<Int, Int?>? {
    if (opcode != Opcode.MOVE_RESULT && opcode != Opcode.MOVE_RESULT_WIDE &&
        opcode != Opcode.MOVE_RESULT_OBJECT
    ) return null
    val invoke = code.getOrNull(index - 1) ?: return null
    if (invoke.opcode != Opcode.INVOKE_STATIC && invoke.opcode != Opcode.INVOKE_STATIC_RANGE &&
        invoke.opcode != Opcode.INVOKE_VIRTUAL && invoke.opcode != Opcode.INVOKE_VIRTUAL_RANGE
    ) return null
    val reference = invoke.getReference<MethodReference>() ?: return null
    if (reference.name == "markIconSize" && reference.returnType == "F" &&
        reference.parameterTypes.map(CharSequence::toString) ==
        listOf("Ljava/lang/Object;", "F")
    ) {
        if (opcode != Opcode.MOVE_RESULT) return null
        val sizeArgumentRegister = invoke.registersUsed.getOrNull(1) ?: return null
        val token = inState.getOrElse(sizeArgumentRegister) { UNKNOWN_VALUE }
        return (token to null).takeIf { token != UNKNOWN_VALUE }
    }
    val destination = destinationRegisterOrNull() ?: return null
    val firstArgumentWord =
        if (invoke.opcode == Opcode.INVOKE_STATIC || invoke.opcode == Opcode.INVOKE_STATIC_RANGE) 0 else 1
    val firstArgumentRegister = invoke.registersUsed.getOrNull(firstArgumentWord) ?: return null
    if (firstArgumentRegister != destination) return null
    val low = inState.getOrElse(firstArgumentRegister) { UNKNOWN_VALUE }
    if (low == UNKNOWN_VALUE) return null
    val high = if (opcode == Opcode.MOVE_RESULT_WIDE) {
        inState.getOrElse(firstArgumentRegister + 1) { UNKNOWN_VALUE }.takeIf { it != UNKNOWN_VALUE }
    } else null
    return low to high
}

/** Word offset of [register] within this instruction's destination pair, or null when it is not a write source. */
private fun Instruction.sourceWordOffset(register: Int): Int? {
    val destination = destinationRegisterOrNull() ?: return null
    return when {
        register == destination -> 0
        register == destination + 1 && opcode.setsWideRegister() -> 1
        else -> null
    }
}

private val WIDE_MOVE_OPCODES = setOf(Opcode.MOVE_WIDE, Opcode.MOVE_WIDE_FROM16, Opcode.MOVE_WIDE_16)

private fun Instruction.destinationRegisterOrNull(): Int? {
    if (!opcode.setsRegister()) return null
    return when (this) {
        is ThreeRegisterInstruction -> registerA
        is TwoRegisterInstruction -> registerA
        is OneRegisterInstruction -> registerA
        else -> null
    }
}

private fun Instruction.moveSourceRegister(): Int? {
    if (opcode !in MOVE_OPCODES) return null
    return (this as? TwoRegisterInstruction)?.registerB
}

private val MOVE_OPCODES = setOf(
    Opcode.MOVE,
    Opcode.MOVE_FROM16,
    Opcode.MOVE_16,
    Opcode.MOVE_OBJECT,
    Opcode.MOVE_OBJECT_FROM16,
    Opcode.MOVE_OBJECT_16,
    Opcode.MOVE_WIDE,
    Opcode.MOVE_WIDE_FROM16,
    Opcode.MOVE_WIDE_16,
)

private data class FlowEdge(val target: Int, val exceptional: Boolean)

/**
 * Decodes normal and exception edges. Each try range adds an exception edge from every
 * throwing instruction it covers to each of its handlers; those edges carry the pre-instruction
 * state. A malformed range or handler address fails closed.
 */
private fun List<Instruction>.controlFlowEdges(
    tryBlocks: List<TryBlock<out ExceptionHandler>>,
): List<List<FlowEdge>>? {
    val offsets = runningFold(0) { address, instruction -> address + instruction.codeUnits }
    val indexByOffset = offsets.dropLast(1).withIndex().associate { (index, offset) -> offset to index }
    val normalEdges = mapIndexed { index, instruction ->
        val opcode = instruction.opcode
        val offset = offsets[index]
        fun target(offset: Int): Int? = indexByOffset[offset]
        val branch = instruction as? OffsetInstruction
        val branchTarget = branch?.let { target(offset + it.codeOffset) }
        when {
            opcode in setOf(Opcode.RETURN, Opcode.RETURN_OBJECT, Opcode.RETURN_VOID, Opcode.RETURN_WIDE, Opcode.THROW) ->
                emptyList()
            opcode in setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32) ->
                listOfNotNull(branchTarget?.let { FlowEdge(it, false) }).takeIf { it.size == 1 } ?: return null
            opcode == Opcode.PACKED_SWITCH || opcode == Opcode.SPARSE_SWITCH -> {
                val payloadIndex = branchTarget ?: return null
                val payload = getOrNull(payloadIndex) as? SwitchPayload ?: return null
                val cases = payload.switchElements.map { element ->
                    target(offset + element.offset)?.let { FlowEdge(it, false) } ?: return null
                }
                (cases + listOfNotNull((index + 1).takeIf { it < size }?.let { FlowEdge(it, false) })).distinct()
            }
            opcode.format in setOf(com.android.tools.smali.dexlib2.Format.Format21t,
                com.android.tools.smali.dexlib2.Format.Format22t) -> {
                val destination = branchTarget ?: return null
                if (index + 1 >= size) return null
                listOf(FlowEdge(destination, false), FlowEdge(index + 1, false)).distinct()
            }
            instruction is SwitchPayload -> emptyList()
            else -> (index + 1).takeIf { it < size }?.let { listOf(FlowEdge(it, false)) }.orEmpty()
        }
    }
    val edges = normalEdges.map { it.toMutableList() }
    for (block in tryBlocks) {
        if (block.codeUnitCount <= 0) return null
        val start = indexByOffset[block.startCodeAddress] ?: return null
        val endAddress = block.startCodeAddress + block.codeUnitCount
        val end = if (endAddress == offsets.last()) size else (indexByOffset[endAddress] ?: return null)
        if (start >= end) return null
        if (block.exceptionHandlers.isEmpty()) return null
        val handlers = block.exceptionHandlers.map { handler ->
            indexByOffset[handler.handlerCodeAddress] ?: return null
        }
        for (index in start until end) {
            if (!this[index].opcode.canThrow()) continue
            handlers.forEach { handler -> edges[index] += FlowEdge(handler, true) }
        }
    }
    return edges.map { it.distinct() }
}

internal fun MutableMethod.hasOnlyFallThroughInto(index: Int): Boolean {
    val implementation = implementation ?: return false
    if (implementation.tryBlocks.isNotEmpty() || index <= 0 || index >= instructions.size) return false
    val edges = instructions.toList().controlFlowEdges(implementation.tryBlocks) ?: return false
    val predecessors = edges.flatMapIndexed { source, successors ->
        successors.filter { edge -> !edge.exceptional && edge.target == index }.map { source }
    }
    return predecessors == listOf(index - 1)
}

/**
 * Successor indices over every edge, normal and exception. Null when the method cannot be decoded
 * or a try block is malformed.
 */
internal fun Method.controlFlowSuccessors(): List<List<Int>>? {
    val implementation = implementation ?: return null
    val edges = implementation.instructions.toList().controlFlowEdges(implementation.tryBlocks) ?: return null
    return edges.map { successors -> successors.map { it.target } }
}

/**
 * Successor indices over normal (non-exceptional) edges. Exception edges from try ranges are
 * excluded, so reachability over this table is an under-approximation. Null when the method cannot
 * be decoded or a try block is malformed.
 */
internal fun Method.normalControlFlowSuccessors(): List<List<Int>>? {
    val implementation = implementation ?: return null
    val edges = implementation.instructions.toList().controlFlowEdges(implementation.tryBlocks) ?: return null
    return edges.map { successors -> successors.filter { !it.exceptional }.map { it.target } }
}

/**
 * Indices reachable from [entry] over this successor table, including the entry itself. Null when
 * the entry or any successor is outside the table, so callers fail closed.
 */
internal fun List<List<Int>>.reachableIndicesFrom(entry: Int): Set<Int>? {
    if (entry !in indices) return null
    val reached = linkedSetOf<Int>()
    val pending = ArrayDeque(listOf(entry))
    while (pending.isNotEmpty()) {
        val index = pending.removeFirst()
        if (!reached.add(index)) continue
        val successors = getOrNull(index) ?: return null
        for (successor in successors) {
            if (successor !in indices) return null
            pending += successor
        }
    }
    return reached
}

/** Entry index per case key of a packed or sparse switch, and the fallthrough index taken when no key matches. */
internal data class SwitchEntries(val byKey: Map<Int, Int>, val fallthrough: Int)

internal fun Method.switchEntries(switchIndex: Int): SwitchEntries? {
    val implementation = implementation ?: return null
    val code = implementation.instructions.toList()
    val switch = code.getOrNull(switchIndex) ?: return null
    if (switch.opcode != Opcode.PACKED_SWITCH && switch.opcode != Opcode.SPARSE_SWITCH) return null
    val offsets = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
    val indexByOffset = offsets.dropLast(1).withIndex().associate { (index, offset) -> offset to index }
    val payloadIndex = indexByOffset[offsets[switchIndex] + (switch as OffsetInstruction).codeOffset] ?: return null
    val payload = code.getOrNull(payloadIndex) as? SwitchPayload ?: return null
    val byKey = payload.switchElements.associate { element ->
        element.key to (indexByOffset[offsets[switchIndex] + element.offset] ?: return null)
    }
    val fallthrough = (switchIndex + 1).takeIf { it < code.size } ?: return null
    return SwitchEntries(byKey, fallthrough)
}

internal fun Instruction.argumentRegister(reference: MethodReference, parameterIndex: Int): Int? {
    if (parameterIndex !in reference.parameterTypes.indices) return null
    val registers = registersUsed
    var word = if (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) 0 else 1
    reference.parameterTypes.take(parameterIndex).forEach { parameter ->
        word += if (parameter.toString() == "J" || parameter.toString() == "D") 2 else 1
    }
    return registers.getOrNull(word)
}
