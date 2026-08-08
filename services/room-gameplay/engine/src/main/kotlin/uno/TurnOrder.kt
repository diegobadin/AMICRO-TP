package uno

enum class Direction(val step: Int) {
    CLOCKWISE(1), COUNTER_CLOCKWISE(-1);

    fun toggled(): Direction = if (this == CLOCKWISE) COUNTER_CLOCKWISE else CLOCKWISE
}

/**
 * The circular ring of players still in the game (§3.2.8). Forfeits remove a seat without
 * disturbing the relative order of the rest, and the index is kept pointing at whoever should
 * actually be acting — an index that merely stays in bounds would silently hand the turn to the
 * wrong player.
 */
data class TurnOrder(
    val activePlayers: List<String>,
    val currentIndex: Int = 0,
    val direction: Direction = Direction.CLOCKWISE,
) {
    val current: String get() = activePlayers[currentIndex]
    val size: Int get() = activePlayers.size

    fun advance(steps: Int = 1): TurnOrder {
        if (activePlayers.isEmpty()) return this
        val n = activePlayers.size
        val moved = ((currentIndex + direction.step * steps) % n + n) % n
        return copy(currentIndex = moved)
    }

    /** Who acts after `steps` moves, without committing to it. */
    fun peek(steps: Int = 1): String = advance(steps).current

    fun reversed(): TurnOrder = copy(direction = direction.toggled())

    fun remove(playerId: String): TurnOrder {
        val index = activePlayers.indexOf(playerId)
        if (index < 0) return this
        if (activePlayers.size == 1) return copy(activePlayers = emptyList(), currentIndex = 0)
        val stillToAct = if (index == currentIndex) peek() else current
        val remaining = activePlayers.filterNot { it == playerId }
        return copy(activePlayers = remaining, currentIndex = remaining.indexOf(stillToAct))
    }
}
