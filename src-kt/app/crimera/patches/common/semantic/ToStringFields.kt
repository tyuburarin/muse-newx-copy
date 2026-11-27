package app.crimera.patches.common.semantic

import app.crimera.patches.common.requireExactlyOne
import app.morphe.patcher.Match
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.ArrayDeque

/*
 * Extracted from piko-patches-library (app.crimera.patches.common.semantic), keeping only
 * fieldForToStringLabel and the data-flow helpers it needs. The library's bridge patchers
 * (requireBridge / patchBridge / accessor getters) are not used by this bundle: the
 * standalone extension reads the models reflectively at runtime instead.
 */

private const val STRING_DESCRIPTOR = "Ljava/lang/String;"

fun Match.fieldForToStringLabel(label: String): FieldReference =
    originalMethod.fieldForToStringLabel(label)

fun Method.fieldForToStringLabel(label: String): FieldReference {
    val instructions = implementation?.instructions?.toList().orEmpty()
    val labelIndices = instructions.mapIndexedNotNull { index, instruction ->
        index.takeIf {
            instruction.getReference<StringReference>()?.string == label
        }
    }
    val labelIndex = requireExactlyOne("model label '$label' in $this", labelIndices)

    val labelInstruction = instructions[labelIndex] as? OneRegisterInstruction
        ?: throw PatchException("model label '$label' has an unsupported register layout in $this")
    val labelRegister = labelInstruction.registerA
    val labelConsumers = instructions.findFirstMethodConsumers(labelIndex, labelRegister)

    val directFields = labelConsumers.flatMap { labelConsumer ->
        val reference = instructions[labelConsumer.index].getReference<MethodReference>()
            ?: return@flatMap emptyList()
        if (!reference.isStringBuilderLabelConsumer()) return@flatMap emptyList()
        instructions.findStringBuilderValueAppendsAfter(labelConsumer.index).flatMap { valueAppendIndex ->
            val valueRegister = instructions[valueAppendIndex].singleArgumentRegister()
                ?: return@flatMap emptyList()
            instructions.findFieldsForRegister(
                register = valueRegister,
                fromIndex = 0,
                untilIndex = valueAppendIndex,
                definingClass = definingClass,
            )
        }
    }
    if (directFields.isNotEmpty()) {
        return requireSingleToStringField(label, toString(), directFields)
    }

    val helperCandidates = buildList {
        labelConsumers.forEach { consumer ->
            val helperIndex = consumer.index
            val argumentRegisters = consumer.argumentRegisters
            val labelArgumentIndex = argumentRegisters.indexOfFirst { it in consumer.labelRegisters }
            if (labelArgumentIndex < 0 || labelArgumentIndex + 1 >= argumentRegisters.size) {
                return@forEach
            }
            val valueArgumentRegister = argumentRegisters[labelArgumentIndex + 1]
            instructions.findFieldsForRegister(
                register = valueArgumentRegister,
                fromIndex = labelIndex + 1,
                untilIndex = helperIndex,
                definingClass = definingClass,
            ).forEach(::add)
            instructions.findFieldsForRegister(
                register = valueArgumentRegister,
                fromIndex = 0,
                untilIndex = labelIndex,
                definingClass = definingClass,
            ).forEach(::add)
        }
    }
    return requireSingleToStringField(label, toString(), helperCandidates)
}

fun requireSingleToStringField(
    label: String,
    owner: String,
    candidates: List<FieldReference>,
): FieldReference {
    val distinct = candidates.distinctBy(FieldReference::toString)
    return requireExactlyOne("model field for '$label' in $owner", distinct)
}

private fun MethodReference.isStringBuilderLabelConsumer(): Boolean =
    definingClass == "Ljava/lang/StringBuilder;" &&
        parameterTypes.map(CharSequence::toString) == listOf(STRING_DESCRIPTOR) &&
        (name == "<init>" || name == "append")

private fun MethodReference.isStringBuilderValueAppend(): Boolean =
    definingClass == "Ljava/lang/StringBuilder;" &&
        name == "append" &&
        parameterTypes.size == 1

