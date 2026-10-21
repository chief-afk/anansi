package com.chief.anansi.plugin.runtime

import com.chief.anansi.plugin.JsPlugin
import com.chief.anansi.plugin.JsPluginCallException
import com.chief.anansi.plugin.JsSourceRuntime
import com.chief.anansi.plugin.error.SourceError
import com.chief.anansi.plugin.error.SourcePluginException
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The [JsSourceRuntime] engine outcome for the spike: quickjs-kt, one
 * QuickJS instance per plugin (per-plugin isolation), all engines confined
 * to a single dedicated dispatcher thread (QuickJS is not thread-safe).
 * Plugin promise chains settle inside quickjs-kt's suspend `evaluate`; a
 * plugin-initiated fetch resolves through the [FetchBridge] async binding.
 * The engine ships bundled in the APK and is never downloaded.
 */
class QuickJsSourceRuntime(
    private val hostScript: String,
    private val vendorScript: String,
    private val fetchBridge: FetchBridge,
    private val evaluationTimeoutMillis: Long = DEFAULT_EVALUATION_TIMEOUT_MILLIS,
    private val onLog: (level: String, message: String) -> Unit = { _, _ -> },
) : JsSourceRuntime, AutoCloseable {

    private val engineDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "anansi-js-engine").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    /** Per-plugin storage scope documents, JSON-backed (shim.ts store shape). */
    private val storageDocs = ConcurrentHashMap<String, JsonObject>()

    private val loaded = ConcurrentHashMap<String, QuickJsPlugin>()

    /**
     * Call completions awaiting their engine evaluation; the
     * `__anansiCallResult` binding hands each evaluation's envelope back in
     * arrival order (the engine serializes binding invocations).
     */
    private val pendingCallResults = ConcurrentLinkedQueue<CompletableDeferred<String>>()

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var closed = false

    override suspend fun load(pluginId: String, dist: String): JsPlugin {
        check(!closed) { "JsSourceRuntime is closed" }
        if (dist.isBlank()) {
            throw SourcePluginException(SourceError.Incompatible("Plugin \"$pluginId\" dist is empty"))
        }
        // One engine slot per plugin id; a reload replaces the previous slot.
        loaded.remove(pluginId)?.closeNowQuietly()

        val quickJs = QuickJs.create(engineDispatcher)
        var succeeded = false
        try {
            quickJs.evaluationTimeoutMillis = evaluationTimeoutMillis
            installBindings(quickJs)
            quickJs.evaluate<Unit>(hostScript)
            quickJs.evaluate<Unit>(vendorScript)
            quickJs.evaluate<Unit>(
                "__anansiLoadPlugin(${literal(pluginId)}, ${literal(dist)})",
            )
            succeeded = true
        } catch (e: QuickJsException) {
            throw asLoadFailure(e)
        } finally {
            if (!succeeded) {
                runCatching { quickJs.close() }
            }
        }
        val plugin = QuickJsPlugin(pluginId, quickJs)
        loaded[pluginId] = plugin
        return plugin
    }

    override fun close() {
        if (closed) return
        closed = true
        loaded.values.forEach { it.closeNowQuietly() }
        loaded.clear()
        engineDispatcher.close()
    }

    private fun installBindings(quickJs: QuickJs) {
        quickJs.function<Any?>("__anansiLog") { args ->
            onLog(args.getOrNull(0) as? String ?: "log", args.getOrNull(1) as? String ?: "")
            null
        }
        quickJs.function<String>("__anansiStorageGet") { args ->
            val owner = args.getOrNull(0) as? String ?: ""
            (storageDocs[owner] ?: DEFAULT_STORAGE_DOC).toString()
        }
        quickJs.function<Any?>("__anansiStorageSet") { args ->
            val owner = args.getOrNull(0) as? String ?: ""
            val document = json.parseToJsonElement(args.getOrNull(1) as? String ?: "{}").jsonObject
            storageDocs[owner] = document
            null
        }
        quickJs.asyncFunction<String>("__anansiFetch") { args ->
            fetchBridge.execute(args.getOrNull(0) as? String ?: "{}")
        }
        quickJs.asyncFunction<Any?>("__anansiCallResult") { args ->
            pendingCallResults.poll()?.complete(args.getOrNull(0) as? String ?: "{}")
            null
        }
    }

    private fun asLoadFailure(cause: QuickJsException): SourcePluginException {
        val message = cause.message ?: "unknown engine error"
        val (kind, reason) = classifyJsError(message)
        return SourcePluginException(
            if (kind == JsErrorKind.UnsupportedFeature) {
                SourceError.UnsupportedFeature(reason)
            } else {
                SourceError.Incompatible(message)
            },
            cause,
        )
    }

    private enum class JsErrorKind { UnsupportedFeature, Other }

    /**
     * JS errors reach Kotlin as `<constructor>: <message>` (typically
     * `Error: ...`); host-contract rejections carry the
     * `anansi/unsupported-feature:` marker and map to the authoritative
     * SourceError variant.
     */
    private fun classifyJsError(message: String): Pair<JsErrorKind, String> {
        val normalized = message.removePrefix("Error: ").trim()
        val marker = "$UNSUPPORTED_FEATURE_PREFIX:"
        if (normalized.startsWith(marker)) {
            return JsErrorKind.UnsupportedFeature to normalized.removePrefix(marker).trim()
        }
        return JsErrorKind.Other to message
    }

    private fun literal(value: String): String = Json.encodeToString(String.serializer(), value)

    private fun QuickJsPlugin.closeNowQuietly() {
        runCatching { closeNow() }
    }

    private inner class QuickJsPlugin(
        override val pluginId: String,
        private val quickJs: QuickJs,
    ) : JsPlugin {

        @Volatile
        private var closed = false

        override suspend fun inspect(): String {
            checkUsable()
            return evaluate("__anansiInspectPlugin(${literal(pluginId)})")
        }

        override suspend fun call(method: String, argsJson: String): String {
            checkUsable()
            if (!argsJson.trimStart().startsWith("[")) {
                throw JsPluginCallException("Plugin call arguments must be a JSON array")
            }
            val completion = CompletableDeferred<String>()
            pendingCallResults.add(completion)
            try {
                evaluate(
                    "__anansiCallPluginAsync(${literal(pluginId)}, ${literal(method)}, ${literal(argsJson)})" +
                        ".then(function (envelopeJson) { __anansiCallResult(envelopeJson); })",
                )
                val envelopeJson = withTimeout(evaluationTimeoutMillis + CALL_COMPLETION_GRACE_MILLIS) {
                    completion.await()
                }
                return unwrapCallEnvelope(envelopeJson)
            } catch (e: QuickJsException) {
                throw asCallFailure(e)
            } finally {
                pendingCallResults.remove(completion)
            }
        }

        override suspend fun close() {
            closeNow()
        }

        fun closeNow() {
            if (closed) return
            closed = true
            quickJs.close()
        }

        private fun checkUsable() {
            check(!closed) { "Plugin \"$pluginId\" runtime slot is closed" }
            check(!this@QuickJsSourceRuntime.closed) { "JsSourceRuntime is closed" }
        }

        private suspend fun evaluate(script: String): String = try {
            quickJs.evaluate<String>(script)
        } catch (e: QuickJsException) {
            val (kind, _) = classifyJsError(e.message ?: "unknown engine error")
            if (kind == JsErrorKind.UnsupportedFeature) {
                throw asCallFailure(e)
            }
            throw JsPluginCallException(e.message ?: "unknown engine error", e)
        }

        private fun asCallFailure(cause: QuickJsException): SourcePluginException {
            val (kind, reason) = classifyJsError(cause.message ?: "unknown engine error")
            return SourcePluginException(SourceError.UnsupportedFeature(reason), cause)
        }

        private fun unwrapCallEnvelope(envelopeJson: String): String {
            val envelope = try {
                json.parseToJsonElement(envelopeJson).jsonObject
            } catch (e: Exception) {
                throw JsPluginCallException("Malformed plugin call envelope: ${e.message}", e)
            }
            val failed = envelope["failed"]
            if (failed != null) {
                val message = failed.jsonPrimitive.content
                val (kind, reason) = classifyJsError(message)
                if (kind == JsErrorKind.UnsupportedFeature) {
                    throw SourcePluginException(SourceError.UnsupportedFeature(reason))
                }
                throw JsPluginCallException(message)
            }
            return envelope.getValue("ok").jsonPrimitive.content
        }
    }

    private companion object {
        const val DEFAULT_EVALUATION_TIMEOUT_MILLIS = 60_000L
        const val CALL_COMPLETION_GRACE_MILLIS = 5_000L
        const val UNSUPPORTED_FEATURE_PREFIX = "anansi/unsupported-feature"
        val DEFAULT_STORAGE_DOC: JsonObject = Json.parseToJsonElement(
            """{"storage":{},"local":null,"session":null}""",
        ).jsonObject
    }
}
