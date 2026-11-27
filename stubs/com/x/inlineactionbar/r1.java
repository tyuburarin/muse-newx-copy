package com.x.inlineactionbar;

/**
 * Compile-only stub for the app's inline action-bar state (12.43.0-prod.01:
 * {@code public final class com.x.inlineactionbar.r1}; toString label
 * "InlineActionBarState"). It exists so the extension can declare the focal
 * bar wrap hook with the state's EXACT dex type (see HeaderCopyButton's
 * stub note): the hook's move-result writes back into the register that
 * feeds the bar composable, whose first parameter is this type — an
 * Object-typed return would poison the register for ART's verifier
 * (the v0.2.4 VerifyError lesson). The wrap itself works reflectively
 * (fields a–f, the six-argument primary constructor).
 *
 * This class is compiled to a separate directory used only as a javac
 * classpath entry; it is NEVER packaged into the extension dex (build.sh
 * asserts that no com/x/** class leaks into it). At runtime the references
 * resolve against the app's real class.
 */
public final class r1 {
    private r1() {
    }
}
