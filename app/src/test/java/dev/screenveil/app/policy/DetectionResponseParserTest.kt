package dev.screenveil.app.policy

import dev.screenveil.app.model.DetectionResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionResponseParserTest {
    @Test fun parsesAllSwimwearAsRedactableCategory() {
        val response = """{"regions":[{"box":[100,100,800,900],"media_type":"image","decision":"swimwear"}]}"""
        val parsed = DetectionResponseParser.parse(response)
        assertTrue(parsed.parseSucceeded)
        assertEquals(1, parsed.regions.size)
        assertEquals(ContentDecision.SWIMWEAR, parsed.regions.single().decision)
    }

    @Test fun parsesFullScreenBeachPhotoRegion() {
        val response = """{"regions":[{"box":[0,0,1000,1000],"media_type":"image","decision":"swimwear"}]}"""
        val parsed = DetectionResponseParser.parse(response)
        assertTrue(parsed.parseSucceeded)
        assertEquals(0, parsed.regions.single().box.left)
        assertEquals(1000, parsed.regions.single().box.bottom)
    }

    @Test fun rejectsCoordinatesOutsideNormalizedRange() {
        val response = """{"regions":[{"box":[-1,0,1200,1000],"media_type":"image","decision":"explicit"}]}"""
        val parsed = DetectionResponseParser.parse(response)
        assertFalse(parsed.parseSucceeded)
        assertTrue(parsed.regions.isEmpty())
    }

    @Test fun rejectsNonIntegerCoordinates() {
        val response = """{"regions":[{"box":[1.5,0,900,900],"media_type":"image","decision":"explicit"}]}"""
        assertFalse(DetectionResponseParser.parse(response).parseSucceeded)
    }

    @Test fun rejectsMalformedRegionRatherThanTreatingItAsSafe() {
        val response = """{"regions":[{"decision":"allow"}]}"""
        assertFalse(DetectionResponseParser.parse(response).parseSucceeded)
    }

    @Test fun emptyRegionListIsAValidScreenWithNoDetectedMedia() {
        val parsed = DetectionResponseParser.parse("""{"regions":[]}""")
        assertTrue(parsed.parseSucceeded)
        assertTrue(parsed.regions.isEmpty())
    }

    @Test fun rejectsNonJsonOutputAndJsonWrappedInCommentary() {
        assertFalse(DetectionResponseParser.parse("I cannot inspect this image.").parseSucceeded)
        assertFalse(DetectionResponseParser.parse("Here is JSON: {\"regions\":[]}").parseSucceeded)
    }

    @Test fun rejectsUnexpectedFields() {
        val response = """{"regions":[],"comment":"safe"}"""
        assertFalse(DetectionResponseParser.parse(response).parseSucceeded)
    }
    @Test fun acceptsMoreThanEightVisibleMediaRegions() {
        val regions = (0 until 12).joinToString(",") { index ->
            val left = index * 25
            "{\"box\":[$left,100,${left + 20},200],\"media_type\":\"image\",\"decision\":\"swimwear\"}"
        }
        val parsed = DetectionResponseParser.parse("{\"regions\":[$regions]}")
        assertTrue(parsed.parseSucceeded)
        assertEquals(12, parsed.regions.size)
    }

    @Test fun rejectsExcessiveMediaRegionsInsteadOfSilentlyTruncating() {
        val regions = (0 until 25).joinToString(",") { index ->
            val left = index * 10
            "{\"box\":[$left,100,${left + 20},200],\"media_type\":\"image\",\"decision\":\"swimwear\"}"
        }
        val parsed = DetectionResponseParser.parse("{\"regions\":[$regions]}")
        assertFalse(parsed.parseSucceeded)
        assertTrue(parsed.regions.isEmpty())
    }

}
