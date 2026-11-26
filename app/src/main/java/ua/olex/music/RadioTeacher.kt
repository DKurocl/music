package ua.olex.music

import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ua.olex.music.model.OptionalWork
import ua.olex.music.model.RadioTeacherEdge
import ua.olex.music.model.RadioTeacherGraph
import ua.olex.music.model.RadioTeacherPage
import ua.olex.music.model.RadioTeacherSeed
import ua.olex.music.model.Track
import ua.olex.music.ui.OnlinePageStore
import java.io.File
import java.io.IOException

/** One finite capture owned by an already completed provider request. */
internal class RadioTeacher(
    directory: File,
    private val account: StateFlow<String>,
    private val accountRevision: StateFlow<Long>,
    private val enabled: StateFlow<Boolean>,
    private val resources: OptionalResources,
    private val scope: CoroutineScope,
) {
    private val store = RadioTeacherStore(directory)
    private val capture = Mutex()

    fun observe(owner: String, revision: Long, seed: String, tracks: List<Track>) {
        if (owner != account.value || revision != accountRevision.value || !enabled.value || !capture.tryLock()) return
        scope.launch(Dispatchers.IO) {
            val page = RadioTeacherPage.verify(seed, tracks) ?: return@launch
            combine(account, accountRevision, enabled) { current, generation, on ->
                current == owner && generation == revision && on
            }.runWhileAllowed {
                resources.runWithPermit(OptionalWork.PROVIDER) { permit ->
                    val previous = store.get(owner)
                    val next = previous.observe(page, System.currentTimeMillis())
                    currentCoroutineContext().ensureActive()
                    if (next !== previous && owner == account.value && revision == accountRevision.value && enabled.value &&
                        resources.permitsPublication(OptionalWork.PROVIDER, permit)) store.write(owner, next)
                }
            }
        }.invokeOnCompletion { capture.unlock() }
    }

    suspend fun neighbours(owner: String, seed: String): List<RadioTeacherEdge> {
        if (owner != account.value || !RadioTeacherPage.isVideoId(seed)) return emptyList()
        val revision = accountRevision.value
        val entries = withContext(Dispatchers.IO) { store.get(owner).neighbours(seed, System.currentTimeMillis()) }
        return entries.takeIf { owner == account.value && revision == accountRevision.value }.orEmpty()
    }
}

/** Two bounded snapshots: public and one personal scope. No headers or cookies are stored. */
internal class RadioTeacherStore(directory: File) {
    private val public = AtomicFile(directory.resolve("public.json"))
    private val personal = AtomicFile(directory.resolve("personal.json"))
    private var publicSnapshot: RadioTeacherGraph? = null
    private var personalSnapshot: Pair<String, RadioTeacherGraph>? = null

    @Synchronized
    fun get(scope: String): RadioTeacherGraph {
        if (scope == "public") return publicSnapshot ?: read(public, scope).also { publicSnapshot = it }
        val key = OnlinePageStore.digest("radio-model/$scope")
        return personalSnapshot?.takeIf { it.first == key }?.second
            ?: read(personal, key).also { personalSnapshot = key to it }
    }

    @Synchronized
    fun write(scope: String, graph: RadioTeacherGraph) {
        val key = if (scope == "public") scope else OnlinePageStore.digest("radio-model/$scope")
        val file = if (scope == "public") public else personal
        val bytes = encode(key, graph).toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) return
        try {
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                file.finishWrite(stream)
            } catch (failed: IOException) {
                file.failWrite(stream)
                throw failed
            }
        } catch (_: IOException) {
            return
        }
        if (scope == "public") publicSnapshot = graph else personalSnapshot = key to graph
    }

    private fun read(file: AtomicFile, key: String): RadioTeacherGraph = try {
        val bytes = file.openRead().use { it.readNBytes(MAX_BYTES + 1) }
        if (bytes.size > MAX_BYTES) RadioTeacherGraph()
        else decode(key, JSONObject(bytes.toString(Charsets.UTF_8)))
    } catch (_: IOException) {
        RadioTeacherGraph()
    } catch (_: org.json.JSONException) {
        RadioTeacherGraph()
    } catch (_: IllegalArgumentException) {
        RadioTeacherGraph()
    }

    private companion object {
        const val VERSION = 1
        const val MAX_BYTES = 128 * 1024
        val fingerprint = Regex("[0-9a-f]{64}")

        fun encode(scope: String, graph: RadioTeacherGraph) = JSONObject()
            .put("version", VERSION)
            .put("scope", scope)
            .put("seeds", JSONArray().apply {
                graph.seeds.forEach { (video, evidence) ->
                    put(JSONArray().put(video).put(evidence.updatedAt).put(JSONArray(evidence.responses))
                        .put(JSONArray().apply {
                            evidence.edges.forEach { edge ->
                                put(JSONArray().put(edge.videoId).put(edge.bestRank).put(edge.observations).put(edge.lastObservedAt))
                            }
                        }))
                }
            })

        fun decode(scope: String, json: JSONObject): RadioTeacherGraph {
            if (json.optInt("version") != VERSION || json.optString("scope") != scope) return RadioTeacherGraph()
            val rows = json.getJSONArray("seeds")
            require(rows.length() <= RadioTeacherGraph.MAX_SEEDS)
            return RadioTeacherGraph(buildMap {
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONArray(i)
                    val seed = row.getString(0)
                    val updated = row.getLong(1)
                    val responseRows = row.getJSONArray(2)
                    val edgeRows = row.getJSONArray(3)
                    require(RadioTeacherPage.isVideoId(seed) && seed !in this && updated > 0)
                    require(responseRows.length() in 1..RadioTeacherGraph.MAX_RESPONSES)
                    require(edgeRows.length() in 1..RadioTeacherGraph.MAX_EDGES)
                    val responses = List(responseRows.length()) { responseRows.getString(it).also { hash -> require(fingerprint.matches(hash)) } }
                    val edges = List(edgeRows.length()) {
                        val edge = edgeRows.getJSONArray(it)
                        val id = edge.getString(0)
                        val rank = edge.getInt(1)
                        val count = edge.getInt(2)
                        val at = edge.getLong(3)
                        require(id != seed && RadioTeacherPage.isVideoId(id) && rank in 0 until RadioTeacherGraph.MAX_EDGES &&
                            count in 1..RadioTeacherGraph.MAX_OBSERVATIONS && at in 1..updated)
                        RadioTeacherEdge(id, rank, count, at)
                    }
                    require(edges.distinctBy { it.videoId }.size == edges.size)
                    put(seed, RadioTeacherSeed(edges, responses, updated))
                }
            })
        }
    }
}
