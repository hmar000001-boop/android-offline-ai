package dev.screenveil.app.policy

import dev.screenveil.app.model.DetectionResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionResponseParserTest {
    @Test fun parsesSwimwearAsSwimwearEvenWhenModelSaysAllow() {
        val response = """{"regions":[{"box":[100,100,800,900],"media_type":"image","decision":"allow","is_swimwear":true}]}"""
        val parsed = DetectionResponseParser.parse(response)
        assertTrue(parsed.parseSucceeded)
        assertEquals(1, parsed.regions.size)
        assertEquals(dev.screenveil.app.policy.ContentDecision.SWIMWEAR, parsed.regions.single().decision)
    }

    @Test fun rejectsCoordinatesOutsideNormalizedRange() {
        val response = """{"regions":[{"box":[-1,0,1200,1000],"decision":"explicit"}]}"""
        val parsed = DetectionResponseParser.parse(response)
        assertFalse(parsed.parseSucceeded)
        assertTrue(parsed.regions.isEmpty())
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

    @Test fun rejectsNonJsonOutput() {
        val parsed = DetectionResponseParser.parse("I cannot inspect this image.")
        assertFalse(parsed.parseSucceeded)
    }
}
