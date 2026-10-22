package com.yumenode.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del calculo de progreso de descargas.
 *
 * Fijan la aritmetica de velocidad, ETA y progreso, y la politica de
 * reintentos con backoff exponencial.
 */
class DownloadProgressTest {

    // ---------- backoff exponencial ----------

    @Test
    fun theFirstRetryWaitsOneSecond() {
        assertEquals(1000L, DownloadProgress.retryDelayMs(1))
    }

    @Test
    fun theDelayDoublesWithEachAttempt() {
        assertEquals(1000L, DownloadProgress.retryDelayMs(1))
        assertEquals(2000L, DownloadProgress.retryDelayMs(2))
        assertEquals(4000L, DownloadProgress.retryDelayMs(3))
        assertEquals(8000L, DownloadProgress.retryDelayMs(4))
    }

    // ---------- politica de reintento ----------

    @Test
    fun itRetriesWhileThereAreAttemptsLeft() {
        assertTrue(DownloadProgress.shouldRetry(attempt = 1))
        assertTrue(DownloadProgress.shouldRetry(attempt = 2))
    }

    @Test
    fun itStopsAtTheMaximum() {
        assertFalse(DownloadProgress.shouldRetry(attempt = 3))
        assertFalse(DownloadProgress.shouldRetry(attempt = 4))
    }

    @Test
    fun itDoesNotRetryWhenCancelled() {
        assertFalse(DownloadProgress.shouldRetry(attempt = 1, cancelled = true))
    }

    @Test
    fun theDefaultMaximumIsThree() {
        assertEquals(3, DownloadProgress.MAX_RETRIES)
    }

    // ---------- velocidad, progreso y ETA ----------

    @Test
    fun theProgressIsTheDownloadedFraction() {
        val s = DownloadProgress.compute(downloadedBytes = 500, totalBytes = 1000, elapsedMs = 10_000)
        assertEquals(0.5, s.progress, 0.0001)
    }

    @Test
    fun theSpeedIsBytesPerSecond() {
        // 1000 bytes en 2 segundos -> 500 B/s
        val s = DownloadProgress.compute(downloadedBytes = 1000, totalBytes = 2000, elapsedMs = 2_000)
        assertEquals(500.0, s.speedBytesPerSec, 0.001)
    }

    @Test
    fun theEtaIsTheRemainingTime() {
        // 1000 de 2000 bytes a 500 B/s -> quedan 1000 bytes -> 2 s
        val s = DownloadProgress.compute(downloadedBytes = 1000, totalBytes = 2000, elapsedMs = 2_000)
        assertEquals(2L, s.etaSeconds)
    }

    @Test
    fun theEtaIsNullWhenTheSpeedIsZero() {
        // Sin tiempo transcurrido no hay velocidad, y sin velocidad no hay ETA.
        val s = DownloadProgress.compute(downloadedBytes = 1000, totalBytes = 2000, elapsedMs = 0)
        assertEquals(0.0, s.speedBytesPerSec, 0.0001)
        assertNull(s.etaSeconds)
    }

    @Test
    fun anUnknownTotalSizeMeansNoProgress() {
        val s = DownloadProgress.compute(downloadedBytes = 1000, totalBytes = -1, elapsedMs = 5_000)
        assertEquals(0.0, s.progress, 0.0001)
        assertNull(s.etaSeconds)
    }

    @Test
    fun aZeroTotalSizeMeansNoProgress() {
        val s = DownloadProgress.compute(downloadedBytes = 1000, totalBytes = 0, elapsedMs = 5_000)
        assertEquals(0.0, s.progress, 0.0001)
        assertNull(s.etaSeconds)
    }

    @Test
    fun theDownloadedBytesAreKeptInTheSnapshot() {
        val s = DownloadProgress.compute(downloadedBytes = 1234, totalBytes = 5678, elapsedMs = 1_000)
        assertEquals(1234L, s.downloadedBytes)
        assertEquals(5678L, s.totalBytes)
    }

    @Test
    fun theProgressNeverExceedsOne() {
        val s = DownloadProgress.compute(downloadedBytes = 2000, totalBytes = 2000, elapsedMs = 1_000)
        assertEquals(1.0, s.progress, 0.0001)
    }

    // ---------- tamano minimo aceptable ----------

    @Test
    fun aCompleteFileIsAccepted() {
        assertTrue(DownloadProgress.isSizeAcceptable(actualBytes = 1000, totalBytes = 1000))
    }

    @Test
    fun aTruncatedFileIsRejected() {
        assertFalse(DownloadProgress.isSizeAcceptable(actualBytes = 500, totalBytes = 1000))
    }

    @Test
    fun theMinimumRatioIsRespected() {
        // Con ratio 0.98, el 97 % no llega; el 99 % si.
        assertFalse(DownloadProgress.isSizeAcceptable(actualBytes = 970, totalBytes = 1000))
        assertTrue(DownloadProgress.isSizeAcceptable(actualBytes = 990, totalBytes = 1000))
    }

    @Test
    fun anUnknownTotalSizeIsAccepted() {
        assertTrue(DownloadProgress.isSizeAcceptable(actualBytes = 10, totalBytes = 0))
        assertTrue(DownloadProgress.isSizeAcceptable(actualBytes = 10, totalBytes = -1))
    }
}
