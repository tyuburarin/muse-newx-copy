package local.muse.newx

import app.crimera.bytecode.Target
import app.crimera.bytecode.insertHook
import app.crimera.bytecode.methodReference
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.addFreeRegisters
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.p0Register
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * The two remaining v0.2.0 hooks:
 *
 *  - Displayed-translation capture: both NewX translation presenters register
 *    the translation they are about to show (or null for the original) through
 *    Lcom/x/scribing/urt/c;->R(d; h6; l; Composer; I)V. Hooking its head and
 *    forwarding (post id, TranslatedPost) to the extension stash is the only
 *    point where "what is on screen" is already decided — CanonicalPost cannot
 *    express the show/original toggle. The method is resolved by its argument
 *    signature plus the TranslatedPost toString anchor and a caller check,
 *    not by its obfuscated owner name.
 *  - Media long-press: the post event handler h5.invoke already receives a
 *    DidLongPressMediaContent event carrying the exact pressed media item.
 *    Hooking its head, copying that item's direct URL and returning
 *    immediately suppresses the native bottom sheet; taps are a different
 *    event type and stay untouched. The handler is resolved by semantic
 *    conditions (instanceof the long-press event + media sheet construction +
 *    reads of the event's rect/scale fields + the "long_press" scribe string),
 *    not by its obfuscated name.
 */

private const val DISPLAYED_TRANSLATION_EXT = "Llocal/muse/newx/extension/misc/DisplayedTranslation;"
private const val MEDIA_LONG_PRESS_EXT = "Llocal/muse/newx/extension/misc/MediaLongPress;"
private const val OBJECT_DESCRIPTOR = "Ljava/lang/Object;"
private const val STRING_DESCRIPTOR = "Ljava/lang/String;"
private const val COMPOSER_DESCRIPTOR = "Landroidx/compose/runtime/Composer;"

private const val EXPECTED_TRANSLATED_POST_DESCRIPTOR = "Lcom/x/groktranslate/l;"
private const val TRANSLATION_REGISTRY_DESCRIPTOR = "Lcom/x/groktranslate/d;"
private const val POST_ID_DESCRIPTOR = "Lcom/x/models/h6;"
private const val EXPECTED_LONG_PRESS_EVENT_DESCRIPTOR = "Lcom/x/urt/items/post/k4;"
private const val MEDIA_SHEET_DESCRIPTOR = "Lcom/x/urt/items/post/media/v;"
private const val POST_ITEMS_SCOPE = "Lcom/x/urt/items/post/"
private const val GROK_PRESENTER_SCOPE = "Lcom/x/urt/items/post/translate/grok/"

private fun requireOneMatchHere(label: String, matches: Collection<Match>): Match {
    if (matches.size == 1) return matches.single()
    throw PatchException(
        "Expected one $label match, found ${matches.size}: " +
            matches.joinToString { it.originalMethod.toString() },
    )
}

// ---------------------------------------------------------------------------
// Displayed translation capture
// ---------------------------------------------------------------------------

context(BytecodePatchContext)
internal fun injectDisplayedTranslation() {
    val translatedPostMatch = requireOneMatchHere(
        "NewX TranslatedPost model",
        Fingerprint(
            definingClass = "Lcom/x/groktranslate/",
            name = "toString",
            returnType = STRING_DESCRIPTOR,
            parameters = emptyList(),
            filters = listOf(app.morphe.patcher.string("TranslatedPost(text=")),
        ).matchAll(),
    )
    val translatedPostDescriptor = translatedPostMatch.originalClassDef.type
    if (translatedPostDescriptor != EXPECTED_TRANSLATED_POST_DESCRIPTOR) {
        throw PatchException(
            "NewX TranslatedPost changed: expected $EXPECTED_TRANSLATED_POST_DESCRIPTOR, " +
                "resolved $translatedPostDescriptor",
        )
    }

    val registryMatch = requireOneMatchHere(
        "NewX translation registry method",
        Fingerprint(
            returnType = "V",
            parameters = listOf(
                TRANSLATION_REGISTRY_DESCRIPTOR,
                POST_ID_DESCRIPTOR,
                translatedPostDescriptor,
                COMPOSER_DESCRIPTOR,
                "I",
            ),
        ).matchAll(),
    )
    val registryMethod = registryMatch.originalMethod

    // Caller check: both translation presenters (timeline auto-translation and
    // post-detail on-demand) must call this method; that is what makes the
    // signature match the display-registration point rather than a lookalike.
    val grokCallers = Fingerprint(
        filters = listOf(methodCall(smali = registryMethod.toString())),
    ).matchAll()
        .map { it.originalClassDef.type }
        .distinct()
        .filter { it.startsWith(GROK_PRESENTER_SCOPE) }
    if (grokCallers.size < 2) {
        throw PatchException(
            "Expected both NewX translation presenters to call the registry method " +
                "$registryMethod, found callers: ${grokCallers.joinToString()}",
        )
    }

    val method = registryMatch.method
    val isStatic = AccessFlags.STATIC.isSet(method.accessFlags)
    val parameterWords = method.parameterTypes.sumOf { type ->
        if (type.toString() == "J" || type.toString() == "D") 2 else 1
    }
    val firstParameterRegister = method.implementation!!.registerCount -
        parameterWords - if (isStatic) 0 else 1
    // Parameters: (registry, post id, translated post, composer, flags). The
    // registry itself is not needed by the extension stash.
    val receiverOffset = if (isStatic) 0 else 1
    val postIdRegister = firstParameterRegister + receiverOffset + 1
    val translatedRegister = postIdRegister + 1
    method.insertHook(0, relocateBranchTargets = false) {
        invokeStatic(
            methodReference("$DISPLAYED_TRANSLATION_EXT->track(Ljava/lang/Object;Ljava/lang/Object;)V"),
            postIdRegister,
            translatedRegister,
        )
    }
}

// ---------------------------------------------------------------------------
// Media long-press
// ---------------------------------------------------------------------------

context(BytecodePatchContext)
internal fun injectMediaLongPress() {
    val eventMatch = requireOneMatchHere(
        "NewX DidLongPressMediaContent event",
        Fingerprint(
            definingClass = POST_ITEMS_SCOPE,
            name = "toString",
            returnType = STRING_DESCRIPTOR,
            parameters = emptyList(),
            filters = listOf(app.morphe.patcher.string("DidLongPressMediaContent(clickedMedia=")),
        ).matchAll(),
    )
    val eventDescriptor = eventMatch.originalClassDef.type
    if (eventDescriptor != EXPECTED_LONG_PRESS_EVENT_DESCRIPTOR) {
        throw PatchException(
            "NewX long-press event changed: expected $EXPECTED_LONG_PRESS_EVENT_DESCRIPTOR, " +
                "resolved $eventDescriptor",
        )
    }

    val handlerMatch = requireOneMatchHere(
        "NewX post media long-press handler",
        Fingerprint(
            definingClass = POST_ITEMS_SCOPE,
            name = "invoke",
            parameters = listOf(OBJECT_DESCRIPTOR),
            returnType = OBJECT_DESCRIPTOR,
            custom = { method, _ -> method.isMediaLongPressHandler(eventDescriptor) },
        ).matchAll(),
    )
    val method = handlerMatch.method
    val unitField = resolveKotlinUnitSingleton()
    // Function1.invoke: p0 is the handler instance, p1 the event object.
    val eventRegister = method.p0Register + 1
    method.insertHook(0, relocateBranchTargets = false) {
        val eventScratch = scratchRegister()
        val checkRegister = scratchRegister()
        move(eventScratch, eventRegister, OBJECT_DESCRIPTOR)
        instanceOf(checkRegister, eventScratch, eventDescriptor)
        ifEqz(checkRegister, Target.Original)
        invokeStatic(
            methodReference("$MEDIA_LONG_PRESS_EXT->onMediaLongPress(Ljava/lang/Object;)V"),
            eventScratch,
        )
        sget(checkRegister, unitField)
        returnObject(checkRegister)
    }
}

/**
 * Semantic shape of h5.invoke: it branches on the long-press event, classifies
 * the pressed media, scribes "long_press" and builds the media sheet state.
 * The presenter-side consumer (a6.F) also instanceof-checks the event but
 * never builds the sheet, which is what separates the two.
 */
private fun Method.isMediaLongPressHandler(eventDescriptor: String): Boolean {
    val instructions = implementation?.instructions?.toList() ?: return false
    var hasInstanceOf = false
    var buildsSheet = false
    var readsRectOrScale = false
    var scribesLongPress = false
    for (instruction in instructions) {
        when (instruction.opcode) {
            Opcode.INSTANCE_OF ->
                if (instruction.getReference<TypeReference>()?.type == eventDescriptor) {
                    hasInstanceOf = true
                }
            Opcode.NEW_INSTANCE ->
                if (instruction.getReference<TypeReference>()?.type == MEDIA_SHEET_DESCRIPTOR) {
                    buildsSheet = true
                }
            Opcode.INVOKE_DIRECT -> {
                val reference = instruction.getReference<MethodReference>()
                if (reference?.definingClass == MEDIA_SHEET_DESCRIPTOR && reference.name == "<init>") {
                    buildsSheet = true
                }
            }
            Opcode.IGET_OBJECT -> {
                val field = instruction.getReference<FieldReference>()
                if (field?.definingClass == eventDescriptor && (field.name == "b" || field.name == "c")) {
                    readsRectOrScale = true
                }
            }
            Opcode.CONST_STRING ->
                if (instruction.getReference<StringReference>()?.string == "long_press") {
                    scribesLongPress = true
                }
            else -> Unit
        }
    }
    return hasInstanceOf && buildsSheet && readsRectOrScale && scribesLongPress
}

/**
 * Rewrites the extension's MediaLongPress.longPressAction() stub to return
 * the constant mapped from the mediaLongPressAction patch option
 * (0=copy, 1=download, 2=choose; the legacy key/title for "both" also maps
 * to 2). Same stub-body idiom as the patch-time bridges: a const + return
 * inserted at index 0 makes the stock body unreachable.
 */
context(BytecodePatchContext)
internal fun rewriteLongPressAction(actionCode: Int) {
    val extensionClass = mutableClassDefBy(MEDIA_LONG_PRESS_EXT)
    val stub = extensionClass.methods.singleOrNull { method ->
        method.name == "longPressAction" &&
            method.parameterTypes.isEmpty() &&
            method.returnType == "I" &&
            AccessFlags.STATIC.isSet(method.accessFlags)
    } ?: throw PatchException("Expected one NewX media long-press action stub in $extensionClass")

    val registerCount = stub.implementation?.registerCount ?: 0
    val target = if (registerCount >= 1) {
        stub
    } else {
        val (expanded, _) = stub.addFreeRegisters(1 - registerCount)
        extensionClass.methods.remove(stub)
        extensionClass.methods.add(expanded)
        expanded
    }
    target.insertHook(0, relocateBranchTargets = false) {
        constInt(0, actionCode)
        returnValue(0)
    }
}

context(BytecodePatchContext)
private fun resolveKotlinUnitSingleton(): FieldReference {
    val match = requireOneMatchHere(
        "Kotlin Unit initializer",
        Fingerprint(
            definingClass = "Lkotlin/Unit;",
            name = "<clinit>",
            returnType = "V",
            parameters = emptyList(),
            filters = listOf(
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
