package com.camarodiag.gen5.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** GitHub Actions metadata for the current open PR. ZIP downloads use the GitHub browser session. */
internal enum class DebugBuildPlatform { ANDROID, WINDOWS }

internal data class DebugBuildPullRequest(val number: Int, val branch: String)

internal data class DebugBuildArtifact(
    val id: Long,
    val name: String,
    val runId: Long,
    val headBranch: String,
    val headSha: String,
    val createdAt: String,
    val expiresAt: String,
    val expired: Boolean,
    val sizeBytes: Long
) {
    fun downloadPage(): String =
        "https://github.com/Hellorheaven/CamaroDiag/actions/runs/" +
            runId + "/artifacts/" + id
}

internal data class DebugBuildListing(
    val android: DebugBuildArtifact? = null,
    val windows: DebugBuildArtifact? = null,
    val scanned: Int = 0
)

internal object DebugBuildCatalog {
    private const val REPO_API = "https://api.github.com/repos/Hellorheaven/CamaroDiag"
    private const val API = "$REPO_API/actions/artifacts"

    private fun openPullRequest(): DebugBuildPullRequest? {
        val connection = URL("$REPO_API/pulls?state=open&per_page=100")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 12_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "CamaroDiag-Debug-Builds")
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "GitHub pull requests HTTP " + connection.responseCode
            }
            val pulls = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            var active: DebugBuildPullRequest? = null
            for (index in 0 until pulls.length()) {
                val item = pulls.optJSONObject(index) ?: continue
                if (item.optJSONObject("base")?.optString("ref") != "main" ||
                    item.optJSONObject("head")?.optJSONObject("repo")?.optString("full_name") !=
                        "Hellorheaven/CamaroDiag"
                ) continue
                val number = item.optInt("number")
                val branch = item.optJSONObject("head")?.optString("ref").orEmpty()
                if (number <= 0 || branch.isBlank()) continue
                check(active == null) { "Multiple active CamaroDiag PRs" }
                active = DebugBuildPullRequest(number, branch)
            }
            return active
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Do not mix PRs or branches and never offer expired artifacts. The capture
     * SHA in the artifact name must match GitHub's workflow_run.head_sha.
     */
    fun choose(
        candidates: List<DebugBuildArtifact>,
        platform: DebugBuildPlatform,
        pr: DebugBuildPullRequest,
        now: Instant = Instant.now()
    ): DebugBuildArtifact? {
        val prefix = when (platform) {
            DebugBuildPlatform.ANDROID -> "CamaroDiag-debug"
            DebugBuildPlatform.WINDOWS -> "CamaroDiag-J2534-Bridge-debug"
        }
        val namePattern = Regex("^$prefix-pr-${pr.number}-([a-f0-9]{40})$")
        return candidates.asSequence().filter { item ->
            val match = namePattern.matchEntire(item.name)
            item.id > 0 && item.runId > 0 && !item.expired &&
                item.headBranch == pr.branch &&
                match != null && match.groupValues[1] == item.headSha &&
                runCatching { Instant.parse(item.expiresAt).isAfter(now) }.getOrDefault(false)
        }.maxByOrNull { it.createdAt }
    }

    suspend fun fetchLatest(): DebugBuildListing = withContext(Dispatchers.IO) {
        val pr = openPullRequest() ?: return@withContext DebugBuildListing()
        val artifacts = mutableListOf<DebugBuildArtifact>()
        // Do not assume the most recent CI commit has a downloadable artifact:
        // some workflow runs intentionally skip builds (or have failed).
        for (page in 1..4) {
            val connection = URL(API + "?per_page=100&page=" + page)
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "CamaroDiag-Owner-Debug-Builds")
            try {
                check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                    "GitHub Actions HTTP " + connection.responseCode
                }
                val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val entries = response.optJSONArray("artifacts") ?: break
                for (index in 0 until entries.length()) {
                    val item = entries.optJSONObject(index) ?: continue
                    val run = item.optJSONObject("workflow_run") ?: continue
                    artifacts += DebugBuildArtifact(
                        id = item.optLong("id"),
                        name = item.optString("name"),
                        runId = run.optLong("id"),
                        headBranch = run.optString("head_branch"),
                        headSha = run.optString("head_sha"),
                        createdAt = item.optString("created_at"),
                        expiresAt = item.optString("expires_at"),
                        expired = item.optBoolean("expired", true),
                        sizeBytes = item.optLong("size_in_bytes")
                    )
                }
                if (entries.length() < 100) break
                // Stop early only when both artifacts already appear and the
                // metadata is sorted newest-first, which GitHub documents.
                if (choose(artifacts, DebugBuildPlatform.ANDROID, pr) != null &&
                    choose(artifacts, DebugBuildPlatform.WINDOWS, pr) != null
                ) break
            } finally {
                connection.disconnect()
            }
        }
        DebugBuildListing(
            android = choose(artifacts, DebugBuildPlatform.ANDROID, pr),
            windows = choose(artifacts, DebugBuildPlatform.WINDOWS, pr),
            scanned = artifacts.size
        )
    }
}
