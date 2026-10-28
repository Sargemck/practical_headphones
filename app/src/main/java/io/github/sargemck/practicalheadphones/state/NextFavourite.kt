package io.github.sargemck.practicalheadphones.state

/** Favourite modes that exist, by index; the one after the current mode, wrapping. Null if none. */
fun nextFavourite(section: ModesSection): Int? {
    val favourites = section.favourites?.slots ?: return null
    val candidates = section.modes.map { it.index }.filter { it in favourites }.sorted()
    if (candidates.isEmpty()) return null
    return candidates.firstOrNull { it > section.current } ?: candidates.first()
}
