package com.camarodiag.gen5.data.obd

import org.junit.Assert.*
import org.junit.Test

class CamaroDiagConnectorWiringGuideTest {
    @Test fun exactDocumentaryReferencesAreKeptSeparate() {
        val guide = CamaroDiagConnectorWiringGuide
        assertEquals(CamaroDiagConnectorWiringGuide.Reference.LFX_2012_E39,
            guide.findReference(2012, "LFX", "e39"))
        assertEquals(CamaroDiagConnectorWiringGuide.Reference.E39A_UNQUALIFIED,
            guide.findReference(2012, "LFX", "E39A"))
        for (rpo in listOf("LS3", "L99")) {
            assertEquals(CamaroDiagConnectorWiringGuide.Reference.LS3_L99_2012_E38,
                guide.findReference(2012, rpo, "E38"))
        }
    }

    @Test fun noPinoutIsExtrapolatedToUnknownOrSimilarVariants() {
        val guide = CamaroDiagConnectorWiringGuide
        assertNull(guide.findReference(2013, "LFX", "E39"))
        assertNull(guide.findReference(2012, "LSA", "E38"))
        assertNull(guide.findReference(2012, "LFX", "E38"))
        assertNull(guide.findReference(2012, "LFX", "E39 / E39A"))
        assertNull(guide.findReference(2012, "LFX", "12643248"))
        assertNull(guide.findReference(2012, "LFX", ""))
    }

    @Test fun documentaryLinksAreNotElectricalOrFlashApprovals() {
        for (reference in CamaroDiagConnectorWiringGuide.Reference.values()) {
            assertFalse(reference.qualifiedForConnection)
            assertTrue(reference.url.startsWith("https://github.com/Hellorheaven/CamaroDiagConnectors/blob/"))
        }
        assertEquals(3, CamaroDiagConnectorWiringGuide.Reference.values().map { it.url }.distinct().size)
    }
}
