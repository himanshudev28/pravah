package dev.pravah.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class CapacityEstimatorTest : FunSpec({

    // 4000 mAh battery, charge from 20% to 80% (60% swing) → adds 2400 mAh = 2_400_000 µAh
    val cleanSession = CapacityEstimator.SessionData(
        startPercent = 20,
        endPercent = 80,
        startChargeCounterUah = 800_000L,
        endChargeCounterUah = 3_200_000L,   // +2_400_000 µAh / 0.6 = 4_000_000 µAh = 4000 mAh
    )

    test("clean session yields capacity within 5% of truth") {
        val state = CapacityEstimator.addSession(CapacityEstimator.EstimatorState.EMPTY, cleanSession)
        state.estimatedCapacityMah shouldNotBe null
        state.estimatedCapacityMah!! shouldBe (4000.0 plusOrMinus 200.0)  // ±5%
    }

    test("too-short session (< 10% delta) is rejected") {
        val shortSession = cleanSession.copy(startPercent = 50, endPercent = 58)
        val state = CapacityEstimator.addSession(CapacityEstimator.EstimatorState.EMPTY, shortSession)
        state.estimatedCapacityMah shouldBe null
        state.sessionCount shouldBe 0
    }

    test("too-small µAh delta is rejected") {
        val tinySession = cleanSession.copy(endChargeCounterUah = cleanSession.startChargeCounterUah + 10_000L)
        val state = CapacityEstimator.addSession(CapacityEstimator.EstimatorState.EMPTY, tinySession)
        state.estimatedCapacityMah shouldBe null
    }

    test("multiple sessions converge via EMA") {
        var state = CapacityEstimator.EstimatorState.EMPTY
        repeat(5) {
            state = CapacityEstimator.addSession(state, cleanSession)
        }
        state.sessionCount shouldBe 5
        state.estimatedCapacityMah!! shouldBe (4000.0 plusOrMinus 100.0)
    }

    test("health percent: 3600 mAh measured, 4000 mAh design → 90%") {
        CapacityEstimator.healthPercent(3600.0, 4000.0) shouldBe (90.0 plusOrMinus 0.01)
    }

    test("health percent is clamped to 100%") {
        CapacityEstimator.healthPercent(5000.0, 4000.0) shouldBe 100.0
    }

    test("health percent is clamped to 0%") {
        CapacityEstimator.healthPercent(-100.0, 4000.0) shouldBe 0.0
    }
})