private data class LabelMethodConsumer(
    val index: Int,
    val argumentRegisters: List<Int>,
    val labelRegisters: Set<Int>,
)

private data class RegisterFlowState(
    val index: Int,
    val register: Int,
)

private data class LabelFlowState(
    val index: Int,
    val registers: Set<Int>,
)

private fun List<Instruction>.findFirstMethodConsumers(
    labelIndex: Int,
    labelRegister: Int,
): List<LabelMethodConsumer> {
    if (labelIndex + 1 >= size) return emptyList()
    val successors = controlFlowSuccessors()
    val pending = ArrayDeque<LabelFlowState>()
    val seen = mutableSetOf<LabelFlowState>()
    val consumers = linkedMapOf<Int, LabelMethodConsumer>()
    pending.add(LabelFlowState(labelIndex + 1, setOf(labelRegister)))

    while (pending.isNotEmpty()) {
        val state = pending.removeFirst()
        if (state.index !in indices || !seen.add(state)) continue
        val instruction = this[state.index]
        val reference = instruction.getReference<MethodReference>()
        val argumentRegisters = instruction.registersUsed
        if (reference != null && argumentRegisters.any { it in state.registers }) {
            consumers.putIfAbsent(
                state.index,
                LabelMethodConsumer(state.index, argumentRegisters, state.registers),
            )
            continue
        }

        val nextRegisters = instruction.updateTrackedRegisters(state.registers)
        if (nextRegisters.isEmpty()) continue
        successors[state.index].forEach { successor ->
            pending.add(LabelFlowState(successor, nextRegisters))
        }
    }
    return consumers.values.toList()
}

private fun List<Instruction>.findStringBuilderValueAppendsAfter(
    consumerIndex: Int,
): List<Int> {
    val successors = controlFlowSuccessors()
    val pending = ArrayDeque<Int>()
    val seen = mutableSetOf<Int>()
    val appends = linkedSetOf<Int>()
    successors[consumerIndex].forEach(pending::add)

    while (pending.isNotEmpty()) {
        val index = pending.removeFirst()
        if (index !in indices || !seen.add(index)) continue
        val instruction = this[index]
        if (instruction.getReference<MethodReference>()?.isStringBuilderValueAppend() == true) {
            appends += index
            continue
        }
        successors[index].forEach(pending::add)
    }
    return appends.toList()
}

private fun List<Instruction>.findFieldsForRegister(
    register: Int,
    fromIndex: Int,
    untilIndex: Int,
    definingClass: String,
): List<FieldReference> {
    if (fromIndex >= untilIndex || untilIndex <= 0) return emptyList()
    val lowerBound = fromIndex.coerceAtLeast(0)
    val upperBound = untilIndex.coerceAtMost(size)
    if (lowerBound >= upperBound) return emptyList()

    val predecessors = controlFlowPredecessors()
    val pending = ArrayDeque<RegisterFlowState>()
    val seen = mutableSetOf<RegisterFlowState>()
    val fields = linkedMapOf<String, FieldReference>()
    pending.add(RegisterFlowState(upperBound - 1, register))

    while (pending.isNotEmpty()) {
        val state = pending.removeFirst()
        if (state.index !in lowerBound until upperBound || !seen.add(state)) continue
        val instruction = this[state.index]

        val fieldRead = instruction.fieldReadForDestination(state.register)
        if (fieldRead != null) {
            if (fieldRead.definingClass == definingClass) {
                fields.putIfAbsent(fieldRead.toString(), fieldRead)
            }
            continue
        }

        val moveSource = instruction.moveSourceForDestination(state.register)
        if (moveSource != null) {
            predecessors[state.index].forEach { predecessor ->
                pending.add(RegisterFlowState(predecessor, moveSource))
            }
            continue
        }

        if (instruction.writesRegister(state.register)) continue
        predecessors[state.index].forEach { predecessor ->
            pending.add(RegisterFlowState(predecessor, state.register))
        }
    }
    return fields.values.toList()
}

private fun Instruction.updateTrackedRegisters(registers: Set<Int>): Set<Int> {
    val move = moveInstruction()
    if (move != null) {
        val next = registers.toMutableSet()
        next.remove(move.registerA)
        if (move.registerB in registers) next += move.registerA
        return next
    }
    return destinationRegister()?.let(registers::minus) ?: registers
}

