package ua.olex.music.model

/** Shuffle traversal updates preserve every existing occurrence and insert Play next contiguously. */
object QueueOrder {
    fun insertNext(order: List<Int>, current: Int, insertionIndex: Int, count: Int): List<Int> {
        require(count >= 0 && insertionIndex in 0..order.size)
        require(order.toSet() == order.indices.toSet())
        if (count == 0) return order
        val shifted = order.map { if (it >= insertionIndex) it + count else it }.toMutableList()
        val shiftedCurrent = if (current >= insertionIndex) current + count else current
        val after = shifted.indexOf(shiftedCurrent).let { if (it < 0) shifted.size else it + 1 }
        shifted.addAll(after, (insertionIndex until insertionIndex + count).toList())
        return shifted
    }
}
