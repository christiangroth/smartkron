package de.chrgroth.smartkron

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameAs
import assertk.assertions.isTrue
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.until
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class SmartkronSchedulingTests {

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
    fun `schedule returns valid registration data`() {
        val smartkron = Counter { end() }
        val registration = SmartkronRegistry.schedule(smartkron)
        assertThat(registration::instance).isSameAs(smartkron)
        assertThat(registration::displayName).isSameAs("de.chrgroth.smartkron.Counter")
    }

    @Test
    fun `metadata contains valid basic information`() {
        val registration = SmartkronRegistry.schedule(Counter { end() })
        val metadata = registration.ensureMetadata()
        assertThat(metadata::displayName).isSameAs("de.chrgroth.smartkron.Counter")
    }

    @Test
    fun `scheduled single run counter ends correctly`() {
        val registration = SmartkronRegistry.schedule(Counter { end() })
        registration.waitUntilInactive()
    }

    @Test
    fun `ends on abort`() {
        val registration = SmartkronRegistry.schedule(Counter {
            abort("expected test failure")
        })
        registration.waitUntilInactive()
        val metadata = registration.ensureMetadata()
        assertThat(metadata::isActive).isFalse()
        assertThat(metadata::plannedNext).isNull()
    }

    @Test
    fun `can continue on failure`() {
        val registration = SmartkronRegistry.schedule(Counter {
            failed(1.seconds, "expected test failure")
        })
        registration.waitUntilActive()
        registration.waitUntilIncreased()
        registration.waitUntilIncreased()
        val metadata = registration.ensureMetadata()
        assertThat(metadata::isActive).isTrue()
        assertThat(metadata::plannedNext).isNotNull()
        registration.deactivate()
    }

    @Test
    fun `exception aborts smartkron`() {
        val registration = SmartkronRegistry.schedule(Counter {
            throw IllegalStateException("expected test exception")
        })
        registration.waitUntilInactive()
        val metadata = registration.ensureMetadata()
        assertThat(metadata::isActive).isFalse()
        assertThat(metadata::plannedNext).isNull()
    }

    @Test
    fun `continues on exception if implemented`() {
        val registration = SmartkronRegistry.schedule(FailsafeCounter {
            throw IllegalStateException("expected test exception")
        })
        registration.waitUntilActive()
        registration.waitUntilIncreased()
        registration.waitUntilIncreased()
        val metadata = registration.ensureMetadata()
        assertThat(metadata::isActive).isTrue()
        assertThat(metadata::plannedNext).isNotNull()
        registration.deactivate()
    }

    @Test
    fun `custom display name is reflected in registration and metadata`() {
        val registration = SmartkronRegistry.schedule(Counter { end() }, "fancy cron")
        assertThat(registration::displayName).isSameAs("fancy cron")
        val metadata = registration.ensureMetadata()
        assertThat(metadata::displayName).isSameAs("fancy cron")
    }

    @Test
    fun `called when active and not when inactive`() {
        val registration = SmartkronRegistry.schedule(Counter { schedule(1.seconds) })
        registration.waitUntilIncreased()

        registration.deactivate()
        registration.ensureNoInvocations()

        registration.activate()
        registration.waitUntilIncreased()

        registration.deactivate()
    }

    @Test
    fun `activation status is reflected in metadata`() {
        val registration = SmartkronRegistry.schedule(Counter { schedule(1.seconds) })
        registration.waitUntilActive()
        assertThat(registration.ensureMetadata()::isActive).isTrue()

        registration.deactivate()
        registration.waitUntilInactive()
        assertThat(registration.ensureMetadata()::isActive).isFalse()

        registration.activate()
        registration.waitUntilIncreased()
        assertThat(registration.ensureMetadata()::isActive).isTrue()
        registration.deactivate()
    }

    @Test
    fun `multiple activation does not schedule extra executions`() {
        val registration = SmartkronRegistry.schedule(Counter { schedule(1.seconds) })
        repeat(100) {
            registration.activate()
        }
        Thread.sleep(5.seconds.inWholeMilliseconds)

        registration.deactivate()
        registration.ensureNoInvocations()
        assertThat(registration.currentInvocationCount()).isLessThan(50)
    }

    @Test
    fun `deactivate long running does not take long`() {
        val registration = SmartkronRegistry.schedule(Counter { schedule(30.seconds) })
        registration.waitUntilActive()
        val beforeDeactivate = Clock.System.now()
        registration.deactivate()
        val afterDeactivate = Clock.System.now()
        registration.ensureNoInvocations()
        assertThat(beforeDeactivate.until(afterDeactivate, DateTimeUnit.SECOND)).isLessThan(25)
    }

    @Test
    fun `multiple deactivation does no harm`() {
        val registration = SmartkronRegistry.schedule(Counter { schedule(1.seconds) })
        registration.waitUntilIncreased()
        registration.deactivate()
        registration.ensureNoInvocations()
        val invocations = registration.currentInvocationCount()
        repeat(100) {
            registration.deactivate()
        }
        assertThat(registration.currentInvocationCount()).isEqualTo(invocations)
    }

    @Test
    fun `prune removes inactive only`() {
        val registrationOne = SmartkronRegistry.schedule(Counter { schedule(1.seconds) }, "one")
        val registrationTwo = SmartkronRegistry.schedule(Counter { schedule(2.seconds) }, "two")
        registrationOne.waitUntilActive()
        registrationTwo.waitUntilActive()

        registrationOne.deactivate()
        registrationOne.waitUntilInactive()
        registrationOne.ensureNoInvocations()

        val removedMetadata = SmartkronRegistry.prune()
        assertThat(removedMetadata::size).isEqualTo(1)
        assertThat(removedMetadata.first()::isActive).isFalse()
        assertThat(removedMetadata.first()::displayName).isEqualTo("one")

        assertThat(SmartkronRegistry.createMetadata()).hasSize(1)
        assertThat(SmartkronRegistry.createMetadata().first()::isActive).isTrue()
        assertThat(SmartkronRegistry.createMetadata().first()::displayName).isEqualTo("two")

        registrationTwo.deactivate()
    }

    @Test
    fun `shutdown removes inactive and cancels active`() {
        val registrationOne = SmartkronRegistry.schedule(Counter { schedule(1.seconds) }, "one")
        val registrationTwo = SmartkronRegistry.schedule(Counter { schedule(2.seconds) }, "two")
        registrationOne.waitUntilActive()
        registrationTwo.waitUntilActive()

        registrationOne.deactivate()
        registrationOne.waitUntilInactive()
        registrationOne.ensureNoInvocations()

        SmartkronRegistry.shutdown()
        assertThat(SmartkronRegistry.createMetadata()).hasSize(0)
        assertThat(SmartkronRegistry.createMetadata(registrationOne)).isNull()
        assertThat(SmartkronRegistry.createMetadata(registrationTwo)).isNull()
    }

    @Test
    fun `activate and deactivate non existent registration does nothing`() {
        val registration = SmartkronRegistry.schedule(Counter { schedule(1.seconds) }, "one")
        registration.waitUntilIncreased()

        SmartkronRegistry.shutdown()
        noPendingSmartkrons()

        registration.activate()
        noPendingSmartkrons()
        registration.deactivate()
    }
}
