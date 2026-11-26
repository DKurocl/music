package ua.olex.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import ua.olex.music.model.BatteryBand
import ua.olex.music.model.OptionalWork
import ua.olex.music.model.ResourceInputs
import ua.olex.music.model.ResourcePolicy
import ua.olex.music.model.ThermalPressure

/** Platform listeners exist only while an optional operation or a visible owner observes policy. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class OptionalResources(
    private val app: Context,
    scope: CoroutineScope,
    private val network: DefaultNetwork,
) {
    internal data class Permit(val power: Long, val screen: Long, val visibility: Long, val network: Long)
    private data class Visibility(val shown: Boolean = false, val revision: Long = 0)
    private data class PowerState(val inputs: ResourceInputs, val revision: Long, val screenRevision: Long)

    private val visibility = MutableStateFlow(Visibility())
    val visible = visibility.map { it.shown }.distinctUntilChanged()
    val isVisible: Boolean get() = visibility.value.shown
    fun setVisible(shown: Boolean) {
        visibility.update { if (it.shown == shown) it else Visibility(shown, it.revision + 1) }
    }

    private val power = app.getSystemService(PowerManager::class.java)
    @Volatile private var latestPower: PowerState? = null
    private var batteryBand = BatteryBand.NORMAL

    private val deviceSignals: Flow<PowerState> = callbackFlow {
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        var battery = app.registerReceiver(null, batteryFilter)
        var registered = true
        var thermalRegistered = false
        fun publish() {
            if (!registered) return
            val snapshot = battery
            val level = snapshot?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = snapshot?.getIntExtra(BatteryManager.EXTRA_SCALE, 0) ?: 0
            val plug = snapshot?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val percent = if (scale > 0 && level in 0..scale) (level.toLong() * 100 / scale).toInt() else null
            val band = percent?.let(batteryBand::after)
            if (band != null) batteryBand = band
            val inputs = ResourceInputs(
                visible = false, interactive = power?.isInteractive,
                saver = power?.isPowerSaveMode,
                batteryPercent = percent,
                charging = plug.takeIf { it >= 0 }?.let { it != 0 },
                thermal = when (if (thermalRegistered) sampleThermalStatus() else null) {
                    null -> ThermalPressure.UNKNOWN
                    PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> ThermalPressure.NORMAL
                    PowerManager.THERMAL_STATUS_MODERATE -> ThermalPressure.MODERATE
                    else -> ThermalPressure.SEVERE
                },
                validated = null, unmetered = null,
                batteryBand = band,
            )
            val last = latestPower
            val previous = last?.inputs
            val baseChanged = previous == null || previous.saver != inputs.saver || previous.charging != inputs.charging ||
                previous.thermal != inputs.thermal || previous.batteryBand != inputs.batteryBand
            val observed = PowerState(inputs, (last?.revision ?: 0) + if (baseChanged) 1 else 0,
                (last?.screenRevision ?: 0) + if (previous?.interactive != inputs.interactive) 1 else 0)
            latestPower = observed
            trySend(observed)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) battery = intent
                publish()
            }
        }
        val thermal = PowerManager.OnThermalStatusChangedListener { publish() }
        app.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }, Context.RECEIVER_NOT_EXPORTED)
        thermalRegistered = try {
            power?.addThermalStatusListener(app.mainExecutor, thermal)
            power != null
        } catch (_: RuntimeException) { false }
        publish()
        awaitClose {
            registered = false
            latestPower = null
            app.unregisterReceiver(receiver)
            if (thermalRegistered) runCatching { power?.removeThermalStatusListener(thermal) }
        }
    }.conflate().flowOn(Dispatchers.Main.immediate)

    private fun sampleThermalStatus(): Int? = try { power?.currentThermalStatus }
    catch (_: RuntimeException) { null }

    private data class Snapshot(val power: PowerState, val visibility: Visibility, val connectivity: DefaultNetworkStatus) {
        fun permit(work: OptionalWork): Permit? {
            val input = power.inputs.copy(visible = visibility.shown, validated = connectivity.validated, unmetered = connectivity.unmetered)
            if (ResourcePolicy.denial(input, work) != null) return null
            return Permit(
                power.revision,
                if (work.foregroundOnly || work == OptionalWork.LOCK_SCREEN_LYRICS) power.screenRevision else 0,
                if (work.foregroundOnly) visibility.revision else 0,
                if (work.usesNetwork) connectivity.revision else 0,
            )
        }
    }

    private val conditions = combine(deviceSignals, visibility, network.conditions, ::Snapshot)
        .distinctUntilChanged().shareIn(scope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)

    fun allowed(work: OptionalWork): Flow<Boolean> =
        conditions.map { it.permit(work) != null }.distinctUntilChanged()

    /** Commit checks use dispatched Android snapshots before a collected policy result can lag. */
    fun currentPermit(work: OptionalWork): Permit? = latestPower?.let { Snapshot(it, visibility.value, network.current()).permit(work) }
    fun permitsPublication(work: OptionalWork, admitted: Permit): Boolean = currentPermit(work) == admitted

    /** Revocation cancels a suspended request and discards results from the old admission. */
    suspend fun <T> run(work: OptionalWork, workBlock: suspend () -> T?): T? =
        runWithPermit(work) { permit -> workBlock()?.takeIf { permitsPublication(work, permit) } }

    suspend fun <T> runWithPermit(work: OptionalWork, workBlock: suspend (Permit) -> T?): T? =
        conditions.map { it.permit(work) }.runWhileUnchanged(workBlock)
}
