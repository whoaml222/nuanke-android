package com.nuanke.focus.domain

data class ForegroundEntry(
    val previousPackage: String?,
    val packageName: String,
)

/**
 * Converts noisy accessibility window events into real foreground-app entries.
 * Ignored packages never mutate state, so an accessibility overlay owned by
 * 暖刻 cannot make the restricted app look as if it was opened repeatedly.
 */
class ForegroundEntryTracker(private val ignoredPackages: Set<String>) {
    private var activePackage: String? = null
    private var blockedPackageForEntry: String? = null

    fun observe(packageName: String): ForegroundEntry? {
        if (packageName in ignoredPackages || packageName == activePackage) return null
        val entry = ForegroundEntry(activePackage, packageName)
        activePackage = packageName
        blockedPackageForEntry = null
        return entry
    }

    fun markBlocked(packageName: String): Boolean {
        if (packageName != activePackage || blockedPackageForEntry == packageName) return false
        blockedPackageForEntry = packageName
        return true
    }

    fun isBlocked(packageName: String): Boolean = blockedPackageForEntry == packageName

    fun releaseBlock() { blockedPackageForEntry = null }

    fun reset() {
        activePackage = null
        blockedPackageForEntry = null
    }
}
