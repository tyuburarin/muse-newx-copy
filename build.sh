#!/usr/bin/env bash
# Muse NewX Copy Tools (.mpp) build script — no Gradle.
# Same hand-build pipeline as the Muse patches (see BUILD.md):
# javac -> d8 for the extension, embeddable kotlinc against the Morphe
# Desktop 2.1.1 fat jar for the patches, d8 merge, zip packaging.
set -euo pipefail
cd "$(dirname "$0")"

VERSION="${VERSION:-0.1.0}"

# --- Toolchain locations (override via environment) ---
JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk17}"                              # JDK 17
GRADLE_LIB="${GRADLE_LIB:-$HOME/tools/gradle-8.13/lib}"                  # Gradle 8.13 distribution lib/
MORPHE_JAR="${MORPHE_JAR:-$HOME/workspace/piko-build/morphe.jar}"        # Morphe Desktop 2.1.1 fat jar
ANDROID_JAR="${ANDROID_JAR:-$HOME/android-sdk/platforms/android-36/android.jar}"
D8="${D8:-$HOME/android-sdk/build-tools/36.0.0/d8}"

for f in "$MORPHE_JAR" "$ANDROID_JAR" "$GRADLE_LIB/kotlin-compiler-embeddable-2.0.21.jar"; do
  [ -f "$f" ] || { echo "missing dependency: $f (see BUILD.md)" >&2; exit 1; }
done
export PATH="$JAVA_HOME/bin:$PATH"   # d8 is a shell script that calls `java`

OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/classes-ext" "$OUT/classes-stubs" "$OUT/dex-ext" "$OUT/dex-patch" "$OUT/pkg/extensions"

# --- 1) Extension (Java) -> extensions/muse-newx.dex ---
# Compile-only stubs for app types the extension's hook methods must name
# with EXACT dex types (com.x.icons.b, com.x.urt.items.post.y1/e6 — see
# stubs/ and BUILD.md v0.2.5). They go on the javac classpath only and are
# never packaged: the jar below is built from classes-ext alone, and the
# leak check after d8 fails the build if any com/x/** class slips in.
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$GRADLE_LIB/kotlin-stdlib-2.0.21.jar" -d "$OUT/classes-stubs" $(find stubs -name '*.java')
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$ANDROID_JAR:$GRADLE_LIB/kotlin-stdlib-2.0.21.jar:$OUT/classes-stubs" -d "$OUT/classes-ext" $(find src-java -name '*.java')
"$JAVA_HOME/bin/jar" --create --file "$OUT/ext.jar" -C "$OUT/classes-ext" .
"$D8" --output "$OUT/dex-ext" "$OUT/ext.jar"
if "$JAVA_HOME/bin/jar" --list --file "$OUT/ext.jar" | grep -q '^com/x/'; then
  echo "compile-only stub leaked into the extension jar" >&2; exit 1
fi
cp "$OUT/dex-ext/classes.dex" "$OUT/pkg/extensions/muse-newx.dex"

# --- 2) Patches (Kotlin) -> patches.jar ---
KOTLINC_CP="$GRADLE_LIB/kotlin-compiler-embeddable-2.0.21.jar"
KOTLINC_CP="$KOTLINC_CP:$GRADLE_LIB/kotlin-stdlib-2.0.21.jar"
KOTLINC_CP="$KOTLINC_CP:$GRADLE_LIB/kotlinx-coroutines-core-jvm-1.6.4.jar"
KOTLINC_CP="$KOTLINC_CP:$GRADLE_LIB/trove4j-1.0.20200330.jar"
KOTLINC_CP="$KOTLINC_CP:$GRADLE_LIB/annotations-24.0.1.jar"
"$JAVA_HOME/bin/java" -cp "$KOTLINC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$MORPHE_JAR:$GRADLE_LIB/kotlin-stdlib-2.0.21.jar" \
  -Xcontext-receivers -Xskip-metadata-version-check -Xskip-prerelease-check \
  -jvm-target 11 \
  -d "$OUT/patches.jar" $(find src-kt -name '*.kt')

# --- 3) Bundle-root classes.dex (patch classes, dexed by d8) ---
"$D8" --output "$OUT/dex-patch" "$OUT/patches.jar"
cp "$OUT/dex-patch/classes.dex" "$OUT/pkg/classes.dex"

# --- 4) Package the .mpp (plain zip) ---
(cd "$OUT/pkg" && "$JAVA_HOME/bin/jar" xf ../patches.jar && rm -f META-INF/MANIFEST.MF)
sed "s/^Version: .*/Version: $VERSION/" assets/MANIFEST.MF > "$OUT/pkg/META-INF/MANIFEST.MF"
cp assets/LICENSE assets/NOTICE "$OUT/pkg/"
(cd "$OUT/pkg" && zip -qr "../muse-newx-copy-$VERSION.mpp" .)

echo "built: $OUT/muse-newx-copy-$VERSION.mpp"
