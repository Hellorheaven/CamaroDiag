package com.camarodiag.gen5.data.obd

/**
 * ECU-independent last-mile binding: a physical erase sector and EVERY
 * outgoing programming unit must correspond to the same intended delta.
 *
 * Compare ABSOLUTE ECU addresses, declared region, dimensions, image bytes
 * and the exact unit payload. This is not a vendor authorization, seed/key
 * or a substitute for checking the real ECU memory and flash geometry.
 */
internal object EcuWriteBlockVerifier {
    fun requireExactSector(
        layout: EcuPartialWritePlanner.Layout,
        region: EcuBackupCoverage.Region,
        originalImage: ByteArray,
        targetImage: ByteArray,
        sector: EcuPartialWritePlanner.Sector,
        eraseRequest: EcuProgrammingDriverContract.RegionRequest
    ) {
        require(eraseRequest.region == region &&
            eraseRequest.address == sector.absoluteAddress &&
            eraseRequest.length == layout.eraseBlockBytes.toLong()) {
            "Erase request does not match the intended ECU region, address or sector size."
        }
        require(layout.eraseBlockBytes > 0 && layout.programBlockBytes > 0 &&
            originalImage.size == targetImage.size &&
            originalImage.size.toLong() == layout.regionSize &&
            sector.originalBytes.size == layout.eraseBlockBytes &&
            sector.patchedBytes.size == layout.eraseBlockBytes) {
            "Write plan does not match the complete ECU sector geometry."
        }
        val start = sector.absoluteAddress - layout.regionStartAddress
        require(start >= 0 && start % layout.eraseBlockBytes == 0L &&
            start <= originalImage.size.toLong() - layout.eraseBlockBytes) {
            "Planned sector is not aligned to the intended ECU image region."
        }
        val first = start.toInt()
        val last = first + layout.eraseBlockBytes
        require(originalImage.copyOfRange(first, last).contentEquals(sector.originalBytes) &&
            targetImage.copyOfRange(first, last).contentEquals(sector.patchedBytes)) {
            "Planned ECU sector bytes do not match the intended original and target images."
        }
        val count = sector.originalBytes.indices.count {
            sector.originalBytes[it] != sector.patchedBytes[it]
        }
        require(count > 0 && count == sector.changedBytes &&
            EcuBenchExpertPlan.sha256Hex(sector.originalBytes) == sector.originalSha256 &&
            EcuBenchExpertPlan.sha256Hex(sector.patchedBytes) == sector.updatedSha256) {
            "ECU sector payload or planned delta digest changed before writing."
        }
    }

    fun requireExactUnit(
        layout: EcuPartialWritePlanner.Layout,
        region: EcuBackupCoverage.Region,
        targetImage: ByteArray,
        sector: EcuPartialWritePlanner.Sector,
        unit: EcuProgrammingDriverContract.RegionRequest,
        sectorOffset: Int,
        payload: ByteArray
    ) {
        require(layout.programBlockBytes > 0 && sectorOffset >= 0 &&
            sectorOffset % layout.programBlockBytes == 0 &&
            sectorOffset <= sector.patchedBytes.size - layout.programBlockBytes &&
            unit.region == region &&
            unit.address == sector.absoluteAddress + sectorOffset.toLong() &&
            unit.length == layout.programBlockBytes.toLong() &&
            payload.size == layout.programBlockBytes) {
            "Programming unit is not aligned to the intended ECU address and block size."
        }
        val fullImageOffset = unit.address - layout.regionStartAddress
        require(fullImageOffset >= 0 &&
            fullImageOffset <= targetImage.size.toLong() - payload.size &&
            payload.contentEquals(sector.patchedBytes.copyOfRange(
                sectorOffset, sectorOffset + payload.size)) &&
            payload.contentEquals(targetImage.copyOfRange(
                fullImageOffset.toInt(), fullImageOffset.toInt() + payload.size))) {
            "Outgoing ECU bytes differ from the exact requested target block."
        }
    }
}
