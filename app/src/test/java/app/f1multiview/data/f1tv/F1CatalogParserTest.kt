package app.f1multiview.data.f1tv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class F1CatalogParserTest {
    private fun node(title:String): JSONObject = JSONObject()
        .put("title", title)
        .put("metadata", JSONObject().put("title", title).put("emfAttributes", JSONObject()))

    @Test fun recognizesEditorialVod() {
        assertEquals("race-in-30", F1CatalogParser.sessionInfo(node("Race in 30"))?.stage)
        assertEquals("highlights", F1CatalogParser.sessionInfo(node("Bahrain GP Highlights"))?.stage)
        assertEquals("press-conference", F1CatalogParser.sessionInfo(node("Post-Race Press Conference"))?.stage)
        assertEquals("pre-show", F1CatalogParser.sessionInfo(node("Pre Race Show"))?.stage)
        assertEquals("post-show", F1CatalogParser.sessionInfo(node("Post Race Show"))?.stage)
    }

    @Test fun normalizesSeries() {
        assertEquals("F2", F1CatalogParser.normalizeSeries("FORMULA 2"))
        assertEquals("F1 Academy", F1CatalogParser.normalizeSeries("", "F1 Academy Highlights"))
        assertEquals("F1", F1CatalogParser.normalizeSeries(""))
    }

    @Test fun returnsNullForUnknownEditorialTitle() {
        assertNotNull(F1CatalogParser.sessionInfo(node("Race in 30")))
        assertEquals(null, F1CatalogParser.sessionInfo(node("Completely unrelated video")))
    }
}
