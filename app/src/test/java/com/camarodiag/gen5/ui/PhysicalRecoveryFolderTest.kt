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

    @Test fun treeInventoryRejectsCrossLevelAliases() {
        val root = listOf(PhysicalRecoveryFolder.Entry("journal", "j", false),
            PhysicalRecoveryFolder.Entry("journal.blocks", "b", true),
            PhysicalRecoveryFolder.Entry("other", "o", false))
        val block = PhysicalRecoveryFolder.Entry("region.ecu-block", "r", false)
        assertTrue(PhysicalRecoveryFolder.uniqueTree("root", root, listOf(block)))
        for (alias in listOf("j", "b", "o", "root"))
            assertFalse(PhysicalRecoveryFolder.uniqueTree("root", root, listOf(block.copy(id = alias))))
        assertFalse(PhysicalRecoveryFolder.uniqueTree("j", root, listOf(block)))
    }

    @Test fun stagingChecksFullDigestEvenWhenSizeIsUnchanged() {
        val file = java.io.File.createTempFile("recovery", ".block")
        try {
            val data = byteArrayOf(1, 2, 3, 4)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(data)
            file.writeBytes(data)
            PhysicalRecoveryFolder.verifyStagedFile(file, 4, digest) {}
            file.writeBytes(byteArrayOf(1, 2, 3, 5))
            assertThrows(IllegalStateException::class.java) {
                PhysicalRecoveryFolder.verifyStagedFile(file, 4, digest) {}
            }
            file.writeBytes(byteArrayOf(1, 2, 3))
            assertThrows(IllegalStateException::class.java) {
                PhysicalRecoveryFolder.verifyStagedFile(file, 4, digest) {}
            }
        } finally {
            file.delete()
        }
    }

    @Test fun stagingCannotVerifyThroughSymbolicLink() {
        val folder = java.nio.file.Files.createTempDirectory("recovery-test")
        val outside = java.nio.file.Files.createTempFile("original", ".block")
        val link = folder.resolve("alias.ecu-block")
        try {
            val data = byteArrayOf(1,2,3)
            java.nio.file.Files.write(outside,data)
            try {
                java.nio.file.Files.createSymbolicLink(link,outside)
            } catch (unsupported: UnsupportedOperationException) {
                org.junit.Assume.assumeNoException(unsupported)
            } catch (unsupported: java.nio.file.FileSystemException) {
                org.junit.Assume.assumeNoException(unsupported)
            }
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(data)
            assertThrows(IllegalStateException::class.java) {
                PhysicalRecoveryFolder.verifyStagedFile(link.toFile(),3,hash) {}
            }
            assertArrayEquals(data,java.nio.file.Files.readAllBytes(outside))
        } finally {
            java.nio.file.Files.deleteIfExists(link)
            java.nio.file.Files.deleteIfExists(folder)
            java.nio.file.Files.deleteIfExists(outside)
        }
    }

    @Test fun requiresIndependentCompleteIdentity() {
        val identity = PhysicalRecoveryIdentity("1G1FA1RXXR0123456", "E39", "ECM", "OS-A")
        assertTrue(identity.valid)
        assertFalse(identity.copy(vin = "I123").valid)
        assertFalse(identity.copy(firmware = "").valid)
        assertFalse(identity.copy(controller = "ECM\n").valid)
    }
}
