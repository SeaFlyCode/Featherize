package com.featherize.app.domain

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock

// android.net.Uri's real implementation isn't available in a plain JVM unit test, and its
// static EMPTY field is stubbed to null by the AGP mockable android.jar — a real GalleryMedia
// still requires a non-null Uri, so each media() call gets its own inert mock instance.
private fun media(
    name: String,
    type: MediaType = MediaType.IMAGE,
    sizeBytes: Long = 0L,
    dateAddedSeconds: Long = 0L,
) = GalleryMedia(
    uri = mock(Uri::class.java),
    displayName = name,
    sizeBytes = sizeBytes,
    type = type,
    dateAddedSeconds = dateAddedSeconds,
    dateModifiedSeconds = dateAddedSeconds,
)

private fun List<GalleryMedia>.names() = map { it.displayName }

class GalleryFilterTest {

    @Test
    fun `applyFilters keeps everything for ALL type and ALL size`() {
        val list = listOf(
            media("a", MediaType.IMAGE, sizeBytes = 10L),
            media("b", MediaType.VIDEO, sizeBytes = 20L),
        )
        assertEquals(listOf("a", "b"), list.applyFilters(MediaTypeFilter.ALL, SizeFilter.ALL).names())
    }

    @Test
    fun `applyFilters filters by media type`() {
        val image = media("a", MediaType.IMAGE)
        val video = media("b", MediaType.VIDEO)
        val result = listOf(image, video).applyFilters(MediaTypeFilter.IMAGE, SizeFilter.ALL)
        assertEquals(listOf("a"), result.names())
    }

    @Test
    fun `applyFilters excludes files below the size threshold`() {
        val small = media("small", sizeBytes = 500_000L)
        val big = media("big", sizeBytes = 2_000_000L)
        val result = listOf(small, big).applyFilters(MediaTypeFilter.ALL, SizeFilter.ABOVE_1MB)
        assertEquals(listOf("big"), result.names())
    }

    @Test
    fun `applyFilters is inclusive at the exact size threshold`() {
        val exact = media("exact", sizeBytes = SizeFilter.ABOVE_1MB.minBytes)
        val result = listOf(exact).applyFilters(MediaTypeFilter.ALL, SizeFilter.ABOVE_1MB)
        assertEquals(listOf("exact"), result.names())
    }

    @Test
    fun `applySort orders by newest date descending`() {
        val old = media("old", dateAddedSeconds = 100L)
        val new = media("new", dateAddedSeconds = 200L)
        val result = listOf(old, new).applySort(SortOption.DATE_NEWEST)
        assertEquals(listOf("new", "old"), result.names())
    }

    @Test
    fun `applySort orders by largest size descending`() {
        val small = media("small", sizeBytes = 10L)
        val big = media("big", sizeBytes = 1000L)
        val result = listOf(small, big).applySort(SortOption.SIZE_LARGEST)
        assertEquals(listOf("big", "small"), result.names())
    }
}
