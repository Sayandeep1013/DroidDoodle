package dev.droiddoodle.agent

import dev.droiddoodle.inference.StopReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Trace export, written by hand rather than generated from annotations.
 *
 * A trace is the project's research output: it is meant to be read by tooling
 * that is not this app, possibly long after this app changed. That makes the
 * wire format a thing to be designed and held stable, not a by-product of
 * whatever the data classes happen to look like this week. Hand-writing it also
 * avoids annotating types that reach into `:inference`, which carries no
 * serialization plugin.
 *
 * The cost is that encode and decode can drift apart, so
 * `TraceJsonTest` round-trips a maximal record and asserts equality. Every field
 * added to [TraceRecord] must be added here, and that test is what enforces it.
 */
public object TraceJson {

    /** Bump when a change would break a reader written against the old shape. */
    public const val SCHEMA_VERSION: Int = 1

    private val pretty = Json { prettyPrint = true }
    private val compact = Json

    public fun encode(record: TraceRecord, prettyPrint: Boolean = true): String {
        val json = if (prettyPrint) pretty else compact
        return json.encodeToString(JsonObject.serializer(), toJson(record))
    }

    /** Encodes several turns as one document, which is the useful export unit. */
    public fun encodeAll(records: List<TraceRecord>, prettyPrint: Boolean = true): String {
        val json = if (prettyPrint) pretty else compact
        val document = buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            putJsonArray("turns") { records.forEach { add(toJson(it)) } }
        }
        return json.encodeToString(JsonObject.serializer(), document)
    }

    public fun decode(text: String): TraceRecord =
        fromJson(compact.parseToJsonElement(text).jsonObject)

    public fun decodeAll(text: String): List<TraceRecord> {
        val document = compact.parseToJsonElement(text).jsonObject
        val version = document["schemaVersion"]?.jsonPrimitive?.int ?: SCHEMA_VERSION
        require(version == SCHEMA_VERSION) {
            "trace document is schema $version, this build reads $SCHEMA_VERSION"
        }
        return document.getValue("turns").jsonArray.map { fromJson(it.jsonObject) }
    }

    // -- encode ----------------------------------------------------------

    private fun toJson(record: TraceRecord): JsonObject = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put("turnId", record.turnId)
        put("startedAtMillis", record.startedAtMillis)
        put("strategyId", record.strategyId)
        put("modelId", record.modelId)
        put("userMessage", record.userMessage)
        put("outcome", record.outcome.name)
        putStringMap("settingsSnapshot", record.settingsSnapshot)
        putJsonArray("rounds") { record.rounds.forEach { add(toJson(it)) } }
        putStringArray("plan", record.plan)
        putJsonObject("validation") {
            put("passed", record.validation.passed)
            put("error", record.validation.error)
        }
        record.confirmation?.let { confirmation ->
            putJsonObject("confirmation") {
                put("required", confirmation.required)
                put("granted", confirmation.granted)
                putStringArray("affectedNodes", confirmation.affectedNodes)
            }
        }
        putJsonArray("steps") { record.steps.forEach { add(toJson(it)) } }
        putStringArray("diff", record.diff)
        putJsonObject("timings") {
            put("totalMillis", record.timings.totalMillis)
            put("assembleMillis", record.timings.assembleMillis)
            put("grammarMillis", record.timings.grammarMillis)
            put("inferenceMillis", record.timings.inferenceMillis)
            put("executeMillis", record.timings.executeMillis)
        }
    }

    private fun toJson(round: InferenceRound): JsonObject = buildJsonObject {
        put("role", round.role.name)
        // Verbatim, in full. The whole point of the trace is answering "what was
        // the model actually looking at?" -- see the Trace.kt header.
        put("prompt", round.prompt)
        put("promptTokens", round.promptTokens)
        putJsonObject("blockTokens") { round.blockTokens.forEach { (k, v) -> put(k, v) } }
        putStringArray("shedBlocks", round.shedBlocks)
        put("grammarHash", round.grammarHash)
        put("rawOutput", round.rawOutput)
        put("outputTokens", round.outputTokens)
        put("prefillMillis", round.prefillMillis)
        put("decodeMillis", round.decodeMillis)
        put("cachedPrefixTokens", round.cachedPrefixTokens)
        put("stopReason", round.stopReason.name)
    }

    private fun toJson(step: StepOutcome): JsonObject = buildJsonObject {
        put("index", step.index)
        put("tool", step.tool)
        put("args", step.args)
        putStringMap("resolvedRefs", step.resolvedRefs)
        put("result", step.result.name)
        put("error", step.error)
        put("durationMillis", step.durationMillis)
    }

    // -- decode ----------------------------------------------------------

    private fun fromJson(o: JsonObject): TraceRecord = TraceRecord(
        turnId = o.str("turnId"),
        startedAtMillis = o.longAt("startedAtMillis"),
        strategyId = o.str("strategyId"),
        modelId = o.str("modelId"),
        settingsSnapshot = o.stringMap("settingsSnapshot"),
        userMessage = o.str("userMessage"),
        rounds = o.getValue("rounds").jsonArray.map { roundFrom(it.jsonObject) },
        plan = o.stringList("plan"),
        validation = o.getValue("validation").jsonObject.let {
            ValidationOutcome(passed = it.bool("passed"), error = it.strOrNull("error"))
        },
        confirmation = o["confirmation"]?.jsonObject?.let {
            ConfirmationOutcome(
                required = it.bool("required"),
                granted = it.bool("granted"),
                affectedNodes = it.stringList("affectedNodes"),
            )
        },
        steps = o.getValue("steps").jsonArray.map { stepFrom(it.jsonObject) },
        diff = o.stringList("diff"),
        outcome = Outcome.valueOf(o.str("outcome")),
        timings = o.getValue("timings").jsonObject.let {
            Timings(
                totalMillis = it.longAt("totalMillis"),
                assembleMillis = it.longAt("assembleMillis"),
                grammarMillis = it.longAt("grammarMillis"),
                inferenceMillis = it.longAt("inferenceMillis"),
                executeMillis = it.longAt("executeMillis"),
            )
        },
    )

    private fun roundFrom(o: JsonObject): InferenceRound = InferenceRound(
        role = RoundRole.valueOf(o.str("role")),
        prompt = o.str("prompt"),
        promptTokens = o.intAt("promptTokens"),
        blockTokens = o.getValue("blockTokens").jsonObject
            .mapValues { (_, v) -> v.jsonPrimitive.int },
        shedBlocks = o.stringList("shedBlocks"),
        grammarHash = o.str("grammarHash"),
        rawOutput = o.str("rawOutput"),
        outputTokens = o.intAt("outputTokens"),
        prefillMillis = o.longAt("prefillMillis"),
        decodeMillis = o.longAt("decodeMillis"),
        cachedPrefixTokens = o.intAt("cachedPrefixTokens"),
        stopReason = StopReason.valueOf(o.str("stopReason")),
    )

    private fun stepFrom(o: JsonObject): StepOutcome = StepOutcome(
        index = o.intAt("index"),
        tool = o.str("tool"),
        args = o.str("args"),
        resolvedRefs = o.stringMap("resolvedRefs"),
        result = StepResult.valueOf(o.str("result")),
        error = o.strOrNull("error"),
        durationMillis = o.longAt("durationMillis"),
    )

    // -- helpers ---------------------------------------------------------

    private fun JsonObjectBuilder.putStringArray(name: String, values: List<String>) {
        putJsonArray(name) { values.forEach { add(JsonPrimitive(it)) } }
    }

    private fun JsonObjectBuilder.putStringMap(name: String, values: Map<String, String>) {
        putJsonObject(name) { values.forEach { (k, v) -> put(k, v) } }
    }

    private fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content
    private fun JsonObject.strOrNull(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.bool(name: String): Boolean = getValue(name).jsonPrimitive.boolean
    private fun JsonObject.intAt(name: String): Int = getValue(name).jsonPrimitive.int
    private fun JsonObject.longAt(name: String): Long = getValue(name).jsonPrimitive.long
    private fun JsonObject.stringList(name: String): List<String> =
        getValue(name).jsonArray.map { it.jsonPrimitive.content }
    private fun JsonObject.stringMap(name: String): Map<String, String> =
        getValue(name).jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content }
}
