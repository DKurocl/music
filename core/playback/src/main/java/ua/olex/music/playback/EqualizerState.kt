package ua.olex.music.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.session.SessionCommand
import org.json.JSONArray
import org.json.JSONObject
import ua.olex.music.dsp.*

/** Settings belong to the active output. Sony correction is available only on the identified Sony route. */
data class EqualizerState(val routeKey: String = "none", val routeName: String = "No output reported",
    val sony: Boolean = false, val settings: EqualizerSettings = EqualizerSettings())

/** Small explicit schema shared by private session commands and local per-output preferences. */
internal object EqualizerCodec {
    fun encode(settings: EqualizerSettings): String = JSONObject().apply {
        put("version", 1); put("enabled", settings.enabled); put("preamp", settings.preampDb); put("profile", settings.profile)
        put("balance", settings.channels.balance.toDouble()); put("mono", settings.channels.mono); put("swap", settings.channels.swap)
        put("bands", JSONArray().apply { settings.bands.forEach { band -> put(JSONObject().apply {
            put("hz", band.frequencyHz); put("db", band.gainDb); put("q", band.q); put("type", band.type.name)
        }) } })
    }.toString()
    fun decode(text: String): EqualizerSettings {
        require(text.length <= 16_384)
        val row = JSONObject(text)
        require(row.getInt("version") == 1)
        val bands = row.getJSONArray("bands")
        require(bands.length() <= 20)
        val profile = row.getString("profile")
        require(profile.length <= 100)
        return EqualizerSettings(row.getBoolean("enabled"), row.getDouble("preamp"), (0 until bands.length()).map { index ->
            val band = bands.getJSONObject(index)
            EqualizerBand(band.getDouble("hz"), band.getDouble("db"), band.getDouble("q"), FilterType.valueOf(band.getString("type")))
        }, profile, ua.olex.music.dsp.ChannelMix(row.optDouble("balance", 0.0).toFloat().coerceIn(-1f, 1f),
            row.optBoolean("mono", false), row.optBoolean("swap", false)))
    }
}

/** App-private command and observed state; no platform equalizer/session effects are attached. */
internal object EqualizerCommand {
    const val ACTION = "ua.olex.music.EQUALIZER"
    const val KEY = "ua.olex.music.equalizer"
    const val ROUTE = "route"
    const val SETTINGS = "settings"
    val command = SessionCommand(ACTION, Bundle.EMPTY)
    fun bundle(state: EqualizerState) = Bundle().apply {
        putString(ROUTE, state.routeKey); putString("name", state.routeName); putBoolean("sony", state.sony)
        putString(SETTINGS, EqualizerCodec.encode(state.settings))
    }
    fun fromExtras(extras: Bundle): EqualizerState {
        val bundle = extras.getBundle(KEY) ?: return EqualizerState()
        return try { EqualizerState(bundle.getString(ROUTE) ?: "none", bundle.getString("name") ?: "Output", bundle.getBoolean("sony"),
            EqualizerCodec.decode(bundle.getString(SETTINGS) ?: return EqualizerState())) }
        catch (_: Exception) { EqualizerState() }
    }
}

/** Service-owned route isolation. Switching outputs publishes a new immutable audio-thread snapshot. */
internal class EqualizerController(private val context: Context, private val onChanged: (EqualizerState) -> Unit) {
    private val preferences = context.getSharedPreferences("equalizer-v1", Context.MODE_PRIVATE)
    var state = EqualizerState()
        private set
    @Volatile var active = EqualizerSettings()
        private set

    fun routeChanged(route: OutputRoute?) {
        val name = route?.name?.takeIf { it.isNotBlank() } ?: route?.kind?.name?.lowercase()?.replace('_', ' ') ?: "No output reported"
        val sony = route?.kind in listOf(OutputRoute.Kind.BLUETOOTH, OutputRoute.Kind.LE_AUDIO) &&
            name.filter(Char::isLetterOrDigit).lowercase().contains("wf1000xm5")
        val key = when {
            route == null -> "none"
            sony -> "sony-wf1000xm5"
            route.kind == OutputRoute.Kind.SPEAKER -> "phone-speaker"
            else -> "${route.kind.name}:$name"
        }
        if (key == state.routeKey && name == state.routeName) return
        val settings = preferences.getString(key, null)?.let { runCatching { EqualizerCodec.decode(it) }.getOrNull() } ?: EqualizerSettings()
        state = EqualizerState(key, name, sony, settings)
        active = if (key == "none") EqualizerSettings() else settings
        onChanged(state)
    }

    fun update(routeKey: String, settings: EqualizerSettings): Boolean {
        if (routeKey != state.routeKey || routeKey == "none") return false
        if (settings.profile == "Sony WF-1000XM5 · AutoEq" && !state.sony) return false
        preferences.edit().putString(routeKey, EqualizerCodec.encode(settings)).apply()
        state = state.copy(settings = settings)
        active = settings
        onChanged(state)
        // Sony correction already shapes the sound: the earbuds' own EQ goes off (Earbuds app).
        if (settings.enabled && settings.profile == ua.olex.music.dsp.SonyCorrection.name) EarbudsLink.send(context, EarbudsLink.EQ_OFF)
        return true
    }
}
