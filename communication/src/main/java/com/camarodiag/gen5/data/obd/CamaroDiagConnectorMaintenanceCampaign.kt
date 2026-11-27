package com.camarodiag.gen5.data.obd

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.camarodiag.connectors.J1979MaintenanceJournal
import com.camarodiag.connectors.J1979MaintenanceUsb
import com.camarodiag.connectors.UsbJ1979
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** One explicitly approved CU/4 operation. Original journal and a private
 * pre-transmission marker survive a cancellation or process interruption.
 * No automatic retry and no generic write API. */
object CamaroDiagConnectorMaintenanceCampaign {
    data class Target(val channel: CamaroDiagConnectorNativeCampaign.Target,val operation: Int) {
        val profile: J1979MaintenanceUsb.Profile get() = J1979MaintenanceUsb.Profile(
            operation,channel.tx,channel.rx,channel.extended,channel.vehicle,channel.budgetMs)
        val valid: Boolean get()=channel.valid&&runCatching { profile }.isSuccess
    }
    data class Evidence(val journal: File,val report: File,val sha256: String?,
                        val document: Map<String,Any?>,val target: Target) {
        val verified: Boolean get()=document["journal_transfer_verified"]==true
        val recoveryRequired: Boolean get()=document["recovery_required"]==true
        val terminal: Map<*,*>? get()=(document["inspection"] as? Map<*,*>)?.get("terminal") as? Map<*,*>
    }
    fun digest(file: File): String {
        val hash=MessageDigest.getInstance("SHA-256")
        var size=0L
        file.inputStream().buffered().use { input ->
            val buffer=ByteArray(8192)
            while(true){
                val count=input.read(buffer)
                if(count<0)break
                check(count>0)
                size+=count
                require(size<=J1979MaintenanceJournal.MAX_JOURNAL)
                hash.update(buffer,0,count)
            }
        }
        return ElmJ1979EvapTest.hex(hash.digest()).lowercase(java.util.Locale.ROOT)
    }
    private fun writeNew(file: File,value: Map<String,Any?>){
        val bytes=JSONObject(value).toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size<=1024*1024)
        FileChannel.open(file.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use { channel ->
            val buffer=java.nio.ByteBuffer.wrap(bytes)
            while(buffer.hasRemaining())check(channel.write(buffer)>0)
            channel.force(true)
        }
    }
    private fun readBounded(file: File): ByteArray {
        val limit=J1979MaintenanceJournal.MAX_JOURNAL
        require(file.isFile&&file.length()<=limit)
        val output=java.io.ByteArrayOutputStream()
        file.inputStream().use { input ->
            val buffer=ByteArray(8192)
            while(true){
                val n=input.read(buffer)
                if(n<0)break
                check(n>0&&n<=limit-output.size())
                output.write(buffer,0,n)
            }
        }
        return output.toByteArray()
    }
    suspend fun run(manager: UsbManager,device: UsbDevice,target: Target,credential: ByteArray,
                    folder: File,appVersion: String,stillApproved:(Long)->Boolean): Evidence =
        withContext(Dispatchers.IO) {
            require(target.valid&&folder.isDirectory&&credential.size==32&&appVersion.isNotBlank()&&
                target.channel.connector==device.deviceName)
            val job=currentCoroutineContext()
            fun current()=job.isActive&&stillApproved(target.channel.connectionGeneration)&&
                manager.hasPermission(device)&&manager.deviceList[device.deviceName]==device
            check(current())
            val stem="j1979-maintenance-"+UUID.randomUUID()
            val marker=File(folder,stem+".pending.json")
            val journal=File(folder,stem+".cm")
            val report=File(folder,stem+".json")
            val started=Instant.now()
            val profile=target.profile
            // Reserve the final report before USB and durably persist a
            // marker. A marker without a final report is never success.
            writeNew(marker,linkedMapOf("schema" to "camarodiag-j1979-native-maintenance-pending-v1",
                "scope" to profile.view(),"started_at_utc" to started.toString(),
                "raw_journal_name" to journal.name,"final_report_name" to report.name,
                "command_outcome_unknown" to true,"permits_automatic_retry" to false))
            val key=credential.copyOf()
            var document: MutableMap<String,Any?>?=null
            var sha: String?=null
            var enteredUsb=false
            try {
                FileChannel.open(report.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use { destination ->
                    try {
                        FileChannel.open(journal.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use { raw ->
                            check(current())
                            enteredUsb=true
                            document=UsbJ1979.captureMaintenance(manager,device,target.channel.controlId,target.channel.dataId,
                                profile,key,Channels.newOutputStream(raw),::current).toMutableMap()
                            raw.force(true)
                        }
                        sha=digest(journal)
                        val value=requireNotNull(document)
                        if(value["journal_transfer_verified"]==true){
                            val saved=J1979MaintenanceJournal.inspect(readBounded(journal),profile.vin)
                            check(saved==value["inspection"]&&digest(journal)==sha) { "Persisted CM/1 differs from transfer" }
                        }
                    }catch(failure:Exception){
                        val value=document ?: linkedMapOf<String,Any?>(
                            "schema" to "camarodiag-functional-j1979-native-maintenance-v1","protocol" to "SAE-J1979",
                            "source" to "LIVE_USB_CU4","scope" to profile.view(),
                            "device_credential_authenticated" to false,"client_proof_sent" to false,
                            "proof_transmission_attempted" to null,"journal_bytes_received" to null,
                            "declared_recovery_in_envelope" to null,"host_elapsed_ms" to null).also { document=it }
                        val possible=value["proof_transmission_attempted"]!=false&&enteredUsb
                        value["journal_transfer_verified"]=false
                        value["terminal_present"]=false
                        value["inspection"]=null
                        value["recovery_required"]=possible
                        value["command_outcome_unknown"]=possible
                        value["error"]=failure.message ?: failure.javaClass.simpleName
                    }
                    val value=requireNotNull(document)
                    if(!current()){
                        val possible=value["proof_transmission_attempted"]!=false&&enteredUsb
                        value["cancelled"]=true
                        value["journal_transfer_verified"]=false
                        value["terminal_present"]=false
                        value["inspection"]=null
                        value["recovery_required"]=possible
                        value["command_outcome_unknown"]=possible
                        value["error"]="CU/4 context revoked"
                    }
                    value["cancelled"]=value["cancelled"]==true
                    value["physical_vehicle_verified"]=false
                    value["evidence_authenticated"]=false
                    value["operator_consent_independently_verified"]=false
                    value["permits_automatic_retry"]=false
                    value["permits_programming"]=false
                    value["version"]=appVersion
                    value["declared_connector"]=device.deviceName
                    value["connection_generation"]=target.channel.connectionGeneration
                    value["started_at_utc"]=started.toString()
                    value["finished_at_utc"]=maxOf(started,Instant.now()).toString()
                    value["journal_sha256"]=sha
                    value["raw_journal_name"]=journal.name
                    val bytes=JSONObject(value).toString(2).toByteArray(Charsets.UTF_8)
                    require(bytes.size<=1024*1024)
                    val buffer=java.nio.ByteBuffer.wrap(bytes)
                    while(buffer.hasRemaining())check(destination.write(buffer)>0)
                    destination.force(true)
                }
            } finally { key.fill(0) }
            Evidence(journal,report,sha,requireNotNull(document).toMap(),target)
        }
}
