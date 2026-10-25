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
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive


/**
 * The [JsSourceRuntime] engine outcome: quickjs-kt, one QuickJS instance per
 * plugin (per-plugin isolation), every engine action (creation, bindings,
 * evaluation, deferred closes) serialized on the single dedicated
 * "anansi-js-engine" dispatcher thread (QuickJS is not thread-safe; plugin
 * JavaScript never runs on the Android main thread). Plugin promise chains
 * settle inside quickjs-kt's suspend `evaluate`; a plugin-initiated fetch
 * resolves through the [FetchBridge] async binding. Slots live in an LRU
 * bounded at [SourceRuntimeLimits.SLOT_LRU_MAX_ENTRIES]; a same-dist reload
 * returns the live slot, a changed dist replaces it with the old slot's
 * close deferred onto the engine thread so in-flight calls finish first.
 * Per-slot storage scopes live here and survive every slot close. The engine
 * ships bundled in the APK and is never downloaded.
 */
class QuickJsSourceRuntime(
    private val hostScript: String,
    private val vendorScript: String,
    private val fetchBridge: FetchBridge,
    private val evaluationTimeoutMillis: Long = DEFAULT_EVALUATION_TIMEOUT_MILLIS,
    private val callDeadlineMillis: Long = SourceRuntimeLimits.CALL_DEADLINE_MILLIS,
    private val slotMemoryLimitBytes: Long = SourceRuntimeLimits.SLOT_MEMORY_LIMIT_BYTES,
    private val slotLruMaxEntries: Int = SourceRuntimeLimits.SLOT_LRU_MAX_ENTRIES,
    private val onLog: (level: String, message: String) -> Unit = { _, _ -> },
) : JsSourceRuntime, AutoCloseable {

    private val engineExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "anansi-js-engine").apply { isDaemon = true }
    }
    private val engineDispatcher: ExecutorCoroutineDispatcher =
        engineExecutor.asCoroutineDispatcher()

    /** Per-plugin storage scope documents, JSON-backed (shim.ts store shape). */
    private val storageDocs = ConcurrentHashMap<String, JsonObject>()

    /**
     * Live engine slots in access order (least-recently-used first), bounded
     * at [slotLruMaxEntries]; guarded by [slotLock]. Never hold [slotLock]
     * across a native close.
     */
    private val slots = LinkedHashMap<String, QuickJsPlugin>(0, 0.75f, true)
    private val slotLock = Any()

    private val loadMutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * Call completions keyed by the call id each evaluation was issued
     * with; the `__anansiCallResult` binding completes only the entry the
     * envelope's id names, so concurrent callers can never receive each
     * other's envelopes.
     */
    private val pendingCallResults = ConcurrentHashMap<Long, CompletableDeferred<String>>()

    private val nextCallId = AtomicLong(0L)

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var closed = false

    override suspend fun load(pluginId: String, dist: String): JsPlugin {
        check(!closed) { "JsSourceRuntime is closed" }
        if (dist.isBlank()) {
            throw SourcePluginException(SourceError.Incompatible("Plugin \"$pluginId\" dist is empty"))
        }
        // Concurrent same-id loads must not double-create a slot; different
        // ids load independently.
        return mutexFor(pluginId).withLock {
            val existing = synchronized(slotLock) { slots[pluginId] }
            if (existing != null && existing.dist == dist && !existing.isClosed) {
                existing
            } else {
                loadNewSlot(pluginId, dist)
            }
        }
    }

    private suspend fun loadNewSlot(pluginId: String, dist: String): QuickJsPlugin {
        val quickJs = withContext(engineDispatcher) {
            QuickJs.create(engineDispatcher).also { engine ->
                engine.evaluationTimeoutMillis = evaluationTimeoutMillis
                engine.memoryLimit = slotMemoryLimitBytes
                installBindings(engine)
            }
        }
        var succeeded = false
        try {
            withContext(engineDispatcher) { quickJs.evaluate<Unit>(hostScript) }
            withContext(engineDispatcher) { quickJs.evaluate<Unit>(vendorScript) }
            withContext(engineDispatcher) {
                quickJs.evaluate<Unit>(
                    "__anansiLoadPlugin(${literal(pluginId)}, ${literal(dist)})",
                )
            }
            succeeded = true
        } catch (e: QuickJsException) {
            throw asLoadFailure(pluginId, e)
        } finally {
            if (!succeeded) {
                runCatching { quickJs.close() }
            }
        }
        val plugin = QuickJsPlugin(pluginId, quickJs, dist)
        val (replaced, evicted) = synchronized(slotLock) {
            val previous = slots.put(pluginId, plugin)
            val collected = mutableListOf<QuickJsPlugin>()
            val eldest = slots.entries.iterator()
            // Busy slots are skipped: the live count may temporarily exceed
            // the bound rather than kill an in-flight call.
            while (slots.size > slotLruMaxEntries && eldest.hasNext()) {
                val candidate = eldest.next()
                if (candidate.value === plugin || !candidate.value.isIdle) continue
                eldest.remove()
                collected += candidate.value
            }
            previous to collected.toList()
        }
        // Replaced and evicted slots close via the engine queue: the close
        // cannot interleave with an evaluate, so an in-flight call always
        // finishes first and a stale handle fails the Kotlin-side check
        // instead of touching freed native memory.
        replaced?.let { closeWhenEngineIdle(it) }
        evicted.forEach { closeWhenEngineIdle(it) }
        return plugin
    }

    override fun close() {
        if (closed) return
        closed = true
        val all = synchronized(slotLock) {
            val values = slots.values.toList()
            slots.clear()
            values
        }
        all.forEach { it.closeNowQuietly() }
        engineDispatcher.close()
    }

    private fun mutexFor(pluginId: String): Mutex = loadMutexes.getOrPut(pluginId) { Mutex() }

    private fun touchSlot(pluginId: String) {
        synchronized(slotLock) { slots[pluginId] }
    }

    private fun closeWhenEngineIdle(plugin: QuickJsPlugin) {
        try {
            engineExecutor.execute { plugin.closeNowQuietly() }
        } catch (e: RejectedExecutionException) {
            plugin.closeNowQuietly()
        }
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
            val callId = (args.getOrNull(0) as? Number)?.toLong()
            if (callId != null) {
                pendingCallResults.remove(callId)?.complete(args.getOrNull(1) as? String ?: "{}")
            }
            null
        }
    }

    private fun asLoadFailure(pluginId: String, cause: QuickJsException): SourcePluginException {
        val message = cause.message ?: "unknown engine error"
        val (kind, reason) = classifyJsError(pluginId, message)
        return SourcePluginException(
            if (kind == JsErrorKind.Other) {
                SourceError.Incompatible(message)
            } else {
                SourceError.UnsupportedFeature(reason)
            },
            cause,
        )
    }

    private enum class JsErrorKind { UnsupportedFeature, MemoryLimit, Other }

    /**
     * JS errors reach Kotlin as `<constructor>: <message>` (typically
     * `Error: ...`); host-contract rejections carry the
     * `anansi/unsupported-feature:` marker, and QuickJS aborts an
     * allocation beyond the slot limit with its "out of memory" string.
     * Both map to the authoritative SourceError variant.
     */
    private fun classifyJsError(pluginId: String, message: String): Pair<JsErrorKind, String> {
        val normalized = message.removePrefix("Error: ").trim()
        val marker = "$UNSUPPORTED_FEATURE_PREFIX:"
        if (normalized.startsWith(marker)) {
            return JsErrorKind.UnsupportedFeature to normalized.removePrefix(marker).trim()
        }
        if (message.contains(MEMORY_ABORT_MARKER, ignoreCase = true)) {
            return JsErrorKind.MemoryLimit to memoryLimitMessage(pluginId)
        }
        return JsErrorKind.Other to message
    }

    private fun memoryLimitMessage(pluginId: String): String =
        "Plugin \"$pluginId\" exceeded its ${slotMemoryLimitBytes / BYTES_PER_MB} MB JavaScript memory limit"

    private fun literal(value: String): String = Json.encodeToString(String.serializer(), value)

    private fun QuickJsPlugin.closeNowQuietly() {
        runCatching { closeNow() }
    }

    private inner class QuickJsPlugin(
        override val pluginId: String,
        private val quickJs: QuickJs,
        val dist: String,
    ) : JsPlugin {

        private val inFlight = AtomicInteger(0)

        val isIdle: Boolean get() = inFlight.get() == 0

        @Volatile
        private var closed = false

        val isClosed: Boolean get() = closed

        override suspend fun inspect(): String = trackInFlight {
            checkUsable()
            touchSlot(pluginId)
            evaluate("__anansiInspectPlugin(${literal(pluginId)})")
        }

        override suspend fun call(method: String, argsJson: String): String = trackInFlight {
            checkUsable()
            touchSlot(pluginId)
            if (!argsJson.trimStart().startsWith("[")) {
                throw JsPluginCallException("Plugin call arguments must be a JSON array")
            }
            val callId = nextCallId.getAndIncrement()
            val completion = CompletableDeferred<String>()
            pendingCallResults[callId] = completion
            try {
                withTimeout(callDeadlineMillis) {
                    evaluate(
                        "__anansiCallPluginAsync(${callId}, ${literal(pluginId)}, ${literal(method)}, ${literal(argsJson)})" +
                            ".then(function (envelopeJson) { __anansiCallResult(${callId}, envelopeJson); })",
                    )
                    unwrapCallEnvelope(completion.await())
                }
            } catch (e: TimeoutCancellationException) {
                // An outer timeout (or caller cancellation) owns this
                // failure; only a still-active coroutine missed its own
                // call deadline.
                coroutineContext.ensureActive()
                throw SourcePluginException(
                    SourceError.Network(
                        "Plugin \"$pluginId\" did not answer within ${describeDuration(callDeadlineMillis)}",
                    ),
                )
            } catch (e: QuickJsException) {
                throw asCallFailure(e)
            } finally {
                pendingCallResults.remove(callId)
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

        private suspend fun <T> trackInFlight(block: suspend () -> T): T {
            inFlight.incrementAndGet()
            try {
                return block()
            } finally {
                inFlight.decrementAndGet()
            }
        }

        private fun checkUsable() {
            check(!closed) { "Plugin \"$pluginId\" runtime slot is closed" }
            check(!this@QuickJsSourceRuntime.closed) { "JsSourceRuntime is closed" }
        }

        private suspend fun evaluate(script: String): String = withContext(engineDispatcher) {
            // Re-checked on the engine thread: a close queued ahead of this
            // evaluate has already run, and no close can run between this
            // check and the native call on the single engine thread.
            checkUsable()
            try {
                quickJs.evaluate<String>(script)
            } catch (e: QuickJsException) {
                val (kind, _) = classifyJsError(pluginId, e.message ?: "unknown engine error")
                if (kind != JsErrorKind.Other) {
                    throw asCallFailure(e)
                }
                throw JsPluginCallException(e.message ?: "unknown engine error", e)
            }
        }

        private fun asCallFailure(cause: QuickJsException): SourcePluginException {
            val (kind, reason) = classifyJsError(pluginId, cause.message ?: "unknown engine error")
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
                val (kind, reason) = classifyJsError(pluginId, message)
                if (kind != JsErrorKind.Other) {
                    throw SourcePluginException(SourceError.UnsupportedFeature(reason))
                }
                throw JsPluginCallException(message)
            }
            return envelope.getValue("ok").jsonPrimitive.content
        }
    }

    private companion object {
        const val DEFAULT_EVALUATION_TIMEOUT_MILLIS = 60_000L
        const val UNSUPPORTED_FEATURE_PREFIX = "anansi/unsupported-feature"
        const val MEMORY_ABORT_MARKER = "out of memory"
        const val BYTES_PER_MB = 1024L * 1024

        /** User-facing durations read in seconds; tests shrink to millis. */
        fun describeDuration(millis: Long): String =
            if (millis % 1_000L == 0L) "${millis / 1_000L} s" else "$millis ms"

        val DEFAULT_STORAGE_DOC: JsonObject = Json.parseToJsonElement(
            """{"storage":{},"local":null,"session":null}""",
        ).jsonObject
    }
}
