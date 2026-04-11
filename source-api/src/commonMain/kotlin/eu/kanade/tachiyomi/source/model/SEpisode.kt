@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.source.model

import java.io.Serializable

interface SEpisode : Serializable {

    var url: String

    var name: String

    var date_upload: Long

    var episode_number: Float

    fun copyFrom(other: SEpisode) {
        name = other.name
        url = other.url
        date_upload = other.date_upload
        episode_number = other.episode_number
    }

    companion object {
        fun create(): SEpisode {
            return SEpisodeImpl()
        }
    }
}
