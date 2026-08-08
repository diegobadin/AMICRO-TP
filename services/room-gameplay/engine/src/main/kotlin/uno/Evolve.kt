package uno

import java.time.Instant

/**
 * The other half of the pair (plan D1): `evolve` folds one event into the state and nothing else —
 * no validation, no clock, no randomness beyond the seed the event already carries. Replay is
 * therefore the definition of correct: if the aggregate rebuilt from the log ever disagrees with
 * the state that was served, the log wins.
 */
fun evolve(state: RoomState, event: Event): RoomState {
    val next = apply(state, event)
    return next.copy(sequenceNumber = state.sequenceNumber + 1)
}

fun replay(events: List<Event>, roomId: String = ""): RoomState =
    events.fold(RoomState(roomId = roomId), ::evolve)

private fun apply(state: RoomState, event: Event): RoomState = when (event) {
    is RoomCreated -> state.copy(
        roomType = event.roomType,
        creatorId = event.creatorId,
        maxPlayers = event.maxPlayers,
        status = RoomStatus.WAITING,
        createdAt = event.at,
    )

    is PlayerJoined -> state.copy(
        players = state.players + RoomPlayer(event.playerId, joinedAt = event.at),
    )

    is PlayerLeft -> state.copy(players = state.players.filterNot { it.playerId == event.playerId })

    is GameStarted -> {
        val dealt = deal(event.playerOrder, event.seed, STARTING_HAND_SIZE)
        state.copy(
            status = RoomStatus.IN_PROGRESS,
            game = Game(
                gameNumber = event.gameNumber,
                status = GameStatus.IN_PROGRESS,
                deck = dealt.deck,
                discard = dealt.discard,
                hands = dealt.hands,
                turnOrder = TurnOrder(event.playerOrder),
                activeColor = event.initialColor,
                challengeWindow = null,
                finishingOrder = emptyList(),
                turnTimerDeadline = event.at.plusSeconds(event.turnTimeoutSeconds),
                turnTimeoutSeconds = event.turnTimeoutSeconds,
                drewThisTurn = false,
                completedAt = null,
            ),
        )
    }

    // The play itself hands the turn over; a Skip or a Draw Two simply names a different player in
    // `nextPlayerId`, and the TurnSkipped that follows sets the same seat again rather than moving
    // relative to it — so the two can never disagree.
    is CardPlayed -> state.mapGame { game ->
        val hand = game.hands.getValue(event.playerId).remove(event.card)
            ?: error("replay diverged: ${event.card} was not in ${event.playerId}'s hand")
        game.copy(
            hands = game.hands + (event.playerId to hand),
            discard = game.discard + event.card,
            activeColor = event.chosenColor ?: event.card.color,
        ).moveTurnTo(event.nextPlayerId, event.at)
    }

    is CardDrawn -> state.mapGame { game ->
        val (drawn, rest) = game.deck.draw(1)
        game.copy(
            deck = rest,
            hands = game.hands + (event.playerId to game.hands.getValue(event.playerId).add(drawn)),
            drewThisTurn = true,
        )
    }

    is ForcedDraw -> state.mapGame { game ->
        val (drawn, rest) = game.deck.draw(event.cardCount)
        game.copy(
            deck = rest,
            hands = game.hands + (event.targetPlayerId to game.hands.getValue(event.targetPlayerId).add(drawn)),
        )
    }

    is DeckRecycled -> state.mapGame { game ->
        // Everything but the top card goes back under, reshuffled with the seed this event records.
        val recycled = game.discard.dropLast(1)
        game.copy(
            deck = Deck(game.deck.cards + recycled.shuffledWith(event.seed)),
            discard = listOf(game.top),
        )
    }

    is DirectionReversed -> state.mapGame { it.copy(turnOrder = it.turnOrder.reversed()) }

    is TurnPassed -> state.mapGame { it.moveTurnTo(event.nextPlayerId, event.at) }

    is TurnSkipped -> state.mapGame { it.moveTurnTo(event.nextPlayerId, event.at) }

    is TurnTimedOut -> state // the draw and the pass that follow carry the state change

    is UnoCallMade -> state.mapGame { game ->
        game.copy(hands = game.hands + (event.playerId to game.hands.getValue(event.playerId).copy(hasCalledUno = true)))
    }

    is ChallengeWindowOpened -> state.mapGame {
        it.copy(
            challengeWindow = ChallengeWindow(
                targetPlayerId = event.targetPlayerId,
                targetCalledUno = event.targetCalledUno,
                openedAt = event.at,
                expiresAt = event.expiresAt,
            ),
        )
    }

    is ChallengeWindowClosed -> state.mapGame { it.copy(challengeWindow = null) }

    is UnoChallengeIssued -> state

    is UnoChallengeResolved -> state.mapGame { game ->
        if (event.penaltyPlayerId == null) game else {
            val (drawn, rest) = game.deck.draw(event.penaltyCardCount)
            game.copy(
                deck = rest,
                hands = game.hands + (event.penaltyPlayerId to game.hands.getValue(event.penaltyPlayerId).add(drawn)),
            )
        }
    }

    is PlayerDisconnected -> state.mapPlayer(event.playerId) {
        it.copy(connection = ConnectionStatus.Disconnected(event.at, event.reconnectionDeadline))
    }

    is PlayerReconnected -> state.mapPlayer(event.playerId) {
        it.copy(connection = ConnectionStatus.Connected)
    }

    // Losing the seat that held the turn passes it on, and the player who inherits it gets a full
    // timer — otherwise they would take over an already-expired deadline and time out immediately.
    is PlayerForfeited -> state
        .mapPlayer(event.playerId) { it.copy(connection = ConnectionStatus.Forfeited) }
        .mapGame { game ->
            val heldTheTurn = game.turnOrder.activePlayers.getOrNull(game.turnOrder.currentIndex) == event.playerId
            val order = game.turnOrder.remove(event.playerId)
            if (heldTheTurn && order.activePlayers.isNotEmpty()) {
                game.copy(turnOrder = order).moveTurnTo(order.current, event.at)
            } else {
                game.copy(turnOrder = order)
            }
        }

    is GameCompleted -> state.copy(
        gamesPlayed = state.gamesPlayed + 1,
        game = state.game?.copy(
            status = GameStatus.COMPLETED,
            finishingOrder = event.finishingOrder,
            completedAt = event.completedAt,
            turnTimerDeadline = null,
            challengeWindow = null,
        ),
    )

    is RoomCompleted -> state.copy(status = RoomStatus.COMPLETED)
}

private fun RoomState.mapGame(f: (Game) -> Game): RoomState =
    if (game == null) this else copy(game = f(game))

private fun RoomState.mapPlayer(playerId: String, f: (RoomPlayer) -> RoomPlayer): RoomState =
    copy(players = players.map { if (it.playerId == playerId) f(it) else it })

/**
 * The turn moves to a named player rather than by a step count, so the log states who acts next
 * instead of leaving replay to re-derive it from direction and skips. A rebuilt aggregate cannot
 * drift from the served one over an off-by-one.
 */
private fun Game.moveTurnTo(playerId: String, at: Instant): Game {
    val index = turnOrder.activePlayers.indexOf(playerId)
    return copy(
        turnOrder = if (index >= 0) turnOrder.copy(currentIndex = index) else turnOrder,
        turnTimerDeadline = at.plusSeconds(turnTimeoutSeconds),
        drewThisTurn = false,
    )
}
