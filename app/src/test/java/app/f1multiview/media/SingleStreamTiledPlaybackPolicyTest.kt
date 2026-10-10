package app.f1multiview.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleStreamTiledPlaybackPolicyTest {
    private fun descriptor(count: Int) = AppOwnedTiledStreamDescriptor(
        streamUri = "https://media.example.test/tiled.mpd",
        codec = AppOwnedTiledStreamDescriptor.Codec.HEVC_TILES,
        drmScheme = AppOwnedTiledStreamDescriptor.DrmScheme.WIDEVINE_CENC,
        licenseUri = "https://license.example.test/widevine",
        origin = AppOwnedTiledStreamDescriptor.Origin.PROVIDER_AUTHORIZED_TILED_STREAM,
        presentationTimelineId = "race-1",
        timescale = 90_000,
        tiles = (0 until count).map { index ->
            AppOwnedTiledStreamDescriptor.Tile(
                sourceId = "feed-$index",
                x = index.toDouble() / count,
                y = 0.0,
                width = 1.0 / count,
                height = 1.0
            )
        },
        keyRotationSignalled = true
    )

    @Test fun blocksMultiFeedWhenOnlyIndependentFeedUrlsAreAvailable() {
        assertFalse(SingleStreamTiledPlaybackPolicy.evaluate(3, null).allowed)
    }

    @Test fun allowsOnlyMatchingValidatedTwoThreeAndFourTileContracts() {
        for (count in 2..4) {
            assertTrue(SingleStreamTiledPlaybackPolicy.evaluate(count, descriptor(count)).allowed)
        }
        assertFalse(SingleStreamTiledPlaybackPolicy.evaluate(4, descriptor(3)).allowed)
    }

    @Test fun rejectsMoreThanFourVisibleVideoFeeds() {
        assertFalse(SingleStreamTiledPlaybackPolicy.evaluate(5, descriptor(4)).allowed)
    }

    @Test fun singleFeedDoesNotRequireTiledDescriptor() {
        assertTrue(SingleStreamTiledPlaybackPolicy.evaluate(1, null).allowed)
    }
}
