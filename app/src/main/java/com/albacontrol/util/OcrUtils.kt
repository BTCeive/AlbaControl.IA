package com.albacontrol.util

object OcrUtils {
    fun normalizeToken(s: String): String {
        return s.filter { it.isLetterOrDigit() }.lowercase().trim()
    }

    // Fuzzy contains: normalized substring or simple Levenshtein ratio check
    fun fuzzyContains(haystack: String, needle: String, threshold: Double = 0.6): Boolean {
        val h = normalizeToken(haystack)
        val n = normalizeToken(needle)
        if (h.contains(n) || n.contains(h)) return true
        val r = levenshteinRatio(h, n)
        return r >= threshold
    }

    private fun levenshteinRatio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val d = levenshteinDistance(a, b)
        val max = kotlin.math.max(a.length, b.length)
        return 1.0 - d.toDouble() / max.toDouble()
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) dp[i][0] = i
        for (j in 0..m) dp[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[n][m]
    }
}
