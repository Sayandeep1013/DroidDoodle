package dev.droiddoodle.app.model

import android.content.Context
import dev.droiddoodle.inference.PromptTemplate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One curated download candidate, exactly as it appears in `assets/models.json`.
 *
 * The list is a bundled manifest rather than a hard-coded array so the
 * candidates can change without a code change (docs/25-inference.md §6).
 */
@Serializable
internal data class ModelEntry(
    val id: String,
    val displayName: String,
    val url: String,
    val sha256: String,
    val fileBytes: Long,
    val estimatedResidentBytes: Long,
    val contextTokens: Int,
    @SerialName("promptTemplate") val promptTemplateKey: String,
    val note: String = "",
)

/** A manifest entry that survived validation, with its template resolved. */
internal data class ModelCandidate(
    val entry: ModelEntry,
    val promptTemplate: PromptTemplate,
) {
    val id: String get() = entry.id
    val displayName: String get() = entry.displayName
}

@Serializable
private data class Manifest(
    val schemaVersion: Int,
    val models: List<ModelEntry>,
)

internal object ModelCatalog {

    const val ASSET_NAME: String = "models.json"
    private const val SUPPORTED_SCHEMA = 1

    private val json = Json {
        ignoreUnknownKeys = true // `_comment` and future fields
    }

    /**
     * Reads and validates the bundled manifest.
     *
     * Entries are dropped rather than defaulted when they fail validation. A
     * candidate with an unknown template or a malformed checksum would download
     * hundreds of megabytes and then fail, or worse, load and produce output
     * bad enough to be mistaken for the model being weak.
     */
    fun load(context: Context): List<ModelCandidate> {
        val text = context.assets.open(ASSET_NAME).use { it.readBytes().decodeToString() }
        val manifest = json.decodeFromString<Manifest>(text)
        require(manifest.schemaVersion == SUPPORTED_SCHEMA) {
            "models.json is schema ${manifest.schemaVersion}, this build reads $SUPPORTED_SCHEMA"
        }
        return manifest.models.mapNotNull { it.validated() }
    }

    private fun ModelEntry.validated(): ModelCandidate? {
        val template = PromptTemplate.fromKey(promptTemplateKey) ?: return null
        if (id.isBlank() || !id.matches(SAFE_ID)) return null
        if (!url.startsWith("https://")) return null
        if (!sha256.matches(HEX_64)) return null
        if (fileBytes <= 0 || contextTokens <= 0) return null
        return ModelCandidate(this, template)
    }

    // The id becomes a filename, so it must not be able to escape the models
    // directory or collide with the `.part` suffix.
    private val SAFE_ID = Regex("^[A-Za-z0-9._-]{1,64}$")
    private val HEX_64 = Regex("^[0-9a-f]{64}$")
}
