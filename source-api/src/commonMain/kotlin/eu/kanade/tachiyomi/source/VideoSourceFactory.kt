package eu.kanade.tachiyomi.source

/**
 * A factory for creating video sources at runtime.
 */
interface VideoSourceFactory {
    /**
     * Create a new copy of the sources.
     *
     * @return The created sources.
     */
    fun createSources(): List<VideoSource>
}
