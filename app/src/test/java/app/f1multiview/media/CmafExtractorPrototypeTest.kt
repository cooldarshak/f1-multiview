package app.f1multiview.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CmafExtractorPrototypeTest {
    @Test
    fun admitsMp4ContainerTypesWithoutAssumingDrmSupport() {
        assertTrue(CmafExtractorPrototype.supportsContainerMimeType("video/mp4"))
        assertTrue(CmafExtractorPrototype.supportsContainerMimeType("APPLICATION/MP4"))
        assertFalse(CmafExtractorPrototype.supportsContainerMimeType("application/vnd.apple.mpegurl"))
        assertFalse(CmafExtractorPrototype.supportsContainerMimeType(null))
    }
}
