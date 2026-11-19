package com.camarodiag.gen5.data.obd

import java.util.Locale

/**
 * User-selected filters for an already captured CAN trace.
 *
 * Filtering is deliberately offline: it never changes adapter state or bus traffic.
 * A null criterion means "do not filter on this field".
 */
data class CanTraceFilter(
    val canId: Int? = null,
    val direction: CanTraceDirection? = null,
    val dataPrefix: List<Int> = emptyList(),
    val searchText: String = "",
    val fromTimestampSeconds: Double? = null,
    val toTimestampSeconds: Double? = null
) {
    init {
        require(canId == null || canId in 0..0x1FFFFFFF) {
            "CAN ID must be a valid 11-bit or 29-bit identifier."
        }
        require(dataPrefix.all { it in 0..0xFF }) {
            "Every data-prefix value must be a byte."
        }
        require(fromTimestampSeconds == null || fromTimestampSeconds.isFinite())
        require(toTimestampSeconds == null || toTimestampSeconds.isFinite())
        require(
            fromTimestampSeconds == null || toTimestampSeconds == null ||
                fromTimestampSeconds <= toTimestampSeconds
        ) { "The start timestamp must not be after the end timestamp." }
    }

    fun matches(frame: CanTraceFrame): Boolean {
        if (canId != null && frame.canId != canId) return false
        if (direction != null && frame.direction != direction) return false
        if (dataPrefix.size > frame.data.size) return false
        if (dataPrefix.isNotEmpty() && frame.data.take(dataPrefix.size) != dataPrefix) return false

        if (fromTimestampSeconds != null || toTimestampSeconds != null) {
            val timestamp = frame.timestampSeconds ?: return false
            // NaN compares false against both bounds, so reject non-finite trace
            // timestamps explicitly instead of allowing them through a bounded filter.
            if (!timestamp.isFinite()) return false
            if (fromTimestampSeconds != null && timestamp < fromTimestampSeconds) return false
            if (toTimestampSeconds != null && timestamp > toTimestampSeconds) return false
        }

        val query = searchText.trim()
        if (query.isEmpty()) return true

        val normalizedQuery = query.uppercase(Locale.ROOT)
            .removePrefix("0X")
            .replace(Regex("[\\s:_-]+"), "")
        val idHex = frame.canId.toString(16).uppercase(Locale.ROOT)
        val paddedIdHex = idHex.padStart(if (frame.canId <= 0x7FF) 3 else 8, '0')
        val dataHex = frame.data.joinToString("") {
            it.toString(16).uppercase(Locale.ROOT).padStart(2, '0')
        }
        val rawHex = frame.data.joinToString(" ") {
            it.toString(16).uppercase(Locale.ROOT).padStart(2, '0')
        }
        val searchable = buildString {
            append(idHex)
            append(' ')
            append(paddedIdHex)
            append(' ')
            append(rawHex)
            append(' ')
            append(dataHex)
            append(' ')
            append(frame.direction.name)
            append(' ')
            append(frame.lineNumber)
        }.uppercase(Locale.ROOT).replace(Regex("[\\s:_-]+"), "")

        return searchable.contains(normalizedQuery)
    }

    companion object {
        /** Parse a user-entered CAN identifier without silently truncating it. */
        fun parseCanId(value: String): Int? {
            val normalized = value.trim().removePrefix("0x").removePrefix("0X")
            if (normalized.isEmpty() || normalized.length > 8 ||
                normalized.any { it.digitToIntOrNull(16) == null }
            ) return null
            return normalized.toLongOrNull(16)
                ?.takeIf { it in 0L..0x1FFFFFFFL }
                ?.toInt()
        }

        /** Parse a byte-prefix such as "03 22 F1" or "0x03,0x22,0xF1". */
        fun parseDataPrefix(value: String): List<Int>? {
            val normalized = value.trim()
            if (normalized.isEmpty()) return emptyList()
            val tokens = normalized.split(Regex("[\\s,;:]+"))
                .filter(String::isNotBlank)
            val bytes = mutableListOf<Int>()
            for (token in tokens) {
                val hex = token.removePrefix("0x").removePrefix("0X")
                if (hex.length != 2 || hex.any { it.digitToIntOrNull(16) == null }) {
                    return null
                }
                bytes += hex.toInt(16)
            }
            return bytes
        }
    }
}

/** Apply a filter while preserving capture order. A non-positive limit returns no rows. */
fun List<CanTraceFrame>.filterCanTrace(
    filter: CanTraceFilter,
    maxResults: Int = Int.MAX_VALUE
): List<CanTraceFrame> {
    if (maxResults <= 0) return emptyList()
    return asSequence()
        .filter(filter::matches)
        .take(maxResults)
        .toList()
}
