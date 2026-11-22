package com.camarodiag.gen5.data.obd

/**
 * Display-only selector populated from CAN identifiers actually observed on
 * the connected bus. An identifier is NOT proof of a named ECU identity.
 * The diagnostic engine never consumes this filter.
 */
object CanConsoleDisplayFilter {
    private val historyCanId = Regex("""^CAN ID=([0-9A-Fa-f]{3}|[0-9A-Fa-f]{8})\\s+DLC=""")
    fun observedIds(history: List<ParsedCanConsoleRecord>, traceIds: Collection<Int>): List<Long> =
        (traceIds.map { it.toLong() } +
            history.mapNotNull { entry ->
                if (entry.direction != "RX") null
                else historyCanId.find(entry.content)?.groupValues?.get(1)?.toLongOrNull(16)
            })
            .filter { it in 0..0x1FFFFFFFL }
            .distinct()
            .sorted()

    fun visible(entry: ParsedCanConsoleRecord, selectedId: Long?): Boolean {
        if (selectedId == null || entry.direction == "SYSTEM") return true
        val id = if (entry.direction == "RX") {
            historyCanId.find(entry.content)?.groupValues?.get(1)?.toLongOrNull(16)
        } else {
            RawCanFrameParser.parse(entry.content)?.canId
        }
        return id == selectedId
    }

    fun visibleId(frameId: Long, selectedId: Long?): Boolean =
        selectedId == null || frameId == selectedId

    fun displayId(id: Long): String =
        id.toString(16).uppercase().padStart(if (id <= 0x7FF) 3 else 8, '0')
}
