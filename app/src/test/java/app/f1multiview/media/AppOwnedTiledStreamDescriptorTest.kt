package app.f1multiview.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppOwnedTiledStreamDescriptorTest {
    private fun tile(id: String, x: Double, y: Double, width: Double, height: Double) =
        AppOwnedTiledStreamDescriptor.Tile(id, x, y, width, height)

    private fun descriptor(tiles: List<AppOwnedTiledStreamDescriptor.Tile>) =
        AppOwnedTiledStreamDescriptor(
            streamUri = "https://media.example.test/race/tiled.mpd",
            codec = AppOwnedTiledStreamDescriptor.Codec.HEVC_TILES,
            drmScheme = AppOwnedTiledStreamDescriptor.DrmScheme.WIDEVINE_CENC,
            licenseUri = "https://license.example.test/widevine",
            origin = AppOwnedTiledStreamDescriptor.Origin.PROVIDER_AUTHORIZED_TILED_STREAM,
            presentationTimelineId = "race-session-123",
            timescale = 90_000,
            tiles = tiles,
            keyRotationSignalled = true
        )

    @Test fun acceptsTwoTileSingleStreamLayout() {
        assertTrue(descriptor(listOf(tile("main", 0.0, 0.0, 0.5, 1.0),
            tile("onboard", 0.5, 0.0, 0.5, 1.0))).validate().isValid)
    }

    @Test fun acceptsThreeTileSingleStreamLayout() {
        assertTrue(descriptor(listOf(tile("main", 0.0, 0.0, 0.5, 1.0),
            tile("onboard-a", 0.5, 0.0, 0.5, 0.5),
            tile("onboard-b", 0.5, 0.5, 0.5, 0.5))).validate().isValid)
    }

    @Test fun acceptsFourTileSingleStreamLayout() {
        assertTrue(descriptor(listOf(tile("main", 0.0, 0.0, 0.5, 0.5),
            tile("a", 0.5, 0.0, 0.5, 0.5),
            tile("b", 0.0, 0.5, 0.5, 0.5),
            tile("c", 0.5, 0.5, 0.5, 0.5))).validate().isValid)
    }

    @Test fun rejectsMissingDrmEndpointAndNonHttpsStream() {
        val invalid = descriptor(listOf(tile("main", 0.0, 0.0, 0.5, 1.0),
            tile("onboard", 0.5, 0.0, 0.5, 1.0))).copy(
                streamUri = "http://media.example.test/tiled.mpd",
                licenseUri = ""
            )
        val validation = invalid.validate()
        assertFalse(validation.isValid)
        assertTrue(validation.errors.any { it.contains("streamUri") })
        assertTrue(validation.errors.any { it.contains("licenseUri") })
    }

    @Test fun rejectsOneTileDuplicateIdsOverlapAndOutOfBounds() {
        val invalid = descriptor(listOf(tile("same", 0.0, 0.0, 0.7, 1.0),
            tile("same", 0.6, 0.0, 0.5, 1.0))).copy(timescale = 0)
        val validation = invalid.validate()
        assertFalse(validation.isValid)
        assertTrue(validation.errors.any { it.contains("tile count") })
        assertTrue(validation.errors.any { it.contains("unique") })
        assertTrue(validation.errors.any { it.contains("overlaps") })
        assertTrue(validation.errors.any { it.contains("bounds") })
        assertTrue(validation.errors.any { it.contains("timescale") })
    }
}
