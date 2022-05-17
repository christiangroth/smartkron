package de.chrgroth.smartkron

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class SmartkronHistoryTests {

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
    fun `disabling history does not record anything`() {
        val registration = SmartkronRegistry.schedule(NoHistoryCounter { end() })
        registration.waitUntilInactive()

        val history = registration.ensureMetadata().history
        assertThat(history).isEmpty()
    }

    @Test
    fun `history reflects executions`() {
        val registration = SmartkronRegistry.schedule(Counter {
            if (count < 3) {
                schedule(500.milliseconds)
            } else {
                end()
            }
        })
        registration.waitUntilActive()
        registration.waitUntilInactive()

        val history = registration.ensureMetadata().history
        assertThat(history).hasSize(3)
        assertThat(history[2]::result).isEqualTo(SmartkronExecutionResult.Success(500.milliseconds, null))
        assertThat(history[1]::started).isGreaterThan(history[2].started)
        assertThat(history[1]::result).isEqualTo(SmartkronExecutionResult.Success(500.milliseconds, null))
        assertThat(history[0]::started).isGreaterThan(history[1].started)
        assertThat(history[0]::result).isEqualTo(SmartkronExecutionResult.Success(null, null))
    }

    @Test
    fun `variant reflected in history`() {
        val registration = SmartkronRegistry.schedule(Counter {
            if (count < 3) {
                schedule(500.milliseconds, variant = "variant-$count")
            } else {
                end(variant = "finished")
            }
        })
        registration.waitUntilActive()
        registration.waitUntilInactive()

        val history = registration.ensureMetadata().history
        assertThat(history).hasSize(3)
        assertThat(history[2]::result).isEqualTo(SmartkronExecutionResult.Success(500.milliseconds, "variant-1"))
        assertThat(history[1]::started).isGreaterThan(history[2].started)
        assertThat(history[1]::result).isEqualTo(SmartkronExecutionResult.Success(500.milliseconds, "variant-2"))
        assertThat(history[0]::started).isGreaterThan(history[1].started)
        assertThat(history[0]::result).isEqualTo(SmartkronExecutionResult.Success(null, "finished"))
    }
}
