package com.camarodiag.gen5.data.obd

/**
 * A CAN identifier is composed of the numeric arbitration ID AND its format.
 * 7E8 (11-bit) and 000007E8 (29-bit) are distinct bus addresses.
 */
data class ObservedCanId(val value: Long, val extended: Boolean) {
    init {
        require(value >= 0 && value <= if (extended) 0x1FFFFFFFL else 0x7FFL)
    }

    val hex: String
        get() = value.toString(16).uppercase().padStart(if (extended) 8 else 3, '0')
}

/** Presentation only. Never modify stored capture or diagnostic correlation. */
object CanConsoleDisplayFilter {
    private val historyCanId = Regex("""^CAN ID=([0-9A-Fa-f]{3}|[0-9A-Fa-f]{8})\s+DLC=""")

    private fun receivedId(entry: ParsedCanConsoleRecord): ObservedCanId? {
        if (entry.direction != "RX") return null
        val token = historyCanId.find(entry.content)?.groupValues?.get(1) ?: return null
        val id = token.toLongOrNull(16) ?: return null
        return runCatching { ObservedCanId(id, token.length == 8) }.getOrNull()
    }

    fun observedIds(
        history: List<ParsedCanConsoleRecord>,
        traceIds: Collection<Int>
    ): List<ObservedCanId> {
        // The existing offline trace model does not retain the original CAN ID width.
        // An ID <= 0x7FF in that model is ambiguous and MUST NOT be promoted to
        // a verified 11-bit address. History RX entries preserve the width.
        val unambiguousTrace = traceIds.asSequence()
            .filter { it in 0x800..0x1FFFFFFF }
            .map { ObservedCanId(it.toLong(), extended = true) }
        return (history.asSequence().mapNotNull(::receivedId) + unambiguousTrace)
            .distinct().sortedWith(compareBy({ it.value }, { it.extended })).toList()
    }

    /** Retain IDs seen earlier in a live session even after the visible history
     * ring buffer has discarded their RX lines. Cap the catalogue for hostile buses. */
    fun accumulateObserved(
        current: Set<ObservedCanId>,
        batch: List<ParsedCanConsoleRecord>
    ): Set<ObservedCanId> {
        if (current.size >= 4_096) return current
        val result = current.toMutableSet()
        for (entry in batch) {
            receivedId(entry)?.let { result.add(it) }
            if (result.size >= 4_096) break
        }
        return result
    }

    /** Use width-preserving trace frames when available, never a guessed ID type. */
    fun observedFrameIds(
        history: List<ParsedCanConsoleRecord>,
        frames: Collection<CanTraceFrame>,
        previouslyObserved: Set<ObservedCanId> = emptySet()
    ): List<ObservedCanId> =
        (previouslyObserved.asSequence() + history.asSequence().mapNotNull(::receivedId) +
            frames.asSequence().mapNotNull { frame ->
                frame.extendedId?.let { extended ->
                    runCatching { ObservedCanId(frame.canId.toLong(), extended) }.getOrNull()
                }
            })
            .distinct().sortedWith(compareBy({ it.value }, { it.extended })).toList()

    fun visibleTrace(frame: CanTraceFrame, selectedId: ObservedCanId?): Boolean =
        selectedId == null ||
            (frame.extendedId != null && frame.canId.toLong() == selectedId.value &&
                frame.extendedId == selectedId.extended)

    fun visible(entry: ParsedCanConsoleRecord, selectedId: ObservedCanId?): Boolean {
        if (selectedId == null || entry.direction == "SYSTEM") return true
        return when (entry.direction) {
            "RX" -> receivedId(entry) == selectedId
            "TX" -> {
                val request = RawCanFrameParser.parse(entry.content)
                request != null && request.isExtended == selectedId.extended &&
                    (request.canId == selectedId.value ||
                        CanConsoleEcuRouting.expectedResponder(request) == selectedId.value)
            }
            else -> false
        }
    }

    /**
     * Offline trace frames have no 11-/29-bit flag. For IDs <= 0x7FF,
     * do not assert membership in a specific format; this avoids showing
     * unrelated standard frames under an extended-ID selection.
     */
    fun visibleId(frameId: Long, selectedId: ObservedCanId?): Boolean =
        selectedId == null || (frameId > 0x7FFL &&
            selectedId.extended && selectedId.value == frameId)

    fun displayId(id: ObservedCanId): String = id.hex
}
