@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.source.model

class SAnimeImpl : SAnime {

    override lateinit var url: String

    override lateinit var title: String

    override var original_title: String? = null

    override var country: String? = null

    override var studio: String? = null

    override var producer: String? = null

    override var director: String? = null

    override var writer: String? = null

    override var year: String? = null

    override var duration: String? = null

    override var description: String? = null

    override var genre: String? = null

    override var status: Int = 0

    override var thumbnail_url: String? = null

    override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE

    override var initialized: Boolean = false
}
