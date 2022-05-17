package de.chrgroth.smartkron

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.Duration.Companion.milliseconds

data class SmartkronRegistration(
    val id: UUID,
    val displayName: String,
    val instance: Smartkron,
) {
    fun activate() = SmartkronRegistry.activate(this)
    fun deactivate() = SmartkronRegistry.deactivate(this)
    fun currentMetadata() = SmartkronRegistry.createMetadata(this)
}

object SmartkronRegistry {
    private val LOG = LoggerFactory.getLogger(SmartkronRegistry::class.java)

    private val wrappers: MutableSet<SmartkronCoroutineWrapper> = mutableSetOf()
    private fun MutableSet<SmartkronCoroutineWrapper>.byRegistration(registration: SmartkronRegistration) =
        firstOrNull { it.belongsTo(registration) }

    @OptIn(DelicateCoroutinesApi::class)
    fun schedule(smartkron: Smartkron, displayNameOverride: String? = null) =
        smartkron.createRegistration(displayNameOverride).apply {
            LOG.info("scheduling smartkron: $displayName")

            val wrapper = SmartkronCoroutineWrapper(GlobalScope, this)
            synchronized(wrappers) {
                wrappers.add(wrapper)
            }

            wrapper.activate()
        }

    private fun Smartkron.createRegistration(displayName: String?) =
        SmartkronRegistration(UUID.randomUUID(), displayName ?: javaClass.name, this)

    fun activate(registration: SmartkronRegistration) {
        wrappers.byRegistration(registration)?.activate()
    }

    fun deactivate(registration: SmartkronRegistration) {
        wrappers.byRegistration(registration)?.deactivate()
    }

    fun prune(): Set<SmartkronMetadata> =
        wrappers.filter { !it.active }.let { inactiveWrappers ->
            val metadata = inactiveWrappers.map { it.createMetadata() }

            LOG.info("removing inactive smartkrons: ${metadata.map { it.displayName }}")
            synchronized(this.wrappers) {
                this.wrappers.removeAll(inactiveWrappers.toSet())
            }

            metadata.toSet()
        }

    fun createMetadata(registration: SmartkronRegistration) =
        wrappers.byRegistration(registration)?.createMetadata()

    fun createMetadata(): Set<SmartkronMetadata> =
        wrappers.map { it.createMetadata() }.toSet()

    fun shutdown() {
        wrappers.forEach { it.deactivate() }
        prune()
    }
}

private class SmartkronCoroutineWrapper(
    private val scope: CoroutineScope,
    registration: SmartkronRegistration,
) {

    companion object {
        private val LOG = LoggerFactory.getLogger(SmartkronCoroutineWrapper::class.java)
    }

    private val id = registration.id
    private val displayName = registration.displayName
    private val instance = registration.instance

    fun belongsTo(registration: SmartkronRegistration) = id == registration.id

    private var job: Job? = null
    private var plannedNext: Instant? = null

    private val history = FifoList<SmartkronExecution>(instance.maxExecutionHistorySize())
    private var statistics = SmartkronStatistics()
    private val variantStatistics: MutableMap<String, SmartkronStatistics> = mutableMapOf()

    val active: Boolean
        get() = job?.isActive ?: false

    fun activate() {
        if (active) {
            return
        }

        LOG.info("activating smartkron $displayName")
        job = scope.launch {
            do {
                val started = Clock.System.now()
                try {
                    instance.run(id, displayName) to null
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    null to e
                }.let {
                    val stopped = Clock.System.now()
                    val duration = stopped - started
                    val execution = SmartkronExecution(started, it.first, duration)
                    val exception = it.second

                    if (instance.hasExecutionHistory()) {
                        history.add(execution)
                    }

                    statistics = statistics.update(execution)
                    if (execution.result != null) {
                        val variant = execution.result.variant
                        if (variant != null) {
                            val variantStatistics = variantStatistics.getOrPut(variant) { SmartkronStatistics() }
                            this@SmartkronCoroutineWrapper.variantStatistics[variant] = variantStatistics.update(execution)
                        }
                    }

                    val plannedDelay = extractPlannedDelay(execution.result, exception)
                    plannedNext = if (plannedDelay != null) Clock.System.now().plus(plannedDelay) else null
                    LOG.info("smartkron $displayName is scheduled next in $plannedDelay -> $plannedNext")

                    if(plannedDelay != null) {
                        delay(plannedDelay)
                    }
                }
            } while (plannedNext != null)
        }
    }

    private fun extractPlannedDelay(result: SmartkronExecutionResult?, exception: Exception?) =
        if (exception != null) {
            val logPrefix = "smartkron $displayName crashed."
            if (instance.abortsOnException()) {
                LOG.error("$logPrefix no further executions will be planned for $displayName.", exception)
                null
            } else {
                LOG.warn("$logPrefix recovering $displayName at ${instance.recoveryDelay()}", exception)
                instance.recoveryDelay()
            }
        } else {
            if (result is SmartkronExecutionResult.Failure) {
                LOG.warn("smartkron $displayName finished with error: ${result.error}")
            }
            if (result?.plannedDelay == null) {
                LOG.info("smartkron $displayName did not return next execution time, disabling.")
            }

            result?.plannedDelay
        }

    fun deactivate() {
        runBlocking {
            val currentJob = job
            if (currentJob != null && active) {
                LOG.info("deactivating smartkron $displayName...")
                currentJob.cancelAndJoin()
                LOG.info("smartkron $displayName deactivated.")
            }
        }
        job = null
        plannedNext = null
    }

    fun createMetadata() =
        SmartkronMetadata(
            displayName,
            active,
            plannedNext,
            statistics,
            variantStatistics.toMap(),
            history.toList()
        )
}

data class SmartkronMetadata(
    val displayName: String,
    val isActive: Boolean,
    val plannedNext: Instant?,
    val statistics: SmartkronStatistics,
    val variantStatistics: Map<String, SmartkronStatistics>,
    val history: List<SmartkronExecution>,
)

data class SmartkronExecution(
    val started: Instant,
    val result: SmartkronExecutionResult?,
    val duration: Duration,
)

data class SmartkronStatistics(
    val count: Long = 0,
    val errors: Long = 0,
    val avgDuration: Duration = 0.milliseconds,
) {
    fun update(execution: SmartkronExecution) =
        SmartkronStatistics(
            count + 1,
            errors + execution.resultErrorCount(),
            avgDuration.newAverage(execution, count)
        )

    private fun SmartkronExecution.resultErrorCount() =
        if (result == null || result.failed()) 1 else 0

    private fun Duration.newAverage(execution: SmartkronExecution, oldCount: Long): Duration =
        ((this.toDouble(DurationUnit.MILLISECONDS) * oldCount + execution.duration.toDouble(DurationUnit.MILLISECONDS)) / (oldCount + 1)).milliseconds
}
