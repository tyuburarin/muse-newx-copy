package local.muse.newx

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object NewXConstants {
    /**
     * Standalone counterpart of Piko's COMPATIBILITY_NEW_X, narrowed to the single
     * release this bundle was built and verified against: X 12.43.0-prod.01.
     */
    val COMPATIBILITY_NEW_X_1243 =
        Compatibility(
            name = "NewX",
            packageName = "com.twitter.android",
            apkFileType = ApkFileType.APKM,
            appIconColor = 0x000000,
            targets =
                listOf(
                    AppTarget(
                        version = "12.43.0-prod.01",
                    ),
                ),
        )

    const val EXTENSION_DEX = "extensions/muse-newx.dex"
}
