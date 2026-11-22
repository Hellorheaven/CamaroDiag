package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StnUdsAdapterRestorationTest {
    @Test fun restoresFunctionalObdAfterAPhysicalStnSession() = runBlocking {
        val commands = mutableListOf<String>()
        val result = StnUdsAdapterRestoration.restore { command ->
            commands += command
            "OK\r\n>"
        }
        assertTrue(result.succeeded)
        assertEquals(StnUdsAdapterRestoration.commands, commands)
        assertTrue(commands.indexOf("STCFCPC") < commands.indexOf("ATSP6"))
        assertTrue(commands.indexOf("ATCRA") < commands.indexOf("ATSH 7DF"))
        assertTrue(commands.contains("ATCAF1"))
    }

    @Test fun failedFilterResetIsNotHiddenByLaterSuccessfulCommands() = runBlocking {
        val commands = mutableListOf<String>()
        val result = StnUdsAdapterRestoration.restore { command ->
            commands += command
            if (command == "ATCRA") "NOT OK" else "OK"
        }
        assertFalse(result.succeeded)
        assertEquals(listOf("ATCRA"), result.failedCommands)
        assertEquals(StnUdsAdapterRestoration.commands, commands)
    }

    @Test fun transportExceptionsStillAttemptAllCleanupCommands() = runBlocking {
        val commands = mutableListOf<String>()
        val result = StnUdsAdapterRestoration.restore { command ->
            commands += command
            if (command == "STCSEGR 0") error("Link lost")
            if (command == "STCSEGT 0") "?" else "OK"
        }
        assertEquals(listOf("STCSEGR 0", "STCSEGT 0"), result.failedCommands)
        assertEquals(StnUdsAdapterRestoration.commands, commands)
    }

    @Test fun rejectsEmptyOrAmbiguousAcknowledgements() = runBlocking {
        for (reply in listOf("", "OKAY", "NOT OK", "NO DATA", "?")) {
            val result = StnUdsAdapterRestoration.restore { reply }
            assertFalse(reply, result.succeeded)
            assertEquals(StnUdsAdapterRestoration.commands, result.failedCommands)
        }
    }
}
