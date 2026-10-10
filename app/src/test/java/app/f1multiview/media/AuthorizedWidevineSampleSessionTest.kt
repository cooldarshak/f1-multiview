package app.f1multiview.media

import android.os.Looper
import app.f1multiview.model.StreamKind
import app.f1multiview.model.StreamSource
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthorizedWidevineSampleSessionTest {
    private fun stream(protected: Boolean, licenseUrl: String? = null) = StreamSource(
        id = "feed",
        title = "Authorized feed",
        kind = StreamKind.WORLD,
        url = "https://stream.example.test/manifest.mpd",
        drmLicenseUrl = licenseUrl,
        drmProtected = protected
    )

    @Test
    fun missingLicenseEndpointFailsClosedWithoutOpeningDrmResources() {
        assertNull(AuthorizedWidevineSampleSession.open(stream(protected = true), Looper.getMainLooper()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun clearFeedCannotOpenProtectedSampleSession() {
        AuthorizedWidevineSampleSession.open(stream(protected = false), Looper.getMainLooper())
    }
}
