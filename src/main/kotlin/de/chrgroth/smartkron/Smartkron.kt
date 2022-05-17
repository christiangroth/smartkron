package de.chrgroth.smartkron

import de.chrgroth.smartkron.SmartkronExecutionResult.Failure
import de.chrgroth.smartkron.SmartkronExecutionResult.Success
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Central interface for all Smartkron implementations.
 *
 * You may override the default methods to control runtime behaviour of your instance.
 * Execution history:
 *  - hasExecutionHistory
 *  - maxExecutionHistorySize
 *
 * Failure handling:
 *  - abortsOnException
 *  - recoveryDelay
 */
interface Smartkron {
    fun hasExecutionHistory() = true
    fun maxExecutionHistorySize() = 100

    suspend fun run(id: UUID, displayName: String): SmartkronExecutionResult

    fun abortsOnException() = true
    fun recoveryDelay(): Duration = 1.seconds

    /**
     * Ends this Smartkron invocation with success status and optional variant.
     */
    fun end(variant: String? = null) = Success(null, variant)

    /**
     * Schedules this Smartkron with given delay and optional variant. The current invocation is reported as success.
     */
    fun schedule(plannedDelay: Duration, variant: String? = null) = Success(plannedDelay, variant)

    /**
     * Ends this Smartkron invocation with failure status, given error message and optional variant.
     */
    fun abort(error: String, variant: String? = null) = failed(null, error)

    /**
     * Reports this Smartkron invocation with failure status, given error message and optional variant.
     * Will reschedule with given delay, if any.
     */
    fun failed(plannedDelay: Duration?, error: String, variant: String? = null) = Failure(error, plannedDelay, variant)
}

sealed class SmartkronExecutionResult {
    abstract val plannedDelay: Duration?
    abstract val variant: String?

    fun failed() = this is Failure

    data class Success(
        override val plannedDelay: Duration?,
        override val variant: String?,
    ) : SmartkronExecutionResult()

    data class Failure(
        val error: String,
        override val plannedDelay: Duration?,
        override val variant: String?,
    ) : SmartkronExecutionResult()
}
