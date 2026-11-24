package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class Lfx2012ControllerReferencesTest {
    @Test fun referencesAreListedWithoutImplyingFirmwareCompatibility() {
        assertEquals("E39", Lfx2012ControllerReferences.controllerFamily)
        assertEquals(
            listOf("12651994", "12643248", "12651993", "12637106", "12642665", "12650256", "12616889"),
            Lfx2012ControllerReferences.describedServiceParts()
        )
        val groups = Lfx2012ControllerReferences.servicePartGroups.associateBy { it.servicePart }
        assertEquals("12632286", groups.getValue("12651994").catalogBootFile)
        assertEquals(null, groups.getValue("12651993").catalogBootFile)
        assertFalse("12653998" in Lfx2012ControllerReferences.describedServiceParts())
    }

    @Test fun lookupHandlesMultipleLabelsOnTheSameServiceNumber() {
        val expected = Lfx2012ControllerReferences.Label("12643248", "12648906", "AA69")
        assertEquals(listOf(expected), Lfx2012ControllerReferences.findLabels(" aa69 "))
        assertEquals(listOf(expected), Lfx2012ControllerReferences.findLabels("12648906"))
        assertTrue(Lfx2012ControllerReferences.findLabels("12643248").containsAll(listOf(
            expected, Lfx2012ControllerReferences.Label("12643248", "12648432", "AA6L")
        )))
        assertTrue(Lfx2012ControllerReferences.findLabels("12653998").isEmpty())
        assertTrue(Lfx2012ControllerReferences.findLabels("1264").isEmpty())
    }

    @Test fun fingerprintRequiresEcuNameCalIdAndCvn() {
        val complete = Lfx2012ControllerReferences.ObservedIdentity(
            ecuName = "ECM E39",
            calibrationIds = listOf("12345678"),
            calibrationVerificationNumbers = listOf("FABC")
        )
        assertTrue(Lfx2012ControllerReferences.hasCompleteReadOnlyFingerprint(complete))
        assertFalse(Lfx2012ControllerReferences.hasCompleteReadOnlyFingerprint(
            complete.copy(calibrationVerificationNumbers = emptyList())
        ))
        assertFalse(Lfx2012ControllerReferences.hasCompleteReadOnlyFingerprint(
            complete.copy(ecuName = null)
        ))
    }

    @Test fun placeholdersCannotMasqueradeAsACompleteEcuFingerprint() {
        val identity = Lfx2012ControllerReferences.ObservedIdentity(
            ecuName = "ECM E39",
            calibrationIds = listOf("12612345"),
            calibrationVerificationNumbers = listOf("FABC")
        )
        listOf("unknown", "DEMO E39", "simulated ECU", "N/A", "00000000", "FFFFFFFF").forEach { fake ->
            assertFalse("ECU name: $fake", Lfx2012ControllerReferences.hasCompleteReadOnlyFingerprint(identity.copy(ecuName = fake)))
            assertFalse("CAL ID: $fake", Lfx2012ControllerReferences.hasCompleteReadOnlyFingerprint(identity.copy(calibrationIds = listOf(fake))))
            assertFalse("CVN: $fake", Lfx2012ControllerReferences.hasCompleteReadOnlyFingerprint(identity.copy(calibrationVerificationNumbers = listOf(fake))))
        }
    }

    @Test fun anyValidEntryIsEnoughWhenOtherEcusReturnPlaceholders() {
        val identity = Lfx2012ControllerReferences.ObservedIdentity(
            ecuName = "ECM E39",
            calibrationIds = listOf("N/A", "12612345"),
            calibrationVerificationNumbers = listOf("00000000", "12A4")
        )
        assertTrue(Lfx2012ControllerReferences.hasCompleteReadOnlyFingerprint(identity))
    }

    @Test fun multilinePlaceholderResponsesDoNotFormACompleteFingerprint() {
        val valid = Lfx2012ControllerReferences.ObservedIdentity(
            ecuName = "ECM E39",
            calibrationIds = listOf("12612345"),
            calibrationVerificationNumbers = listOf("12A4")
        )
        assertFalse(Lfx2012ControllerReferences.hasCompleteReadOnlyFingerprint(
            valid.copy(ecuName = "UNKNOWN\nSIMULATED")
        ))
        assertFalse(Lfx2012ControllerReferences.hasCompleteReadOnlyFingerprint(
            valid.copy(calibrationIds = listOf("N/A\n00000000"))
        ))
        assertFalse(Lfx2012ControllerReferences.hasCompleteReadOnlyFingerprint(
            valid.copy(calibrationVerificationNumbers = listOf("FFFFFFFF\nTBD"))
        ))
        assertTrue(Lfx2012ControllerReferences.hasCompleteReadOnlyFingerprint(
            valid.copy(calibrationIds = listOf("N/A\n12612345"))
        ))
    }

    @Test fun elmErrorsAndFormattedPlaceholdersNeverValidateAnEcuFingerprint() {
        val base = Lfx2012ControllerReferences.ObservedIdentity(
            ecuName = "ACDelco E39",
            calibrationIds = listOf("12612345"),
            calibrationVerificationNumbers = listOf("1234ABCD")
        )
        val invalid = listOf(
            "NO DATA", "NO RESPONSE", "ERROR", "UNAVAILABLE", "ECU: UNKNOWN",
            "ECU: DEMO E39", "SIMULATED E39", "0x00 00 00", "0xFF FF FF",
            "00:00:00", "FF-FF-FF"
        )
        invalid.forEach { value ->
            assertFalse("ECU name $value", Lfx2012ControllerReferences.hasCompleteReadOnlyFingerprint(
                base.copy(ecuName = value)
            ))
            assertFalse("Calibration ID $value", Lfx2012ControllerReferences.hasCompleteReadOnlyFingerprint(
                base.copy(calibrationIds = listOf(value))
            ))
            assertFalse("CVN $value", Lfx2012ControllerReferences.hasCompleteReadOnlyFingerprint(
                base.copy(calibrationVerificationNumbers = listOf(value))
            ))
        }
    }

    @Test fun e39FamilyMatchingAvoidsSubstringFalsePositives() {
        assertTrue(Lfx2012ControllerReferences.hasCandidateE39FamilyLabel("ACDelco E39 ECM"))
        assertTrue(Lfx2012ControllerReferences.hasCandidateE39FamilyLabel("E39A / ECM"))
        assertFalse(Lfx2012ControllerReferences.hasCandidateE39FamilyLabel("E390"))
        assertFalse(Lfx2012ControllerReferences.hasCandidateE39FamilyLabel("XXE39"))
        assertFalse(Lfx2012ControllerReferences.hasCandidateE39FamilyLabel("DEMO E39"))
        assertFalse(Lfx2012ControllerReferences.hasCandidateE39FamilyLabel(null))
    }

    @Test fun ecuResponderProvenanceRequiresReadableCanAddresses() {
        val classify = Lfx2012ControllerReferences::classifyResponderAttribution
        val none = Lfx2012ControllerReferences.ResponderAttribution.NO_VALID_CAN_ADDRESS
        val one = Lfx2012ControllerReferences.ResponderAttribution.SINGLE_ADDRESS_OBSERVED
        val multiple = Lfx2012ControllerReferences.ResponderAttribution.MULTIPLE_ADDRESSES_OBSERVED
        assertEquals(none, classify(emptyList()))
        assertEquals(none, classify(listOf("unknown", "7E8")))
        assertEquals(none, classify(listOf("7E8", "NO DATA")))
        assertEquals(none, classify(listOf("800")))
        assertEquals(none, classify(listOf("20000000")))
        assertEquals(none, classify(listOf("7E8", "FFFFFFFF")))
        assertEquals(one, classify(listOf("7e8", "0x7E8", "7E8")))
        assertEquals(one, classify(listOf("18daf110")))
        assertEquals(multiple, classify(listOf("7E8", "7E9")))
        assertEquals(multiple, classify(listOf("18DAF110", "18DAF118")))
    }

    @Test fun syntheticEcuResponsesNeverCountAsCompleteIdentity() {
        val fields = Lfx2012ControllerReferences.ObservedIdentity(
            ecuName = "ACDelco E39",
            calibrationIds = listOf("12612345"),
            calibrationVerificationNumbers = listOf("1234ABCD")
        )
        assertTrue(Lfx2012ControllerReferences.hasCompleteReadOnlyFingerprint(fields))
        assertFalse(
            Lfx2012ControllerReferences.hasCompleteReadOnlyFingerprint(
                fields.copy(simulated = true)
            )
        )
    }

    @Test fun aCompleteReadOnlyFingerprintNeverAuthorizesAWrite() {
        val vehicle = Lfx2012FlexFuelPreflight.Vehicle(
            2012, "LFX", 5, "2G1TEST00C0000001", "2G1TEST00C0000001"
        )
        val evidence = Lfx2012FlexFuelPreflight.Evidence(
            controllerId = "E39",
            firmwareId = "12345678",
            verifiedFirmware = false,
            originalBackupVerified = false,
            recoveryVerified = false
        )
        assertFalse(Lfx2012FlexFuelPreflight.canWriteCalibration(vehicle, evidence))
    }
}
