package com.camarodiag.gen5.ui

import com.camarodiag.gen5.R
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class CanTraceInputException(val messageRes: Int) : Exception()

internal suspend fun InputStream.readCanTraceText(maxBytes: Int): String {
    val contents = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val context = currentCoroutineContext()
    while (true) {
        context.ensureActive()
        val count = read(buffer)
        if (count < 0) break
        if (count > maxBytes - contents.size()) {
            throw CanTraceInputException(R.string.msg_trace_too_large)
        }
        contents.write(buffer, 0, count)
    }
    if (contents.size() == 0) throw CanTraceInputException(R.string.msg_trace_empty)
    return contents.toString(Charsets.UTF_8.name())
}
