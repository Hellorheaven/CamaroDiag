package com.camarodiag.gen5.ui

import com.camarodiag.connectors.J1979LiveNativeReplay

/** CSV projection of a verified original CL/1 recording; never applies an
 * undocumented conversion or presents a raw value as a numeric measurement. */
internal object J1979NativeLiveCsv {
    private const val MAX_BYTES=40*1024*1024
    fun encode(replay: J1979LiveNativeReplay.Result): ByteArray {
        require(replay.captureIntegrityVerified)
        val profile=requireNotNull(replay.profile)
        val result=requireNotNull(replay.result)
        val samples=result["samples"] as? List<*> ?: error("Missing samples")
        require(samples.size<=J1979LiveNativeReplay.MAX_SAMPLES)
        val out=StringBuilder("cycle,pid_hex,elapsed_ms,status,response_hex,request_can_id,response_can_id,vin,source_completed\r\n")
        fun integer(value: Any?, min: Int, max: Int): Int {
            val n=when(value) {
                is Int -> value.toLong()
                is Long -> value
                else -> error("Invalid integer")
            }
            require(n in min.toLong()..max.toLong())
            return n.toInt()
        }
        for(item in samples) {
            val row=item as? Map<*,*> ?: error("Invalid sample")
            val cycle=integer(row["cycle"],1,profile.cycles)
            val pid=integer(row["pid"],1,255)
            require(pid in profile.pids())
            val elapsed=integer(row["elapsed_ms"],0,604000)
            val status=row["status"] as? String ?: error("Missing status")
            require(status in setOf("RAW","NEGATIVE","TIMEOUT"))
            val response=row["response_hex"] as? String
            require(response==null || (response.length<=8190 && response.length%2==0 &&
                response.all { it in "0123456789ABCDEF" }))
            require((status=="TIMEOUT")==(response==null))
            out.append(cycle).append(',')
                .append(pid.toString(16).uppercase(java.util.Locale.ROOT).padStart(2,'0')).append(',')
                .append(elapsed).append(',').append(status).append(',').append(response.orEmpty()).append(',')
                .append(profile.tx.toString(16).uppercase(java.util.Locale.ROOT)).append(',')
                .append(profile.rx.toString(16).uppercase(java.util.Locale.ROOT)).append(',')
                .append(profile.vin).append(',').append(replay.completed()).append("\r\n")
            require(out.length<=MAX_BYTES)
        }
        return out.toString().toByteArray(Charsets.US_ASCII)
    }
}
