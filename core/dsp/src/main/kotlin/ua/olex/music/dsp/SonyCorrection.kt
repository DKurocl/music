package ua.olex.music.dsp

/** AutoEq correction from Rtings B&K 5128 measurements, not an individual ear/fit measurement. */
object SonyCorrection {
    const val name = "Sony WF-1000XM5 · AutoEq"
    const val source = "https://github.com/jaakkopasanen/AutoEq/tree/master/results/Rtings/Bruel%20%26%20Kjaer%205128%20in-ear/Sony%20WF-1000XM5"
    val settings = EqualizerSettings(true, -4.8, listOf(
        EqualizerBand(105.0, -1.0, 0.70, FilterType.LOW_SHELF),
        EqualizerBand(135.0, -3.3, 0.48), EqualizerBand(540.0, 4.5, 1.53),
        EqualizerBand(399.0, -0.9, 1.11), EqualizerBand(927.0, 1.4, 1.60),
        EqualizerBand(10_000.0, 4.8, 0.70, FilterType.HIGH_SHELF),
        EqualizerBand(9_764.0, -3.2, 3.87), EqualizerBand(1_975.0, -0.8, 3.15),
        EqualizerBand(10_000.0, 0.3, 3.43), EqualizerBand(4_313.0, -1.5, 6.00),
    ), name)
}
