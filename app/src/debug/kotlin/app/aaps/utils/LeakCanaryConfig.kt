package app.aaps.utils

import app.aaps.core.interfaces.utils.fabric.FabricPrivacy

/**
 * Fork: LeakCanary is not included in debug builds either (see app/build.gradle.kts),
 * so this matches the release stub.
 */
@Suppress("UNUSED_PARAMETER")
fun configureLeakCanary(isEnabled: Boolean = false, fabricPrivacy: FabricPrivacy? = null) {
    // do nothing - LeakCanary is not available
}
