package mihon.feature.profiles.core

import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.domain.profile.model.ProfileType

@Serializable
data class ProfileScopedBackup(
    @ProtoNumber(1) val profile: ProfileBackup,
    @ProtoNumber(2) val categories: List<BackupCategory> = emptyList(),
    @ProtoNumber(3) val manga: List<BackupManga> = emptyList(),
    @ProtoNumber(4) val preferences: List<BackupPreference> = emptyList(),
    @ProtoNumber(5) val sourcePreferences: List<BackupSourcePreferences> = emptyList(),
    @ProtoNumber(6) val anime: List<BackupAnime> = emptyList(),
)

@Serializable
data class ProfileBackup(
    @ProtoNumber(1) val uuid: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val colorSeed: Long,
    @ProtoNumber(4) val position: Long,
    @ProtoNumber(5) val requiresAuth: Boolean,
    @ProtoNumber(6) val isArchived: Boolean,
    @ProtoNumber(7) val type: ProfileType = ProfileType.MANGA,
)
