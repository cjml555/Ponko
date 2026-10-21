// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
package com.yumenode.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la logica de division en tramos de la descarga por rangos.
 *
 * Es codigo puro (sin red, sin disco): por eso corre en JVM sin Robolectric.
 */
class RangePlanTest {

    // ---- segmentCount ---------------------------------------------------

    @Test
    fun unknownSizeUsesASingleSegment() {
        assertEquals(1, RangePlan.segmentCount(0L))
        assertEquals(1, RangePlan.segmentCount(-1L))
    }

    @Test
    fun smallFileUsesASingleSegment() {
        // 3 MB < MIN_SEGMENT_BYTES (8 MB)
        assertEquals(1, RangePlan.segmentCount(3L * 1024 * 1024))
    }

    @Test
    fun largeFileUsesTheMaximumNumberOfSegments() {
        // 200 MB / 8 MB = 25 -> topado a 4
        assertEquals(4, RangePlan.segmentCount(200L * 1024 * 1024))
    }

    @Test
    fun segmentCountIsCappedByTheParallelRequest() {
        // El fichero es enorme, pero solo se piden 2 tramos en paralelo.
        assertEquals(2, RangePlan.segmentCount(2_000L * 1024 * 1024, parallel = 2))
    }

    @Test
    fun parallelRequestIsClampedToTheAllowedRange() {
        // Un valor absurdo (0 o negativo) no debe romper la division.
        assertEquals(1, RangePlan.segmentCount(200L * 1024 * 1024, parallel = 0))
        assertEquals(4, RangePlan.segmentCount(200L * 1024 * 1024, parallel = 99))
    }

    // ---- segments -------------------------------------------------------

    @Test
    fun segmentsCoverTheWholeFileWithoutGapsOrOverlap() {
        val total = 1000L
        val segs = RangePlan.segments(total, 4)
        assertEquals(4, segs.size)
        assertEquals(0L, segs.first().start)
        assertEquals(total - 1, segs.last().end)
        for (i in 0 until segs.size - 1) {
            assertEquals("hueco/sovrapuesto en $i", segs[i].end + 1, segs[i + 1].start)
        }
    }

    @Test
    fun segmentSizesAreBalancedWithinOneByte() {
        val segs = RangePlan.segments(1001L, 4)
        val sizes = segs.map { it.size }
        assertEquals(251L, sizes[0])
        assertEquals(250L, sizes[1])
        // La diferencia entre el mayor y el menor es de 1 byte.
        assertTrue((sizes.max()!! - sizes.min()!!) <= 1L)
    }

    @Test
    fun totalSizeIsPreserved() {
        val total = 987_654_321L
        val segs = RangePlan.segments(total, 4)
        assertEquals(total, segs.sumOf { it.size })
    }

    @Test
    fun aSingleSegmentCoversEverything() {
        val segs = RangePlan.segments(500L, 1)
        assertEquals(1, segs.size)
        assertEquals(0L, segs[0].start)
        assertEquals(499L, segs[0].end)
        assertEquals(500L, segs[0].size)
    }

    @Test
    fun unknownSizeProducesNoSegments() {
        assertTrue(RangePlan.segments(0L, 4).isEmpty())
        assertTrue(RangePlan.segments(-5L, 4).isEmpty())
    }

    @Test
    fun segmentCountIsAtLeastOneEvenIfAskedForZero() {
        val segs = RangePlan.segments(100L, 0)
        assertEquals(1, segs.size)
        assertEquals(100L, segs[0].size)
    }

    // ---- rangeHeader ----------------------------------------------------

    @Test
    fun rangeHeaderUsesTheInclusiveByteForm() {
        val seg = RangePlan.Segment(0, 100, 199)
        assertEquals("bytes=100-199", seg.rangeHeader())
    }

    @Test
    fun rangeHeaderOfTheFirstSegmentStartsAtZero() {
        val seg = RangePlan.Segment(0, 0, 9)
        assertEquals("bytes=0-9", seg.rangeHeader())
    }

    // ---- pending --------------------------------------------------------

    @Test
    fun pendingSkipsTheSegmentsAlreadyDone() {
        val segs = RangePlan.segments(1000L, 4)
        val pending = RangePlan.pending(segs, setOf(0, 2))
        assertEquals(listOf(1, 3), pending.map { it.index })
    }

    @Test
    fun pendingIsEmptyWhenEverythingIsDone() {
        val segs = RangePlan.segments(1000L, 4)
        assertTrue(RangePlan.pending(segs, setOf(0, 1, 2, 3)).isEmpty())
    }

    @Test
    fun pendingIsEverythingWhenNothingIsDone() {
        val segs = RangePlan.segments(1000L, 4)
        assertEquals(4, RangePlan.pending(segs, emptySet()).size)
    }

    // ---- parseContentRange ---------------------------------------------

    @Test
    fun contentRangeIsParsed() {
        val seg = RangePlan.parseContentRange("bytes 100-199/1000")
        assertNotNull(seg)
        assertEquals(100L, seg!!.start)
        assertEquals(199L, seg.end)
    }

    @Test
    fun contentRangeIgnoresTheTotalPart() {
        val seg = RangePlan.parseContentRange("bytes 0-524287/1048576")
        assertNotNull(seg)
        assertEquals(0L, seg!!.start)
        assertEquals(524287L, seg.end)
    }

    @Test
    fun contentRangeToleratesDifferentCasingAndSpaces() {
        val seg = RangePlan.parseContentRange("  BYTES  10-20/100  ")
        assertNotNull(seg)
        assertEquals(10L, seg!!.start)
    }

    @Test
    fun malformedContentRangeIsRejected() {
        assertNull(RangePlan.parseContentRange(null))
        assertNull(RangePlan.parseContentRange(""))
        assertNull(RangePlan.parseContentRange("bytes */1000"))
        assertNull(RangePlan.parseContentRange("bytes abc-def/1000"))
        assertNull(RangePlan.parseContentRange("sin formato"))
    }

    @Test
    fun invertedContentRangeIsRejected() {
        // final < inicial: no puede ser un tramo valido
        assertNull(RangePlan.parseContentRange("bytes 200-100/1000"))
    }

    // ---- acceptsRanges --------------------------------------------------

    @Test
    fun acceptRangesIsRecognised() {
        assertTrue(RangePlan.acceptsRanges("bytes"))
        assertTrue(RangePlan.acceptsRanges("BYTES"))
        assertTrue(RangePlan.acceptsRanges(" bytes "))
    }

    @Test
    fun acceptRangesIsRejectedWhenAbsentOrDifferent() {
        assertFalse(RangePlan.acceptsRanges(null))
        assertFalse(RangePlan.acceptsRanges(""))
        assertFalse(RangePlan.acceptsRanges("none"))
        // Un CDN que no anuncia rangos devolveria el fichero entero: si se
        // aceptase, los tramos se escribirian encima unos de otros.
        assertFalse(RangePlan.acceptsRanges("none, chunked"))
    }
}
