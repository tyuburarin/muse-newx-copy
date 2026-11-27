package local.muse.newx.utils

/**
 * Loads an extension dex bundled inside this .mpp through the bundle's own
 * class loader (the same idiom the Muse patches use). The
 * BytecodePatchBuilder.extendWith(String) overload does not resolve bundle
 * resources, which left extension classes unmatched.
 */
private object BundledResourceAnchor

fun app.morphe.patcher.patch.BytecodePatchBuilder.extendWithBundled(extensionFileName: String) {
    val classLoader = BundledResourceAnchor::class.java.classLoader
    extendWith(
        java.util.function.Supplier<java.io.InputStream> {
            classLoader.getResourceAsStream(extensionFileName)
        },
    )
}