private fun Instruction.moveSourceForDestination(register: Int): Int? {
    val move = moveInstruction() ?: return null
    return move.registerB.takeIf { move.registerA == register }
}

private fun Instruction.moveInstruction(): TwoRegisterInstruction? =
    takeIf { opcode in MOVE_OPCODES }?.let { it as? TwoRegisterInstruction }

private fun Instruction.fieldReadForDestination(register: Int): FieldReference? {
    if (opcode in INSTANCE_FIELD_READ_OPCODES) {
        val instruction = this as? TwoRegisterInstruction ?: return null
        if (instruction.registerA != register) return null
        return getReference<FieldReference>()
    }
    if (opcode in STATIC_FIELD_READ_OPCODES) {
        val instruction = this as? OneRegisterInstruction ?: return null
        if (instruction.registerA != register) return null
        return getReference<FieldReference>()
    }
    return null
}

private fun Instruction.destinationRegister(): Int? {
    if (opcode in MOVE_OPCODES) return (this as? TwoRegisterInstruction)?.registerA
    if (opcode in INSTANCE_FIELD_READ_OPCODES) {
        return (this as? TwoRegisterInstruction)?.registerA
    }
    if (opcode in STATIC_FIELD_READ_OPCODES) {
        return (this as? OneRegisterInstruction)?.registerA
    }
    if (opcode in NON_DESTINATION_OPCODES) return null
    if (this is ThreeRegisterInstruction) return registerA
    if (this is TwoRegisterInstruction) return registerA
    if (this is OneRegisterInstruction) return registerA
    return null
}

private fun Instruction.writesRegister(register: Int): Boolean =
    destinationRegister() == register

private fun List<Instruction>.controlFlowPredecessors(): Array<List<Int>> {
    val predecessors = Array(size) { mutableListOf<Int>() }
    controlFlowSuccessors().forEachIndexed { index, successors ->
        successors.forEach { successor ->
            predecessors[successor] += index
        }
    }
    return Array(size) { predecessors[it].toList() }
}

private fun List<Instruction>.controlFlowSuccessors(): Array<Set<Int>> {
    val offsets = IntArray(size)
    val indexByOffset = mutableMapOf<Int, Int>()
    var codeOffset = 0
    forEachIndexed { index, instruction ->
        offsets[index] = codeOffset
        indexByOffset[codeOffset] = index
        codeOffset += instruction.codeUnits
    }

    fun branchTarget(index: Int): Int? {
        val offsetInstruction = this[index] as? com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction ?: return null
        return indexByOffset[offsets[index] + offsetInstruction.codeOffset]
    }

    fun fallthrough(index: Int): Int? = (index + 1).takeIf { it < size }

    return Array(size) { index ->
        val instruction = this[index]
        when {
            instruction is SwitchPayload -> emptySet()
            instruction.opcode in GOTO_OPCODES ->
                setOfNotNull(branchTarget(index))
            instruction.opcode in CONDITIONAL_BRANCH_OPCODES ->
                setOfNotNull(branchTarget(index), fallthrough(index))
            instruction.opcode in SWITCH_OPCODES -> {
                val payload = branchTarget(index)?.let { this[it] as? SwitchPayload }
                buildSet {
                    fallthrough(index)?.let(::add)
                    payload?.switchElements?.forEach { element ->
                        indexByOffset[offsets[index] + element.offset]?.let(::add)
                    }
                }
            }
            instruction.opcode in TERMINAL_OPCODES -> emptySet()
            else -> setOfNotNull(fallthrough(index))
        }
    }
}

private val MOVE_OPCODES =
    setOf(
        Opcode.MOVE,
        Opcode.MOVE_FROM16,
        Opcode.MOVE_16,
        Opcode.MOVE_WIDE,
        Opcode.MOVE_WIDE_FROM16,
        Opcode.MOVE_WIDE_16,
        Opcode.MOVE_OBJECT,
        Opcode.MOVE_OBJECT_FROM16,
        Opcode.MOVE_OBJECT_16,
    )

