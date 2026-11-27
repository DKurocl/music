package ua.olex.music.model

/**
 * Automatic work only. User commands, ongoing audio, what the owner is looking at (visible covers,
 * lock-screen artwork) and durable records (play history) are outside this policy.
 */
enum class OptionalWork(val foregroundOnly: Boolean = false, val usesNetwork: Boolean = true) {
    PROVIDER(true), REMOTE_QUEUE_REFILL, LOCK_SCREEN_LYRICS, PREFETCH(true), METERED_MOTION_COVER(true),
    LOCAL_SCAN(true, false), SESSION_TASTE(true, false), LEARNING,
}

enum class ThermalPressure { UNKNOWN, NORMAL, MODERATE, SEVERE }
enum class BatteryBand {
    NORMAL, LOW, CRITICAL;

    /** A stable tier across noisy readings around 10% and 25%. */
    fun after(percent: Int): BatteryBand = when {
        percent <= 10 || (this == CRITICAL && percent < 13) -> CRITICAL
        percent <= 25 || (this != NORMAL && percent < 28) -> LOW
        else -> NORMAL
    }
}
enum class ResourceDenial { DORMANT, BLACKOUT, UNKNOWN_POWER, THERMAL, OFFLINE, METERED, NOT_CHARGING, NO_CONSENT }

/** Immutable signals from the work owner and Android. Unknown signals never authorize heavy work. */
data class ResourceInputs(
    val visible: Boolean,
    val interactive: Boolean?,
    val saver: Boolean?,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val thermal: ThermalPressure,
    val validated: Boolean?,
    val unmetered: Boolean?,
    val blackout: Boolean = false,
    val chargingLearning: Boolean = false,
    val batteryBand: BatteryBand? = batteryPercent?.let(BatteryBand.NORMAL::after),
)

/** Default battery thresholds leave headroom without changing audio quality or user commands. */
object ResourcePolicy {
    fun denial(inputs: ResourceInputs, work: OptionalWork): ResourceDenial? {
        if (inputs.blackout || inputs.saver == true ||
            (inputs.charging != true && inputs.batteryBand == BatteryBand.CRITICAL)) return ResourceDenial.BLACKOUT
        if (work == OptionalWork.LOCK_SCREEN_LYRICS && inputs.interactive != true) return ResourceDenial.DORMANT
        if ((!inputs.visible || inputs.interactive != true) &&
            (work.foregroundOnly || (work == OptionalWork.LEARNING && !inputs.chargingLearning))) return ResourceDenial.DORMANT
        if (inputs.saver != false || inputs.batteryBand == null || inputs.thermal == ThermalPressure.UNKNOWN) {
            return ResourceDenial.UNKNOWN_POWER
        }
        if (inputs.thermal == ThermalPressure.SEVERE) return ResourceDenial.THERMAL
        if (!work.usesNetwork) return null
        if (inputs.validated != true) return ResourceDenial.OFFLINE
        // A radio continuation is a few kilobytes for a stream that is already playing over this
        // network, so mobile data does not stop it (the owner chose that stream on this network).
        if (work != OptionalWork.METERED_MOTION_COVER && work != OptionalWork.REMOTE_QUEUE_REFILL &&
            inputs.unmetered != true) return ResourceDenial.METERED
        if (work == OptionalWork.PREFETCH || work == OptionalWork.METERED_MOTION_COVER) {
            if (inputs.thermal == ThermalPressure.MODERATE) return ResourceDenial.THERMAL
            if (inputs.charging != true && inputs.batteryBand == BatteryBand.LOW) return ResourceDenial.BLACKOUT
        }
        if (work == OptionalWork.LEARNING) {
            if (!inputs.chargingLearning) return ResourceDenial.NO_CONSENT
            if (inputs.charging != true) return ResourceDenial.NOT_CHARGING
            if (inputs.thermal == ThermalPressure.MODERATE) return ResourceDenial.THERMAL
        }
        return null
    }
}
