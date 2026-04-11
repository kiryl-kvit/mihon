package eu.kanade.tachiyomi.extension.model

enum class ExtensionType(val metadataValue: String) {
    MANGA("manga"),
    VIDEO("video"),
    ;

    companion object {
        fun fromMetadataValue(value: String?): ExtensionType? {
            return entries.firstOrNull { it.metadataValue.equals(value, ignoreCase = true) }
        }
    }
}
