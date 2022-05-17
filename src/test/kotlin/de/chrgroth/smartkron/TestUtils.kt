package de.chrgroth.smartkron

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.awaitility.core.ConditionFactory
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.fail
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

internal fun SmartkronRegistration.ensureMetadata() =
    currentMetadata() ?: fail("Metadata missing for ${this.displayName}")

internal fun SmartkronRegistration.waitUntilInactive() {
    await().untilCallTo {
        currentMetadata()
    } matches {
        it != null && !it.isActive
    }
}

internal fun SmartkronRegistration.waitUntilActive() {
    await().untilCallTo {
        currentMetadata()
    } matches {
        it != null && it.isActive
    }
}

internal fun SmartkronRegistration.waitUntilIncreased() {
    val baseValue = currentInvocationCount()
    await().untilCallTo {
        currentMetadata()
    } matches {
        baseValue < (it?.currentInvocationCount() ?: 0)
    }
}

internal fun SmartkronRegistration.ensureNoInvocations(waitTime: Long = 5.seconds.inWholeMilliseconds) {
    val baseValue = currentInvocationCount()
    Thread.sleep(waitTime)
    assertThat(currentInvocationCount()).isEqualTo(baseValue)
}

internal fun SmartkronRegistration.currentInvocationCount() =
    currentMetadata()?.currentInvocationCount() ?: 0

private fun SmartkronMetadata.currentInvocationCount() = statistics.count

internal fun noPendingSmartkrons() {
    await().until {
        SmartkronRegistry.createMetadata().none { it.isActive }
    }
}

private fun await(): ConditionFactory {
    return org.awaitility.kotlin.await.pollInterval(Duration.ofMillis(25)).atMost(Duration.ofSeconds(20))
}
