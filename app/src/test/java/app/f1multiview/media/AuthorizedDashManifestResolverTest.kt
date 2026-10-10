package app.f1multiview.media

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthorizedDashManifestResolverTest {
    private val manifestUri = Uri.parse("https://origin.example.test/race/manifest.mpd")
    private val dollar = '$'
    private val manifest = """
        <?xml version="1.0"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011"
             type="static" mediaPresentationDuration="PT8S" minBufferTime="PT1S">
          <BaseURL>https://cdn.example.test/race/</BaseURL>
          <Period id="p0" start="PT0S" duration="PT8S">
            <AdaptationSet id="1" contentType="video" mimeType="video/mp4" segmentAlignment="true">
              <SegmentTemplate timescale="1" duration="2"
                  initialization="init-${dollar}RepresentationID${dollar}.mp4"
                  media="segment-${dollar}Number${dollar}.m4s" startNumber="5"/>
              <Representation id="low" bandwidth="300000" width="640" height="360" codecs="avc1.4d401e"/>
              <Representation id="high" bandwidth="1200000" width="1920" height="1080" codecs="avc1.640028"/>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    @Test
    fun resolvesInitializationAndFirstMediaSegmentWithAuthAndRoleMetadata() {
        val plan = AuthorizedDashManifestResolver.resolve(
            manifestUri = manifestUri,
            manifestBytes = manifest.toByteArray(),
            requestHeaders = mapOf("Cookie" to "playToken=opaque", "Authorization" to "authorized"),
            maxWidth = 640,
            maxHeight = 360
        )

        assertEquals("https://cdn.example.test/race/init-low.mp4", plan.initialization.uri.toString())
        assertEquals("https://cdn.example.test/race/segment-5.m4s", plan.firstMediaSegment.dataSpec.uri.toString())
        assertEquals("playToken=opaque", plan.firstMediaSegment.dataSpec.httpRequestHeaders["Cookie"])
        assertEquals("authorized", plan.firstMediaSegment.dataSpec.httpRequestHeaders["Authorization"])
        assertEquals(MediaSegmentRoleDataSource.MEDIA_SEGMENT_ROLE, plan.firstMediaSegment.dataSpec.customData)
        assertEquals(0L, plan.initialization.position)
        assertEquals(0L, plan.firstMediaSegment.dataSpec.position)
        assertEquals(2_000_000L, plan.firstMediaSegment.durationUs)
        assertEquals(640, plan.format.width)
        assertEquals(360, plan.format.height)
        assertFalse(plan.dynamicManifest)
        assertFalse(plan.manifestDeclaredDrmInitData)
    }

    @Test
    fun choosesSmallestRepresentationWhenEveryRenditionExceedsTileBounds() {
        val plan = AuthorizedDashManifestResolver.resolve(
            manifestUri = manifestUri,
            manifestBytes = manifest.toByteArray(),
            requestHeaders = emptyMap(),
            maxWidth = 320,
            maxHeight = 180
        )

        assertEquals(640, plan.format.width)
        assertEquals(360, plan.format.height)
        assertTrue(plan.firstMediaSegment.durationUs > 0)
    }

    @Test
    fun protectedResolutionRejectsManifestWithoutWidevineInitData() {
        try {
            AuthorizedDashManifestResolver.resolve(
                manifestUri = manifestUri,
                manifestBytes = manifest.toByteArray(),
                requestHeaders = emptyMap(),
                requireWidevineInitData = true
            )
            throw AssertionError("Protected DASH must not select a rendition without Widevine init data")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Widevine DRM initialization data"))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyManifestBytes() {
        AuthorizedDashManifestResolver.resolve(manifestUri, byteArrayOf(), emptyMap())
    }
}
