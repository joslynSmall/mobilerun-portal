package com.mobilerun.portal.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotOptionsTest {
    private fun opts(json: String) = ScreenshotOptions.from(JSONObject(json))

    @Test
    fun defaultsArePassthroughPng() {
        val o = opts("{}")
        assertEquals(ScreenshotOptions.FORMAT_PNG, o.format)
        assertTrue(o.isPassthrough)
    }

    @Test
    fun hideOverlayAloneStaysPassthrough() {
        assertTrue(opts("""{"hideOverlay": true}""").isPassthrough)
    }

    @Test
    fun formatAliasesAndUnknownValues() {
        assertEquals(ScreenshotOptions.FORMAT_JPEG, opts("""{"format":"JPG"}""").format)
        assertEquals(ScreenshotOptions.FORMAT_JPEG, opts("""{"format":"jpeg"}""").format)
        assertEquals(ScreenshotOptions.FORMAT_WEBP, opts("""{"format":"webp"}""").format)
        assertEquals(ScreenshotOptions.FORMAT_PNG, opts("""{"format":"gif"}""").format)
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        val o = opts("""{"format":"jpeg","quality":500,"scale":3.5,"maxSide":-10}""")
        assertEquals(100, o.quality)
        assertEquals(1.0, o.scale, 0.0)
        assertEquals(0, o.maxSide)
        assertEquals(1, opts("""{"quality":0}""").quality)
        assertEquals(1.0, opts("""{"scale":0}""").scale, 0.0)
    }

    @Test
    fun scaledPngIsNotPassthrough() {
        assertFalse(opts("""{"scale":0.5}""").isPassthrough)
        assertFalse(opts("""{"maxSide":1600}""").isPassthrough)
        assertFalse(opts("""{"format":"jpeg"}""").isPassthrough)
    }

    @Test
    fun maxSideCapsLongerEdge() {
        val o = opts("""{"format":"jpeg","maxSide":1600}""")
        assertEquals(1600.0 / 2400.0, ScreenshotTranscoder.scaleFactor(1080, 2400, o), 1e-9)
    }

    @Test
    fun smallerOfScaleAndMaxSideWins() {
        assertEquals(0.5, ScreenshotTranscoder.scaleFactor(1080, 2400, opts("""{"scale":0.5,"maxSide":1600}""")), 1e-9)
        assertEquals(800.0 / 2400.0, ScreenshotTranscoder.scaleFactor(1080, 2400, opts("""{"scale":0.5,"maxSide":800}""")), 1e-9)
    }

    @Test
    fun maxSideLargerThanImageDoesNotUpscale() {
        assertEquals(1.0, ScreenshotTranscoder.scaleFactor(1080, 2400, opts("""{"maxSide":4000}""")), 0.0)
    }
}
