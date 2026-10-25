package com.chief.anansi.plugin.error

import com.chief.anansi.data.entity.SourceErrorEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Registry round-trip tests (spec: Verification): every [SourceError]
 * variant survives the stored-kind representation (including Blocked and
 * SourceMissing; the mapping `when` is compiler-checked over the enum), an
 * unknown stored kind falls back to Parse, and the staleness helper keeps
 * fresh rows while pruning rows older than the window.
 */
class SourceErrorRoundTripTest {

    @Test
    fun everyVariantCarriesItsStoredKindName() {
        // The names equal the variant simple names the registry stored
        // before the enum existed, so existing rows keep loading.
        assertEquals("Network", SourceError.Network("r").kind.name)
        assertEquals("Parse", SourceError.Parse("r").kind.name)
        assertEquals("Incompatible", SourceError.Incompatible("r").kind.name)
        assertEquals("RequiresJs", SourceError.RequiresJs("r").kind.name)
        assertEquals("UnsupportedFeature", SourceError.UnsupportedFeature("r").kind.name)
        assertEquals("SourceMissing", SourceError.SourceMissing("r").kind.name)
        assertEquals("Blocked", SourceError.Blocked("r").kind.name)
    }

    @Test
    fun storedKindRoundTripsEveryVariant() {
        val variants = listOf(
            SourceError.Network("network reason"),
            SourceError.Parse("parse reason"),
            SourceError.Incompatible("incompatible reason"),
            SourceError.RequiresJs("requires-js reason"),
            SourceError.UnsupportedFeature("unsupported reason"),
            SourceError.SourceMissing("source-missing reason"),
            SourceError.Blocked("blocked reason"),
        )
        variants.forEach { error ->
            val row = SourceErrorEntity(
                sourceId = "src",
                kind = error.kind.name,
                reason = error.reason,
                updatedAt = 0L,
            )
            val restored = row.toError()
            assertEquals(error::class, restored::class)
            assertEquals(error.kind, restored.kind)
            assertEquals(error.reason, restored.reason)
        }
    }

    @Test
    fun unknownStoredKindFallsBackToParse() {
        val restored = SourceErrorEntity("src", "SomeFutureKind", "kept reason", 0L).toError()
        assertTrue(restored is SourceError.Parse)
        assertEquals("kept reason", restored.reason)
    }

    @Test
    fun stalenessKeepsFreshRowsAndPrunesRowsPastTheWindow() {
        val now = 1_700_000_000_000L
        val day = 24L * 60 * 60 * 1000
        val window = 30L * day
        assertFalse(isStaleErrorRow(now, now))
        assertFalse(isStaleErrorRow(now - 29 * day, now))
        // The window edge itself is still fresh; one millisecond more is not.
        assertFalse(isStaleErrorRow(now - window, now))
        assertTrue(isStaleErrorRow(now - window - 1, now))
        assertTrue(isStaleErrorRow(now - 40 * day, now))
    }
}
