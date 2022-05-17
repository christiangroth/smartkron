package de.chrgroth.smartkron

internal class FifoList<T>(private val capacity: Int) {
    private val elements: MutableList<T> = mutableListOf()

    fun add(element: T) {
        if (elements.size == capacity) {
            elements.removeLast()
        }
        elements.add(0, element)
    }

    fun toList() = elements.toList()
}
