package com.tang.intellij.lua.debugger.emmy

data class SourceIdentity(
    val uri: String,
    val canonicalPath: String,
    val sourceHash: String? = null,
    val loaderEpoch: Long? = null,
    val verified: Boolean = false
) {
    fun matches(other: SourceIdentity): Boolean {
        if (canonicalPath.isNotBlank() && other.canonicalPath.isNotBlank() &&
            !canonicalPath.equals(other.canonicalPath, ignoreCase = true)) return false
        if (sourceHash != null && other.sourceHash != null && sourceHash != other.sourceHash) return false
        if (loaderEpoch != null && other.loaderEpoch != null && loaderEpoch != other.loaderEpoch) return false
        return verified && other.verified
    }
}
