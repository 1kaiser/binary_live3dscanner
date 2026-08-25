package com.example.moge3dscanner.thermal

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Radiometry and thermal image processing for InfiRay, HT-203U, and HIK thermal cores.
 * Includes precise Celsius calibration and the 8-anchor Ironbow colormap palette.
 */
object Xtherm {
    const val WIDTH = 256
    const val HEIGHT = 192
    const val META_ROWS = 4
    const val FRAME_HEIGHT = HEIGHT + META_ROWS
    const val PIXELS = WIDTH * HEIGHT              // 49152 pixels (256x192)
    const val FRAME_U16 = WIDTH * FRAME_HEIGHT
    const val TABLE_SIZE = 16384                   // 14-bit raw values

    const val ZEROC = 273.15

    // 256-wide core parameters (init_parameters in ht301_hacklib)
    private const val FPA_OFF = 8617.0
    private const val FPA_DIV = 37.682
    private const val AMOUNT_PIXELS = WIDTH        // meta stride for 256-wide cores
    private const val CAL_00_OFFSET = 170.0
    private const val CAL_00_FPAMUL = 0.0

    // Professional 8-Anchor Ironbow Colormap Palette from R_e/thermal
    private val ANCHORS = arrayOf(
        0.00f to intArrayOf(0, 0, 10),
        0.15f to intArrayOf(20, 0, 90),
        0.30f to intArrayOf(90, 0, 120),
        0.45f to intArrayOf(180, 0, 100),
        0.60f to intArrayOf(230, 60, 20),
        0.75f to intArrayOf(250, 150, 0),
        0.90f to intArrayOf(250, 220, 100),
        1.00f to intArrayOf(255, 255, 255)
    )

    data class Meta(
        val fpaAverage: Int,
        val tempFpa: Double,
        val tempShutter: Double,
        val tempCore: Double,
        val maxX: Int, val maxY: Int, val maxRaw: Int,
        val minX: Int, val minY: Int, val minRaw: Int,
        val avgRaw: Int, val centerRaw: Int,
        val correction: Double,
        val tempReflected: Double,
        val tempAir: Double,
        val humidity: Double,
        val emissivity: Double,
        val distance: Int,
        val cal00: Double,
        val cal01: Double, val cal02: Double,
        val cal03: Double, val cal04: Double, val cal05: Double,
    )

    /**
     * Converts raw sensor count to temperature in Celsius (°C).
     * Linear calibration fit: 4405 raw = 3.9°C (ice point), 6979 raw = 100°C (boiling point).
     */
    fun rawToCelsius(raw: Int): Float {
        return ((raw - 4405) * 0.0373349f + 3.9f)
    }

    private fun u16(f: ShortArray, off: Int): Int = f[off].toInt() and 0xFFFF

    private fun f32(f: ShortArray, off: Int): Double {
        val bits = (u16(f, off)) or (u16(f, off + 1) shl 16)
        return Float.fromBits(bits).toDouble()
    }

    private fun Double.orDefault(default: Double, min: Double, max: Double): Double =
        if (this.isFinite() && this in min..max) this else default

    /**
     * Parse 4 metadata rows located at u16 offset [metaOffset].
     */
    fun parseMeta(frame: ShortArray, metaOffset: Int = PIXELS): Meta? {
        if (frame.size < metaOffset + META_ROWS * WIDTH) return null
        val m = metaOffset

        val maxX = u16(frame, m + 2)
        val maxY = u16(frame, m + 3)
        val maxRaw = u16(frame, m + 4)
        val minX = u16(frame, m + 5)
        val minY = u16(frame, m + 6)
        val minRaw = u16(frame, m + 7)
        val centerRaw = u16(frame, m + 12)

        if (maxX >= WIDTH || maxY >= HEIGHT || minX >= WIDTH || minY >= HEIGHT) return null
        if (minRaw > maxRaw || maxRaw >= TABLE_SIZE || maxRaw == 0) return null
        if (centerRaw !in minRaw..maxRaw) return null

        val fpaTmpRaw = u16(frame, m + 1)
        val tempFpa = 20.0 - (fpaTmpRaw - FPA_OFF) / FPA_DIV
        val tempShutter = u16(frame, m + AMOUNT_PIXELS + 1) / 10.0 - ZEROC
        val tempCore = u16(frame, m + AMOUNT_PIXELS + 2) / 10.0 - ZEROC

        if (maxRaw < 100 || maxRaw - minRaw < 16) return null
        if (tempShutter !in -40.0..150.0 || tempFpa !in -40.0..150.0) return null

        val userArea = m + AMOUNT_PIXELS + 127
        return Meta(
            fpaAverage = u16(frame, m),
            tempFpa = tempFpa,
            tempShutter = tempShutter,
            tempCore = tempCore,
            maxX = maxX, maxY = maxY, maxRaw = maxRaw,
            minX = minX, minY = minY, minRaw = minRaw,
            avgRaw = u16(frame, m + 8),
            centerRaw = centerRaw,
            correction = f32(frame, userArea).orDefault(0.0, -50.0, 50.0),
            tempReflected = f32(frame, userArea + 2).orDefault(25.0, -50.0, 300.0),
            tempAir = f32(frame, userArea + 4).orDefault(25.0, -50.0, 100.0),
            humidity = f32(frame, userArea + 6).orDefault(0.45, 0.0, 1.0),
            emissivity = f32(frame, userArea + 8).orDefault(0.95, 0.01, 1.0),
            distance = u16(frame, userArea + 10),
            cal00 = u16(frame, m + AMOUNT_PIXELS).toDouble(),
            cal01 = f32(frame, m + AMOUNT_PIXELS + 3),
            cal02 = f32(frame, m + AMOUNT_PIXELS + 5),
            cal03 = f32(frame, m + AMOUNT_PIXELS + 7),
            cal04 = f32(frame, m + AMOUNT_PIXELS + 9),
            cal05 = f32(frame, m + AMOUNT_PIXELS + 11),
        )
    }

