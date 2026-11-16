package com.camarodiag.gen5.data.obd

/** VXDIAG hardware family supported by CamaroDiag's connection layer. */
enum class VxdiagModel { VCX_SE, VCX_NANO }
enum class VxdiagLink { USB, WIFI }
enum class VxdiagProtocol { J2534, ISO22900_PDU, VENDOR_NATIVE }
enum class VxdiagReadiness { READY_WITH_VENDOR_PROVIDER, PC_BRIDGE_IMPLEMENTED_REQUIRES_VENDOR_DRIVER, DEVICE_DISCOVERY_ONLY, UNSUPPORTED_LINK }

data class VxdiagDeviceProfile(
    val model: VxdiagModel,
    val link: VxdiagLink,
    val label: String,
    val protocols: Set<VxdiagProtocol>,
    val readiness: VxdiagReadiness,
    val noteFr: String
)

/**
 * Capability matrix based on published VXDIAG product specifications.
 * VCX-NANO USB and Wi-Fi are separate hardware variants. VCX-SE supports USB-C and Wi-Fi.
 * The Windows PC bridge is implemented; a registered vendor J2534 provider is still required.
 */
object VxdiagProfiles {
    fun all(): List<VxdiagDeviceProfile> = listOf(
        VxdiagDeviceProfile(VxdiagModel.VCX_SE, VxdiagLink.USB, "VCX SE USB-C", setOf(VxdiagProtocol.J2534, VxdiagProtocol.ISO22900_PDU), VxdiagReadiness.PC_BRIDGE_IMPLEMENTED_REQUIRES_VENDOR_DRIVER, "Pont PC J2534 disponible ; pilote VXDIAG Windows enregistré requis. HS-CAN et SW-ISO15765 si J2534-2 exposé par le pilote."),
        VxdiagDeviceProfile(VxdiagModel.VCX_SE, VxdiagLink.WIFI, "VCX SE Wi-Fi", setOf(VxdiagProtocol.J2534, VxdiagProtocol.ISO22900_PDU), VxdiagReadiness.PC_BRIDGE_IMPLEMENTED_REQUIRES_VENDOR_DRIVER, "Pont PC J2534 disponible ; pilote VXDIAG Windows et accès réseau du modèle exact requis."),
        VxdiagDeviceProfile(VxdiagModel.VCX_NANO, VxdiagLink.USB, "VCX NANO USB", setOf(VxdiagProtocol.J2534, VxdiagProtocol.ISO22900_PDU), VxdiagReadiness.PC_BRIDGE_IMPLEMENTED_REQUIRES_VENDOR_DRIVER, "Pont PC J2534 disponible ; pilote Windows VXDIAG ou fournisseur J2534 compatible requis pour cette révision Nano USB. SW-CAN non garanti."),
        VxdiagDeviceProfile(VxdiagModel.VCX_NANO, VxdiagLink.WIFI, "VCX NANO Wi-Fi", setOf(VxdiagProtocol.J2534, VxdiagProtocol.ISO22900_PDU), VxdiagReadiness.PC_BRIDGE_IMPLEMENTED_REQUIRES_VENDOR_DRIVER, "Pont PC J2534 disponible si le pilote Windows de cette variante Wi-Fi enregistre un fournisseur PassThru. SW-CAN dépend du support J2534-2.")
    )
}

/**
 * Adapter boundary for an official/vendor-supplied Android SDK or a documented bridge.
 * Implementations must preserve J2534 status/errors and must not translate arbitrary
 * diagnostic requests into undocumented CAN frames.
 */
interface VxdiagProvider {
    val providerName: String
    suspend fun open(profile: VxdiagDeviceProfile): Result<Unit>
    suspend fun readVersion(): Result<String>
    suspend fun passThruConnect(protocol: VxdiagProtocol, baudRate: Int = 500_000): Result<Unit>
    suspend fun passThruWrite(message: ByteArray): Result<Unit>
    suspend fun passThruRead(timeoutMs: Int = 1000): Result<ByteArray>
    suspend fun close()
}

/** No provider is bundled until VXDIAG supplies a redistributable Android SDK/API. */
class MissingVxdiagProvider : VxdiagProvider {
    override val providerName = "VXDIAG SDK not installed"
    override suspend fun open(profile: VxdiagDeviceProfile) = Result.failure<Unit>(UnsupportedOperationException("SDK Android VXDIAG absent : ${profile.label}"))
    override suspend fun readVersion() = Result.failure<String>(UnsupportedOperationException("VXDIAG SDK Android requis"))
    override suspend fun passThruConnect(protocol: VxdiagProtocol, baudRate: Int) = Result.failure<Unit>(UnsupportedOperationException("J2534 Android provider requis"))
    override suspend fun passThruWrite(message: ByteArray) = Result.failure<Unit>(UnsupportedOperationException("J2534 Android provider requis"))
    override suspend fun passThruRead(timeoutMs: Int) = Result.failure<ByteArray>(UnsupportedOperationException("J2534 Android provider requis"))
    override suspend fun close() = Unit
}
