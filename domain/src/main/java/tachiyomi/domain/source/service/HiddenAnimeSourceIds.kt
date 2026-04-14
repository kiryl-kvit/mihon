package tachiyomi.domain.source.service

import kotlinx.coroutines.flow.Flow

interface HiddenAnimeSourceIds {
    fun get(): Set<Long>

    fun subscribe(): Flow<Set<Long>>
}
