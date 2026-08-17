package com.featherize.app.domain

enum class MediaTypeFilter(val label: String) {
    ALL("Tout"),
    IMAGE("Photos"),
    VIDEO("Vidéos"),
}

enum class SizeFilter(val label: String, val minBytes: Long) {
    ALL("Toutes tailles", 0L),
    ABOVE_1MB("> 1 Mo", 1_000_000L),
    ABOVE_10MB("> 10 Mo", 10_000_000L),
    ABOVE_50MB("> 50 Mo", 50_000_000L),
    ABOVE_200MB("> 200 Mo", 200_000_000L),
}

enum class SortOption(val label: String) {
    DATE_NEWEST("Plus récent"),
    SIZE_LARGEST("Plus lourd"),
}

fun List<GalleryMedia>.applyFilters(type: MediaTypeFilter, size: SizeFilter): List<GalleryMedia> = filter { media ->
    val matchesType = when (type) {
        MediaTypeFilter.ALL -> true
        MediaTypeFilter.IMAGE -> media.type == MediaType.IMAGE
        MediaTypeFilter.VIDEO -> media.type == MediaType.VIDEO
    }
    matchesType && media.sizeBytes >= size.minBytes
}

fun List<GalleryMedia>.applySort(sort: SortOption): List<GalleryMedia> = when (sort) {
    SortOption.DATE_NEWEST -> sortedByDescending { it.dateAddedSeconds }
    SortOption.SIZE_LARGEST -> sortedByDescending { it.sizeBytes }
}
