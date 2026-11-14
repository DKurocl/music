package ua.olex.music.dsp

/**
 * Strict AutoEq/Equalizer APO parametric text import. Unsupported commands fail rather than silently
 * changing a correction curve. Import does not enable processing or claim a headphone match.
 */
object AutoEqProfile {
    private val preamp = Regex("Preamp:\\s*([-+0-9.eE]+)\\s*dB", RegexOption.IGNORE_CASE)
    private val filter = Regex("Filter\\s+(\\d+):\\s+(ON|OFF)\\s+(\\w+)\\s+Fc\\s+([-+0-9.eE]+)\\s+Hz\\s+Gain\\s+([-+0-9.eE]+)\\s+dB\\s+Q\\s+([-+0-9.eE]+)", RegexOption.IGNORE_CASE)
    fun parse(text: String, name: String): EqualizerSettings {
        require(text.length <= 65_536) { "Profile is too large" }
        val bands = mutableListOf<EqualizerBand>()
        val ids = mutableSetOf<Int>()
        var preampDb: Double? = null
        fun number(text: String): Double = requireNotNull(text.toDoubleOrNull()?.takeIf { it.isFinite() }) { "Invalid filter number" }
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isBlank()) continue
            val p = preamp.matchEntire(line)
            if (p != null) {
                require(preampDb == null) { "More than one preamp" }
                preampDb = number(p.groupValues[1]); continue
            }
            val f = requireNotNull(filter.matchEntire(line)) { "Unsupported profile command" }
            val id = requireNotNull(f.groupValues[1].toIntOrNull()) { "Invalid filter number" }
            require(ids.add(id) && ids.size <= 20) { "Duplicate or too many filters" }
            val type = when (f.groupValues[3].uppercase(java.util.Locale.ROOT)) {
                "PK" -> FilterType.PEAK
                "LS", "LSC" -> FilterType.LOW_SHELF
                "HS", "HSC" -> FilterType.HIGH_SHELF
                else -> throw IllegalArgumentException("Unsupported filter type")
            }
            val band = EqualizerBand(number(f.groupValues[4]), number(f.groupValues[5]), number(f.groupValues[6]), type)
            if (f.groupValues[2].equals("ON", ignoreCase = true)) bands += band
        }
        require(bands.isNotEmpty()) { "No enabled filters" }
        val title = name.trim().take(90).ifBlank { "Imported AutoEq" }
        return EqualizerSettings(enabled = false, preampDb = requireNotNull(preampDb) { "Missing preamp" }, bands = bands, profile = title)
    }
}
