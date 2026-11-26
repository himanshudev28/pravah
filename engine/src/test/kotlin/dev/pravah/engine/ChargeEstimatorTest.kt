package dev.pravah.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.random.Random

class ChargeEstimatorTest : FunSpec({

    test("taper: fast charger 80→100 takes 25–50 min, like real phones") {
        // 4000 mAh, 1 C fast charge measured at 80 % (already at taper start)
        val m = ChargeEstimator.minutesToFull(80, 4000.0, 4000.0)
        m shouldBeGreaterThan 20.0
        m shouldBeLessThan 50.0
    }

    test("taper: trickle charger is close to linear") {
        // 0.1 C → 10 h for 0→100 linear; 50→100 ≈ 5 h, taper adds a little
        val m = ChargeEstimator.minutesToFull(50, 400.0, 4000.0)
        m shouldBeGreaterThan 290.0
        m shouldBeLessThan 340.0
    }

    test("taper: measured current already tapered is not double-counted") {
        // At 95 % on a fast charger the current has dropped; remaining time must be small
        val f = ChargeEstimator.taperFactor(95, 1.0)
        val m = ChargeEstimator.minutesToFull(95, 4000.0 * f, 4000.0)
        m shouldBeLessThan 20.0
    }

    test("estimate is calculating during warm-up then stable despite ±30% noise every second") {
        val est = ChargeEstimator()
        val rnd = Random(42)
        var t = 0L
        var counter = 2_000_000L
        val shown = mutableListOf<Int>()
        repeat(600) { i ->
            val ma = 2000.0 * (1 + (rnd.nextDouble() - 0.5) * 0.6)
            counter += (2000 * 1000 / 3600.0).toLong()   // 2000 mA for 1 s
            val e = est.update(t, FlowMode.CHARGING, 50 + i / 120, ma, counter, 4000.0)
            if (i < 20) e.calculating shouldBe true
            if (i > 40) { e.minutes shouldNotBe null; shown += e.minutes!! }
            t += 1000
        }
        // Count how often the displayed value moved by more than the 1 min natural countdown
        val jumps = shown.zipWithNext().count { (a, b) -> kotlin.math.abs(a - b) > 1 }
        (jumps <= 3) shouldBe true
        // Over 9+ min of steady charging it must count down, not freeze or climb
        (shown.first() - shown.last()) shouldBeInRange 5..14
    }

    test("discharge: time to empty from drain") {
        val est = ChargeEstimator()
        var t = 0L
        var counter = 3_000_000L
        var e = Estimate.NONE
        repeat(300) {
            counter -= (300 * 1000 / 3600.0).toLong()
            e = est.update(t, FlowMode.DISCHARGING, 75, -300.0, counter, 4000.0)
            t += 1000
        }
        e.kind shouldBe EstimateKind.TO_EMPTY
        // 75 % of 4000 = 3000 mAh / 300 mA = 10 h
        e.minutes!! shouldBeInRange 560..640
    }

    test("mode switch resets warm-up") {
        val est = ChargeEstimator()
        repeat(60) { est.update(it * 1000L, FlowMode.CHARGING, 50, 2000.0, 0, 4000.0) }
        val e = est.update(61_000, FlowMode.DISCHARGING, 50, -400.0, 0, 4000.0)
        e.calculating shouldBe true
    }

    test("speed: latched sustained peak survives taper; wireless bands") {
        val s = SessionSpeedTracker()
        var t = 0L
        repeat(300) { s.update(t, 20.0, 50); t += 1000 }   // 20 W plateau
        repeat(600) { s.update(t, 4.0, 90); t += 1000 }    // taper
        ChargingSpeed.fromWatts(s.sustainedPeakWatts, wireless = false) shouldBe ChargingSpeed.FAST
        ChargingSpeed.fromWatts(10.0, wireless = true) shouldBe ChargingSpeed.FAST
        ChargingSpeed.fromWatts(10.0, wireless = false) shouldBe ChargingSpeed.NORMAL
    }

    test("display rounding") {
        ChargeEstimator.roundForDisplay(42.4) shouldBe 42
        ChargeEstimator.roundForDisplay(97.0) shouldBe 95
        ChargeEstimator.roundForDisplay(412.0) shouldBe 410
    }
})

class ChargeEstimatorSpikeTest : FunSpec({
    test("a launch spike does not skew the discharge estimate") {
        val est = ChargeEstimator()
        var e = Estimate.NONE
        for (i in 0 until 200) {
            val ma = if (i < 4) -1600.0 else -600.0
            e = est.update(i * 1000L, FlowMode.DISCHARGING, 71, ma, 0, 4150.0)
        }
        // 71 % × 4150 / 600 mA ≈ 4.9 h → expect 4 h 30 m … 5 h 10 m
        e.minutes!! shouldBeInRange 270..310
    }

    test("charge limit: ETA counts to the limit, not to 100 %") {
        // Measured on an S21 FE: 55 %, 1255 mA, 4150 mAh
        val to100 = ChargeEstimator.minutesToFull(55, 1255.0, 4150.0)
        val to85 = ChargeEstimator.minutesToFull(55, 1255.0, 4150.0, targetPercent = 85)
        to85 shouldBeLessThan to100 * 0.7
        // 30 % × 41.5 mAh / 1255 mA ≈ 59 min
        to85 shouldBeGreaterThan 50.0
        to85 shouldBeLessThan 65.0
    }

    test("charge limit change re-targets the shown ETA immediately") {
        val est = ChargeEstimator()
        var t = 0L
        var e = Estimate.NONE
        repeat(120) { e = est.update(t, FlowMode.CHARGING, 55, 1500.0, 0, 4150.0, targetPercent = 100); t += 1000 }
        val full = e.minutes!!
        e = est.update(t, FlowMode.CHARGING, 55, 1500.0, 0, 4150.0, targetPercent = 85)
        (e.minutes!! < full * 0.7) shouldBe true
    }

    test("at or above the limit there is no time-to-full") {
        val est = ChargeEstimator()
        var t = 0L
        var e = Estimate.NONE
        repeat(60) { e = est.update(t, FlowMode.CHARGING, 85, 300.0, 0, 4150.0, targetPercent = 85); t += 1000 }
        e.minutes shouldBe null
    }

    test("idle floor: a screen-on dip does not stretch the ETA") {
        val plain = ChargeEstimator()
        val floored = ChargeEstimator()
        var t = 0L
        var a = Estimate.NONE
        var b = Estimate.NONE
        repeat(180) {
            a = plain.update(t, FlowMode.CHARGING, 60, 1400.0, 0, 4150.0)
            b = floored.update(t, FlowMode.CHARGING, 60, 1400.0, 0, 4150.0, idleFloorMa = 2300.0)
            t += 1000
        }
        (b.minutes!! < a.minutes!!) shouldBe true
        // floor lower than measured is ignored
        val c = ChargeEstimator()
        var e = Estimate.NONE
        t = 0L
        repeat(180) { e = c.update(t, FlowMode.CHARGING, 60, 1400.0, 0, 4150.0, idleFloorMa = 500.0); t += 1000 }
        e.minutes shouldBe a.minutes
    }

    test("speed: a reported fast adapter is at least FAST even when the phone eats most of the power") {
        ChargingSpeed.classify(6.0, wireless = false, fastAdapter = true) shouldBe ChargingSpeed.FAST
        ChargingSpeed.classify(6.0, wireless = false, fastAdapter = false) shouldBe ChargingSpeed.SLOW
        ChargingSpeed.classify(27.0, wireless = false, fastAdapter = true) shouldBe ChargingSpeed.SUPER_FAST
    }
})
