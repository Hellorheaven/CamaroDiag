package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Lfx2012FuelReadOnlyTest {
    private val pids = Lfx2012FuelReadOnly.PIDS
    private fun responses() = pids.associateWith { "41%02X80".format(it) as String? }

    @Test fun reportedEthanolAndBothBankTrimsAreReadOnlyEcuValues() {
        val survey = Lfx2012FuelReadOnly.Survey(
            Lfx2012FuelReadOnly.Source.J2534, pids.toSet(), responses()
        )
        val raw = mapOf(
            0x52 to intArrayOf(255),
            0x06 to intArrayOf(128),
            0x07 to intArrayOf(64),
            0x08 to intArrayOf(0),
            0x09 to intArrayOf(255)
        )
        val snapshot = Lfx2012FuelReadOnly.inspect(survey, raw, 1795320000000L)
        assertEquals(100.0, snapshot.ethanolPercent!!, 0.00001)
        assertEquals(0.0, snapshot.reading(0x06).percent!!, 0.00001)
        assertEquals(-50.0, snapshot.reading(0x07).percent!!, 0.00001)
        assertEquals(-100.0, snapshot.reading(0x08).percent!!, 0.00001)
        assertEquals(99.21875, snapshot.reading(0x09).percent!!, 0.00001)
        assertFalse(snapshot.flexFuelCalibratedOrSensorVerified)
    }

    @Test fun unsupportedAndMissingValuesNeverBecomeAnInventedZeroPercent() {
        val supported = setOf(0x06, 0x52)
        val responses = pids.associateWith { if (it in supported) "ok" else null }
        val survey = Lfx2012FuelReadOnly.Survey(
            Lfx2012FuelReadOnly.Source.ELM, supported, responses
        )
        val raw = pids.associateWith { pid -> when (pid) {
            0x06 -> intArrayOf(128)
            0x52 -> null
            else -> null
        } }
        val snapshot = Lfx2012FuelReadOnly.inspect(survey, raw, 1L)
        assertNull(snapshot.ethanolPercent)
        assertEquals(Lfx2012FuelReadOnly.Availability.NO_VALID_RESPONSE,
            snapshot.reading(0x52).availability)
        assertEquals(Lfx2012FuelReadOnly.Availability.UNSUPPORTED,
            snapshot.reading(0x07).availability)
        assertEquals(0.0, snapshot.reading(0x06).percent!!, 0.000001)
        assertEquals(0L, snapshot.reading(0x06).raw!!.toLong() - 128L)
    }

    @Test fun explicitZeroEthanolIsDifferentFromUnsupportedEthanol() {
        val p = setOf(0x52)
        val survey = Lfx2012FuelReadOnly.Survey(
            Lfx2012FuelReadOnly.Source.J2534,
            p, pids.associateWith { if (it == 0x52) "415200" else null }
        )
        val zero = Lfx2012FuelReadOnly.inspect(
            survey, pids.associateWith { if (it == 0x52) intArrayOf(0) else null }, 1L
        )
        assertEquals(0.0, zero.ethanolPercent!!, 0.0)
        assertEquals(Lfx2012FuelReadOnly.Availability.REPORTED,
            zero.reading(0x52).availability)
        assertFalse(zero.flexFuelCalibratedOrSensorVerified)
    }

    @Test fun incompleteAndForgedSurveysAreRejectedBeforeDisplay() {
        assertTrue(runCatching { Lfx2012FuelReadOnly.Survey(
            Lfx2012FuelReadOnly.Source.J2534, emptySet(), mapOf(0x52 to "415200")
        ) }.isFailure)
        assertTrue(runCatching { Lfx2012FuelReadOnly.Survey(
            Lfx2012FuelReadOnly.Source.ELM,
            emptySet(), pids.associateWith { if (it == 0x52) "415200" else null }
        ) }.isFailure)
        val survey = Lfx2012FuelReadOnly.Survey(
            Lfx2012FuelReadOnly.Source.ELM, pids.toSet(), responses()
        )
        assertTrue(runCatching { Lfx2012FuelReadOnly.inspect(
            survey, mapOf(0x52 to intArrayOf(0)), 1L
        ) }.isFailure)
        assertTrue(runCatching { Lfx2012FuelReadOnly.inspect(
            survey, pids.associateWith { intArrayOf(0) }, 0L
        ) }.isFailure)
    }
}
