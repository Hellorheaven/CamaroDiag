package com.camarodiag.gen5.ui

import org.junit.Assert.*
import org.junit.Test

class PhysicalRecoveryFolderTest {
    @Test fun requiresOneUnambiguousPair() {
        assertEquals("a.journal", PhysicalRecoveryFolder.journalName(setOf("a.journal"), setOf("a.journal.blocks")))
        assertNull(PhysicalRecoveryFolder.journalName(setOf("a", "b"), setOf("a.blocks", "b.blocks")))
        assertNull(PhysicalRecoveryFolder.journalName(setOf("a"), setOf("other.blocks")))
        assertFalse(PhysicalRecoveryFolder.safeName("../a"))
        assertFalse(PhysicalRecoveryFolder.safeName("a/b"))
        assertFalse(PhysicalRecoveryFolder.safeName(".."))
        assertTrue(PhysicalRecoveryFolder.safeName("CALIBRATION-1000-8.ecu-block"))
    }
    @Test fun inventoryRejectsAliasesAndChangingDocumentIds() {
        fun entry(name: String, id: String, directory: Boolean = false) =
            PhysicalRecoveryFolder.Entry(name, id, directory)
        val journal = entry("a.journal", "one")
        val blocks = entry("a.journal.blocks", "two", true)
        val original = listOf(journal, blocks)
        assertTrue(PhysicalRecoveryFolder.sameInventory(original, original.reversed()))
        assertFalse(PhysicalRecoveryFolder.sameInventory(original, listOf(journal, blocks.copy(id = "three"))))
        assertFalse(PhysicalRecoveryFolder.sameInventory(original, listOf(journal)))
        assertFalse(PhysicalRecoveryFolder.sameInventory(original, listOf(journal, blocks.copy(directory = false))))
        assertFalse(PhysicalRecoveryFolder.uniqueEntries(listOf(journal, blocks.copy(id = "one"))))
        assertFalse(PhysicalRecoveryFolder.uniqueEntries(listOf(journal, journal.copy(id = "different"))))
        assertFalse(PhysicalRecoveryFolder.uniqueEntries(listOf(journal.copy(id = ""))))
    }

    @Test fun requiresIndependentCompleteIdentity() {
        val identity = PhysicalRecoveryIdentity("1G1FA1RXXR0123456", "E39", "ECM", "OS-A")
        assertTrue(identity.valid)
        assertFalse(identity.copy(vin = "I123").valid)
        assertFalse(identity.copy(firmware = "").valid)
        assertFalse(identity.copy(controller = "ECM\n").valid)
    }
}