private val INSTANCE_FIELD_READ_OPCODES =
    setOf(
        Opcode.IGET,
        Opcode.IGET_WIDE,
        Opcode.IGET_OBJECT,
        Opcode.IGET_BOOLEAN,
        Opcode.IGET_BYTE,
        Opcode.IGET_CHAR,
        Opcode.IGET_SHORT,
    )

private val STATIC_FIELD_READ_OPCODES =
    setOf(
        Opcode.SGET,
        Opcode.SGET_WIDE,
        Opcode.SGET_OBJECT,
        Opcode.SGET_BOOLEAN,
        Opcode.SGET_BYTE,
        Opcode.SGET_CHAR,
        Opcode.SGET_SHORT,
    )

private val GOTO_OPCODES = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

private val CONDITIONAL_BRANCH_OPCODES =
    setOf(
        Opcode.IF_EQ,
        Opcode.IF_NE,
        Opcode.IF_LT,
        Opcode.IF_GE,
        Opcode.IF_GT,
        Opcode.IF_LE,
        Opcode.IF_EQZ,
        Opcode.IF_NEZ,
        Opcode.IF_LTZ,
        Opcode.IF_GEZ,
        Opcode.IF_GTZ,
        Opcode.IF_LEZ,
    )

private val SWITCH_OPCODES = setOf(Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH)

private val TERMINAL_OPCODES =
    setOf(
        Opcode.RETURN_VOID,
        Opcode.RETURN,
        Opcode.RETURN_WIDE,
        Opcode.RETURN_OBJECT,
        Opcode.THROW,
    )

private val NON_DESTINATION_OPCODES =
    setOf(
        Opcode.NOP,
        Opcode.MONITOR_ENTER,
        Opcode.MONITOR_EXIT,
        Opcode.CHECK_CAST,
        Opcode.THROW,
        Opcode.GOTO,
        Opcode.GOTO_16,
        Opcode.GOTO_32,
        Opcode.PACKED_SWITCH,
        Opcode.SPARSE_SWITCH,
        Opcode.FILL_ARRAY_DATA,
        Opcode.RETURN_VOID,
        Opcode.RETURN,
        Opcode.RETURN_WIDE,
        Opcode.RETURN_OBJECT,
        Opcode.IF_EQ,
        Opcode.IF_NE,
        Opcode.IF_LT,
        Opcode.IF_GE,
        Opcode.IF_GT,
        Opcode.IF_LE,
        Opcode.IF_EQZ,
        Opcode.IF_NEZ,
        Opcode.IF_LTZ,
        Opcode.IF_GEZ,
        Opcode.IF_GTZ,
        Opcode.IF_LEZ,
        Opcode.APUT,
        Opcode.APUT_WIDE,
        Opcode.APUT_OBJECT,
        Opcode.APUT_BOOLEAN,
        Opcode.APUT_BYTE,
        Opcode.APUT_CHAR,
        Opcode.APUT_SHORT,
        Opcode.IPUT,
        Opcode.IPUT_WIDE,
        Opcode.IPUT_OBJECT,
        Opcode.IPUT_BOOLEAN,
        Opcode.IPUT_BYTE,
        Opcode.IPUT_CHAR,
        Opcode.IPUT_SHORT,
        Opcode.SPUT,
        Opcode.SPUT_WIDE,
        Opcode.SPUT_OBJECT,
        Opcode.SPUT_BOOLEAN,
        Opcode.SPUT_BYTE,
        Opcode.SPUT_CHAR,
        Opcode.SPUT_SHORT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.INVOKE_SUPER,
        Opcode.INVOKE_DIRECT,
        Opcode.INVOKE_STATIC,
        Opcode.INVOKE_INTERFACE,
        Opcode.INVOKE_VIRTUAL_RANGE,
        Opcode.INVOKE_SUPER_RANGE,
        Opcode.INVOKE_DIRECT_RANGE,
        Opcode.INVOKE_STATIC_RANGE,
        Opcode.INVOKE_INTERFACE_RANGE,
    )

private fun Instruction.singleArgumentRegister(): Int? = registersUsed.getOrNull(1)
