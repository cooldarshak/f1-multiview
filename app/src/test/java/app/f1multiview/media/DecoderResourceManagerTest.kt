package app.f1multiview.media

import app.f1multiview.model.StreamKind
import app.f1multiview.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DecoderResourceManagerTest {
    private fun feed(id: String, kind: StreamKind = StreamKind.ONBOARD) =
        StreamSource(id = id, title = id, kind = kind, url = "https://example.test/$id.m3u8")

    @Test
    fun allocatesFourSlotsAndRejectsTheFifthWithoutEviction() {
        val manager = DecoderResourceManager(4)

        repeat(4) { index ->
            assertNotNull(manager.request(feed("feed-$index")).lease)
        }

        assertNull(manager.request(feed("feed-4")).lease)
        assertEquals(4, manager.activeLeases().size)
        assertEquals(0, manager.availableSlots())
    }

    @Test
    fun releasedSlotIsReusableByAnotherLogicalFeed() {
        val manager = DecoderResourceManager(2)

        val first = manager.request(feed("a")).lease!!
        manager.request(feed("b"))
        manager.release("a")

        val replacement = manager.request(feed("c")).lease!!
        assertEquals(first.slotId, replacement.slotId)
        assertEquals("c", replacement.feedId)
        assertEquals(0, manager.availableSlots())
    }

    @Test
    fun referenceFeedGetsHighestPriorityAndReferenceStateMoves() {
        val manager = DecoderResourceManager(2)

        manager.request(feed("main"), isReference = true)
        manager.request(feed("secondary"))

        assertEquals(100, manager.lease("main")?.priority)
        assertEquals(true, manager.lease("main")?.isReference)

        manager.updateReference("secondary")

        assertEquals(false, manager.lease("main")?.isReference)
        assertEquals(100, manager.lease("secondary")?.priority)
    }

}
