package eu.kanade.tachiyomi.extension.api

import android.content.Context
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.core.common.GlobalCustomPreferences
import mihon.domain.extensionrepo.interactor.GetExtensionRepo
import mihon.domain.extensionrepo.interactor.UpdateExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.time.Instant
import kotlin.time.Duration.Companion.days

internal class ExtensionApi {

    private val networkService: NetworkHelper by injectLazy()
    private val preferenceStore: PreferenceStore by injectLazy()
    private val getExtensionRepo: GetExtensionRepo by injectLazy()
    private val updateExtensionRepo: UpdateExtensionRepo by injectLazy()
    private val extensionManager: ExtensionManager by injectLazy()
    private val customPreferences: GlobalCustomPreferences by injectLazy()
    private val json: Json by injectLazy()

    private val lastExtCheck: Preference<Long> by lazy {
        preferenceStore.getLong(Preference.appStateKey("last_ext_check"), 0)
    }

    private data class UpdateCandidate(
        val installed: Extension.Installed,
        val available: Extension.Available,
    )

    suspend fun findExtensions(): List<Extension.Available> {
        return withIOContext {
            getExtensionRepo.getAll()
                .map { async { getExtensions(it) } }
                .awaitAll()
                .flatten()
        }
    }

    private suspend fun getExtensions(extRepo: ExtensionRepo): List<Extension.Available> {
        val repoBaseUrl = extRepo.baseUrl
        return try {
            val response = networkService.client
                .newCall(GET("$repoBaseUrl/index.min.json"))
                .awaitSuccess()

            with(json) {
                response
                    .parseAs<List<ExtensionJsonObject>>()
                    .toExtensions(repoBaseUrl)
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to get extensions from $repoBaseUrl" }
            emptyList()
        }
    }

    suspend fun checkForUpdates(
        context: Context,
        fromAvailableExtensionList: Boolean = false,
    ): List<Extension.Installed>? {
        // Limit checks to once a day at most
        if (!fromAvailableExtensionList &&
            Instant.now().toEpochMilli() < lastExtCheck.get() + 1.days.inWholeMilliseconds
        ) {
            return null
        }

        // Update extension repo details
        updateExtensionRepo.awaitAll()

        val extensions = if (fromAvailableExtensionList) {
            extensionManager.availableExtensionsFlow.value
        } else {
            findExtensions().also { lastExtCheck.set(Instant.now().toEpochMilli()) }
        }

        extensionManager.isInitialized.first { it }

        val extensionsByPkg = extensions.associateBy(Extension.Available::pkgName)

        val installedExtensions = ExtensionLoader.loadExtensions(context)
            .filterIsInstance<LoadResult.Success>()
            .map { it.extension }

        val updateCandidates = buildList {
            installedExtensions.forEach { installedExt ->
                val availableExt = extensionsByPkg[installedExt.pkgName] ?: return@forEach
                val hasUpdatedVer = availableExt.versionCode > installedExt.versionCode
                val hasUpdatedLib = availableExt.libVersion > installedExt.libVersion
                val hasUpdate = hasUpdatedVer || hasUpdatedLib
                if (hasUpdate) {
                    add(UpdateCandidate(installedExt, availableExt))
                }
            }
        }

        if (updateCandidates.isEmpty()) {
            extensionManager.setAvailableExtensions(extensions)
            return emptyList()
        }

        extensionManager.setAvailableExtensions(extensions)

        if (fromAvailableExtensionList || !customPreferences.extensionsAutoUpdates.get()) {
            ExtensionUpdateNotifier(context).promptUpdates(updateCandidates.map { it.installed.name })
            return updateCandidates.map { it.installed }
        }

        return autoUpdateExtensions(context, extensions, updateCandidates)
    }

    private suspend fun autoUpdateExtensions(
        context: Context,
        extensions: List<Extension.Available>,
        updateCandidates: List<UpdateCandidate>,
    ): List<Extension.Installed> {
        val updatedNames = mutableListOf<String>()
        val leftoverNames = mutableListOf<String>()

        extensionManager.runAutoUpdateSession {
            updateCandidates.forEach { candidate ->
                val finalStep = runCatching {
                    extensionManager.installExtensionForAutoUpdate(candidate.available)
                        .first { it.isCompleted() }
                }.getOrElse { InstallStep.Error }

                when (finalStep) {
                    InstallStep.Installed -> updatedNames += candidate.installed.name
                    InstallStep.RequiresUserAction,
                    InstallStep.Idle,
                    InstallStep.Error,
                    -> leftoverNames += candidate.installed.name
                    else -> Unit
                }
            }
        }

        val notifier = ExtensionUpdateNotifier(context)
        if (updatedNames.isNotEmpty()) {
            notifier.autoUpdated(updatedNames)
        }
        if (leftoverNames.isNotEmpty()) {
            notifier.promptUpdates(leftoverNames)
        }

        return updateCandidates.map { it.installed }
    }

    private fun List<ExtensionJsonObject>.toExtensions(repoUrl: String): List<Extension.Available> {
        return this
            .filter {
                val libVersion = it.extractLibVersion()
                libVersion >= ExtensionLoader.LIB_VERSION_MIN && libVersion <= ExtensionLoader.LIB_VERSION_MAX
            }
            .map {
                Extension.Available(
                    name = it.name.substringAfter("Tachiyomi: "),
                    pkgName = it.pkg,
                    versionName = it.version,
                    versionCode = it.code,
                    libVersion = it.extractLibVersion(),
                    lang = it.lang,
                    isNsfw = it.nsfw == 1,
                    sources = it.sources?.map(extensionSourceMapper).orEmpty(),
                    apkName = it.apk,
                    iconUrl = "$repoUrl/icon/${it.pkg}.png",
                    repoUrl = repoUrl,
                )
            }
    }

    fun getApkUrl(extension: Extension.Available): String {
        return "${extension.repoUrl}/apk/${extension.apkName}"
    }

    private fun ExtensionJsonObject.extractLibVersion(): Double {
        return version.substringBeforeLast('.').toDouble()
    }
}

@Serializable
private data class ExtensionJsonObject(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val sources: List<ExtensionSourceJsonObject>?,
)

@Serializable
private data class ExtensionSourceJsonObject(
    val id: Long,
    val lang: String,
    val name: String,
    val baseUrl: String,
)

private val extensionSourceMapper: (ExtensionSourceJsonObject) -> Extension.Available.Source = {
    Extension.Available.Source(
        id = it.id,
        lang = it.lang,
        name = it.name,
        baseUrl = it.baseUrl,
    )
}
