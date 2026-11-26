package dev.pravah.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Builds synthetic Sample lists to exercise CalibrationEngine.
 *
 * [unitDivisor]    1=µA, 1000=mA (what the device actually emits)
 * [signMultiplier] +1=charging is positive, -1=inverted
 * [currentMa]      charging current magnitude in mA (positive)
 * [durationMinutes] length of the charging window
 */
private fun buildChargingSamples(
    unitDivisor: Long,
    signMultiplier: Int,
    currentMa: Double,
    durationMinutes: Double,
    stepSeconds: Long = 10,
): List<Sample> {
    val stepMs = stepSeconds * 1000L
    val steps = ((durationMinutes * 60) / stepSeconds).toInt()
    val rawCurrentPerUnit = (currentMa * 1000 / unitDivisor).toLong() * signMultiplier  // raw value the device reports
    val uahPerStep = (currentMa / 1000.0 * stepMs / 3_600_000.0 * 1_000_000.0).toLong()

    return buildList {
        var chargeCounter = 0L
        for (i in 0..steps) {
            add(Sample(i * stepMs, rawCurrentPerUnit, chargeCounter))
            chargeCounter += uahPerStep
        }
    }
}

private fun buildFlatSamples(steps: Int = 50): List<Sample> =
    (0..steps).map { i -> Sample(i * 10_000L, 0L, 500_000L) }

private fun buildNoisySamples(steps: Int = 50): List<Sample> {
    var sign = 1L
    return (0..steps).map { i ->
        sign = -sign
        Sample(i * 10_000L, sign * 100L, 500_000L)
    }
}

class CalibrationEngineTest : FunSpec({

    context("µA with positive sign (normal)") {
        val samples = buildChargingSamples(unitDivisor = 1, signMultiplier = 1, currentMa = 2000.0, durationMinutes = 30.0)
        val profile = CalibrationEngine.calibrate(samples)
        test("unit divisor is 1") { profile.unitDivisor shouldBe 1L }
        test("sign is +1") { profile.signMultiplier shouldBe 1 }
        test("confidence is HIGH") { profile.confidence shouldBe Confidence.HIGH }
    }

    context("mA with positive sign (Samsung-style)") {
        val samples = buildChargingSamples(unitDivisor = 1000, signMultiplier = 1, currentMa = 2000.0, durationMinutes = 30.0)
        val profile = CalibrationEngine.calibrate(samples)
        test("unit divisor is 1000") { profile.unitDivisor shouldBe 1000L }
        test("sign is +1") { profile.signMultiplier shouldBe 1 }
        test("confidence is HIGH") { profile.confidence shouldBe Confidence.HIGH }
    }

    context("µA with inverted sign") {
        val samples = buildChargingSamples(unitDivisor = 1, signMultiplier = -1, currentMa = 2000.0, durationMinutes = 30.0)
        val profile = CalibrationEngine.calibrate(samples)
        test("unit divisor is 1") { profile.unitDivisor shouldBe 1L }
        test("sign is -1") { profile.signMultiplier shouldBe -1 }
        test("confidence is HIGH") { profile.confidence shouldBe Confidence.HIGH }
    }

    context("mA with inverted sign") {
        val samples = buildChargingSamples(unitDivisor = 1000, signMultiplier = -1, currentMa = 2000.0, durationMinutes = 30.0)
        val profile = CalibrationEngine.calibrate(samples)
        test("unit divisor is 1000") { profile.unitDivisor shouldBe 1000L }
        test("sign is -1") { profile.signMultiplier shouldBe -1 }
        test("confidence is HIGH") { profile.confidence shouldBe Confidence.HIGH }
    }

    test("flat/zero current → UNKNOWN") {
        val profile = CalibrationEngine.calibrate(buildFlatSamples())
        profile.confidence shouldBe Confidence.UNKNOWN
    }

    test("noisy straddling-zero current → never HIGH") {
        val profile = CalibrationEngine.calibrate(buildNoisySamples())
        profile.confidence shouldNotBe Confidence.HIGH
    }

    test("sub-minimum window (< 1 mAh delta) → not HIGH") {
        // Only 0.5 mAh window — below MIN_DELTA_UAH
        val samples = buildChargingSamples(unitDivisor = 1, signMultiplier = 1, currentMa = 500.0, durationMinutes = 0.05)
        val profile = CalibrationEngine.calibrate(samples)
        profile.confidence shouldNotBe Confidence.HIGH
    }

    test("too few samples → UNKNOWN") {
        val profile = CalibrationEngine.calibrate(emptyList())
        profile.confidence shouldBe Confidence.UNKNOWN
    }

    test("single sample → UNKNOWN") {
        val profile = CalibrationEngine.calibrate(listOf(Sample(0L, 2_000_000L, 100_000L)))
        profile.confidence shouldBe Confidence.UNKNOWN
    }

    context("normalize helper") {
        test("mA inverted (-2000 raw) → +2_000_000 µA") {
            val profile = CalibrationProfile(1000L, -1, Confidence.HIGH)
            // -2000 mA * -1 sign * 1000 = +2_000_000 µA
            CalibrationEngine.normalize(-2000L, profile) shouldBe 2_000_000L
        }
        test("µA positive (1_500_000 raw) → +1_500_000 µA") {
            val profile = CalibrationProfile(1L, 1, Confidence.HIGH)
            CalibrationEngine.normalize(1_500_000L, profile) shouldBe 1_500_000L
        }
        test("µA inverted (-1_500_000 raw) → +1_500_000 µA") {
            val profile = CalibrationProfile(1L, -1, Confidence.HIGH)
            CalibrationEngine.normalize(-1_500_000L, profile) shouldBe 1_500_000L
        }
    }
})
