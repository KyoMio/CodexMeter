package com.kmnexus.codexmeter.domain.update

/** Compares user-facing app version names without build suffixes such as `-debug`. */
object AppVersionComparator {
    fun isRemoteVersionNewer(currentVersionName: String, remoteVersionName: String): Boolean {
        val current = AppVersion.parse(currentVersionName) ?: return false
        val remote = AppVersion.parse(remoteVersionName) ?: return false
        return remote > current
    }
}

private data class AppVersion(private val parts: List<Int>) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        val maxSize = maxOf(parts.size, other.parts.size)
        for (index in 0 until maxSize) {
            val left = parts.getOrElse(index) { 0 }
            val right = other.parts.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    companion object {
        fun parse(rawVersionName: String): AppVersion? {
            val base = rawVersionName
                .trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('-')
                .substringBefore('+')
                .trim()
            if (base.isBlank()) return null
            val parsedParts = base.split('.')
                .map { part -> part.takeIf { it.all(Char::isDigit) && it.isNotBlank() }?.toIntOrNull() }
            if (parsedParts.any { it == null }) return null
            return AppVersion(parsedParts.filterNotNull())
        }
    }
}
