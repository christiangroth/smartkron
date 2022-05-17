package de.chrgroth.smartkron

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class FifoListTests {

    @Test
    fun checkElementsOrder() {
        val fifo = FifoList<Int>(3)
        fifo.add(1)
        fifo.add(2)
        fifo.add(3)

        val list = fifo.toList()
        assertThat(list).hasSize(3)
        assertThat(list).isEqualTo(listOf(3, 2, 1))
    }

    @Test
    fun checkOverflow() {
        val fifo = FifoList<Int>(3)
        fifo.add(1)
        fifo.add(2)
        fifo.add(3)
        fifo.add(4)
        fifo.add(5)

        val list = fifo.toList()
        assertThat(list).hasSize(3)
        assertThat(list).isEqualTo(listOf(5, 4, 3))
    }
}