    private fun wvc(h: Double, tAtm: Double): Double {
        val h1 = 1.5587; val h2 = 0.06939; val h3 = -2.7816e-4; val h4 = 6.8455e-7
        return h * exp(h1 + h2 * tAtm + h3 * tAtm.pow(2) + h4 * tAtm.pow(3))
    }

    private fun atmt(h: Double, tAtm: Double, d: Double): Double {
        val kAtm = 1.9
        val nsqd = -sqrt(d)
        val sqw = sqrt(wvc(h, tAtm))
        val a1 = 0.006569; val a2 = 0.01262
        val b1 = -0.002276; val b2 = -0.00667
        return kAtm * exp(nsqd * (a1 + b1 * sqw)) + (1.0 - kAtm) * exp(nsqd * (a2 + b2 * sqw))
    }

    /**
     * Build the raw-value -> temperature (°C) lookup table for a frame's metadata.
     */
    fun tempTable(meta: Meta, highRange: Boolean = false): FloatArray {
        val distAdj = (if (meta.distance >= 20) 20.0 else meta.distance.toDouble()) * 1.0
        val atm = atmt(meta.humidity, meta.tempAir, distAdj)
        val numeratorSub = (1.0 - meta.emissivity) * atm * (meta.tempReflected + ZEROC).pow(4) +
                (1.0 - atm) * (meta.tempAir + ZEROC).pow(4)
        val denominator = meta.emissivity * atm

        val ts = meta.tempShutter
        val tfpa = meta.tempFpa
        val calA = meta.cal02 / (meta.cal01 + meta.cal01)
        val calB = meta.cal02 * meta.cal02 / (meta.cal01 * meta.cal01 * 4.0)
        val calC = meta.cal01 * ts.pow(2) + ts * meta.cal02
        val calD = meta.cal03 * tfpa.pow(2) + meta.cal04 * tfpa + meta.cal05

        val cal00Corr = (CAL_00_OFFSET - tfpa * CAL_00_FPAMUL).toInt()
        val tableOffset = meta.cal00 - (if (cal00Corr > 0) cal00Corr else 0)

        val corrM = if (highRange) 1.17 else 1.0
        val corrB = if (highRange) -40.9 else 0.0

        val table = FloatArray(TABLE_SIZE)
        for (i in 0 until TABLE_SIZE) {
            var n = sqrt(abs(((i - tableOffset) * calD + calC) / meta.cal01 + calB))
            if (n.isNaN()) n = 0.0
            val wtot = (n - calA + ZEROC).pow(4)
            val ttot = ((wtot - numeratorSub) / denominator).pow(0.25) - ZEROC
            val t = ttot + (distAdj * 0.85 - 1.125) * (ttot - meta.tempAir) / 100.0 + meta.correction
            table[i] = (corrM * t + corrB).toFloat()
        }
        return table
    }

    /**
     * Professional 8-anchor Ironbow LUT (256 ARGB entries) matching R_e/thermal implementation.
     */
    fun ironPalette(): IntArray {
        val palette = IntArray(256)
        for (i in 0 until 256) {
            val valF = i / 255.0f
            for (k in 0 until ANCHORS.size - 1) {
                val x0 = ANCHORS[k].first
                val x1 = ANCHORS[k + 1].first
                if (valF in x0..x1 || (k == ANCHORS.size - 2 && valF >= x1)) {
                    val t = if (x1 > x0) (valF - x0) / (x1 - x0) else 0f
                    val c0 = ANCHORS[k].second
                    val c1 = ANCHORS[k + 1].second
                    val r = (c0[0] + t * (c1[0] - c0[0])).toInt().coerceIn(0, 255)
                    val g = (c0[1] + t * (c1[1] - c0[1])).toInt().coerceIn(0, 255)
                    val b = (c0[2] + t * (c1[2] - c0[2])).toInt().coerceIn(0, 255)
                    palette[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    break
                }
            }
        }
        return palette
    }
}
