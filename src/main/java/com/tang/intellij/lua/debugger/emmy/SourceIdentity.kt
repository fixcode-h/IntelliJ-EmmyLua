package com.tang.intellij.lua.debugger.emmy

data class SourceIdentity(
    val uri: String,
    val canonicalPath: String,
    val sourceHash: String? = null,
    val loaderEpoch: Long? = null,
    val verified: Boolean = false,
    val sourceEpoch: Long? = null,
    /** True when sourceHash covers the complete file rather than only a prefix. */
    val hashComplete: Boolean = true
) {
    val effectiveEpoch: Long? get() = sourceEpoch ?: loaderEpoch

    fun matches(other: SourceIdentity): Boolean {
        if (normalizePath(canonicalPath) != normalizePath(other.canonicalPath)) return false
        if (sourceHash != null && other.sourceHash != null && sourceHash != other.sourceHash) return false
        if (effectiveEpoch != null && other.effectiveEpoch != null && effectiveEpoch != other.effectiveEpoch) return false
        return verified && other.verified
    }

    fun toWire(): SourceIdentityWire = SourceIdentityWire(
        canonicalPath = canonicalPath,
        uri = uri,
        sourceHash = sourceHash,
        loaderEpoch = loaderEpoch,
        sourceEpoch = sourceEpoch,
        verified = verified
    )

    companion object {
        private const val DEFAULT_HASH_LIMIT = 1024 * 1024

        fun fromPath(path: String, hashLimitBytes: Int = DEFAULT_HASH_LIMIT): SourceIdentity {
            val normalized = normalizePath(path)
            val file = java.io.File(path)
            val exists = file.isFile
            val canonical = if (exists) normalizePath(runCatching { file.canonicalPath }.getOrNull() ?: path) else normalized
            val hashResult = if (exists) boundedSha256(file, hashLimitBytes) else null
            return SourceIdentity(
                uri = toFileUri(canonical),
                canonicalPath = canonical,
                sourceHash = hashResult?.hash,
                verified = exists && hashResult?.complete == true,
                hashComplete = hashResult?.complete == true
            )
        }

        fun normalizePath(path: String): String {
            if (path.isBlank()) return ""
            val replaced = path.trim().replace('\\', '/')
            val hasDrive = replaced.length >= 2 && replaced[1] == ':'
            val drivePrefix = replaced.takeIf { hasDrive }
                ?.let { it.substring(0, 2).lowercase() + if (it.length > 2 && it[2] == '/') "/" else "" }
            val prefix = drivePrefix ?: when {
                replaced.startsWith("//") -> "//"
                replaced.startsWith('/') -> "/"
                else -> ""
            }
            val bodyText = when {
                hasDrive -> replaced.substring(if (replaced.length > 2 && replaced[2] == '/') 3 else 2)
                replaced.startsWith("//") -> replaced.substring(2)
                replaced.startsWith('/') -> replaced.substring(1)
                else -> replaced
            }
            val body = bodyText.split('/').filter { it.isNotEmpty() && it != "." }
            val parts = ArrayDeque<String>()
            body.forEach { part ->
                if (part == ".." && parts.isNotEmpty() && parts.last() != "..") parts.removeLast()
                else if (part != "..") parts.addLast(part)
            }
            val joined = parts.joinToString("/")
            val normalized = prefix + joined
            val pathWithPlatformCase = if (isWindows()) normalized.lowercase() else normalized
            return if (pathWithPlatformCase.length > prefix.length) pathWithPlatformCase.trimEnd('/') else pathWithPlatformCase
        }

        private fun isWindows(): Boolean =
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

        private fun toFileUri(path: String): String = try {
            java.io.File(path).toURI().toString()
        } catch (_: Throwable) {
            "file:///${path.replace('\\', '/')}"
        }

        private data class HashResult(val hash: String, val complete: Boolean)

        private fun boundedSha256(file: java.io.File, limit: Int): HashResult? = runCatching {
            if (limit <= 0) return@runCatching null
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val complete = file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var remaining = limit
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    remaining -= read
                }
                remaining > 0 || input.read() < 0
            }
            HashResult(
                digest.digest().joinToString("") { "%02x".format(it) },
                complete = complete
            )
        }.getOrNull()
    }
}
