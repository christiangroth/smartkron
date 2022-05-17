package de.chrgroth.smartkron

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import kotlinx.datetime.Clock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class SmartkronStatisticsTests {

    @BeforeEach
    fun setUp() {
        noPendingSmartkrons()
    }

    @AfterEach
    fun tearDown() {
        try {
            noPendingSmartkrons()
        } finally {
            SmartkronRegistry.shutdown()
        }
    }

    @Test
    fun `statistics are updated correctly`() {
        fun SmartkronStatistics.assert(count: Long, errors: Long, avgDuration: Duration) {
            assertThat(this::count).isEqualTo(count)
            assertThat(this::errors).isEqualTo(errors)
            assertThat(this::avgDuration).isEqualTo(avgDuration)
        }
        fun SmartkronStatistics.update(result: SmartkronExecutionResult?, duration: Duration) =
            update(SmartkronExecution(Clock.System.now(), result, duration))

        var statistics = SmartkronStatistics()
        statistics.assert(0, 0, 0.milliseconds)

        statistics = statistics.update(SmartkronExecutionResult.Success(null, null), 100.milliseconds)
        statistics.assert(1, 0, 100.milliseconds)

        statistics = statistics.update(SmartkronExecutionResult.Success(null, null), 20.milliseconds)
        statistics.assert(2, 0, 60.milliseconds)

        statistics = statistics.update(SmartkronExecutionResult.Failure("expected test failure", null, null), 180.milliseconds)
        statistics.assert(3, 1, 100.milliseconds)

        statistics = statistics.update(null, 20.milliseconds)
        statistics.assert(4, 2, 80.milliseconds)
    }

    @Test
    fun `statistics reflect executions`() {
        val registration = SmartkronRegistry.schedule(Counter {
            if (count < 3) {
                schedule(500.milliseconds)
            } else {
                abort("expected test abort")
            }
        })
        registration.waitUntilActive()
        registration.waitUntilInactive()

        val statistics = registration.ensureMetadata().statistics
        assertThat(statistics::count).isEqualTo(3)
        assertThat(statistics::errors).isEqualTo(1)
    }

    @Test
    fun `variant reflected in statistics`() {
        val registration = SmartkronRegistry.schedule(Counter {
            if (count < 3) {
                schedule(500.milliseconds, variant = "variant-$count")
            } else {
                end(variant = "finished")
            }
        })
        registration.waitUntilActive()
        registration.waitUntilInactive()

        val variantStatistics = registration.ensureMetadata().variantStatistics
        assertThat(variantStatistics).hasSize(3)
        assertThat(variantStatistics::keys).isEqualTo(setOf("variant-1", "variant-2", "finished"))
        assertThat(variantStatistics["variant-1"]).isNotNull()
        assertThat(variantStatistics["variant-1"]!!::count).isEqualTo(1)
        assertThat(variantStatistics["variant-1"]!!::errors).isEqualTo(0)
        assertThat(variantStatistics["variant-2"]).isNotNull()
        assertThat(variantStatistics["variant-2"]!!::count).isEqualTo(1)
        assertThat(variantStatistics["variant-2"]!!::errors).isEqualTo(0)
        assertThat(variantStatistics["finished"]).isNotNull()
        assertThat(variantStatistics["finished"]!!::count).isEqualTo(1)
        assertThat(variantStatistics["finished"]!!::errors).isEqualTo(0)
    }
}
