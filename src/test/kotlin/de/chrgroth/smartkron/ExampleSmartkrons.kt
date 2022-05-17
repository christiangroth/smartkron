package de.chrgroth.smartkron

import java.util.UUID
import kotlin.coroutines.coroutineContext

open class NoHistoryCounter(
    count: Int = 0,
    block: Counter.() -> SmartkronExecutionResult,
) : Counter(count, block) {
    override fun hasExecutionHistory() = false
}

open class FailsafeCounter(
    count: Int = 0,
    block: Counter.() -> SmartkronExecutionResult,
) : Counter(count, block) {
    override fun abortsOnException() = false
}

open class Counter(
    var count: Int = 0,
    private val block: Counter.() -> SmartkronExecutionResult,
) : Smartkron {
    override suspend fun run(id: UUID, displayName: String): SmartkronExecutionResult {
        count++
        println("$coroutineContext displayName count=$count")
        return block.invoke(this)
    }
}
