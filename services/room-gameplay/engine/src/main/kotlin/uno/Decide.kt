package uno

import java.time.Instant

data class EngineConfig(
    val minPlayers: Int = MIN_PLAYERS_TO_PLAY,
    val turnTimeoutSeconds: Long = 30,
    /** Turns a player may let lapse in a row before the seat is given up (P5 E2). */
    val idleTimeoutsBeforeForfeit: Int = 3,
    val waitingRoomExpirySeconds: Long = 900,
)

/**
 * The clock and the shuffle seed are parameters, not ambient state (plan D2/D3): deadline behaviour
 * is testable without sleeping, and every shuffle a command performs is recorded in the event that
 * caused it. Nothing in this file reads `Instant.now()` or constructs a `Random` — the edge does
 * both, once, and hands the values in.
 */
fun decide(
    state: RoomState,
    command: Command,
    now: Instant,
    seed: Long,
    config: EngineConfig = EngineConfig(),
): Decision {
    val log = Log(state)
    // E2: expire what is overdue, then process. Deadlines that came and went while nobody was
    // sending commands take effect here, before the incoming command is judged — otherwise a
    // player whose turn timed out minutes ago would still be holding the turn.
    log.expireOverdue(now, config)

    return when (command) {
        is CreateRoom -> log.createRoom(command, now)
        is JoinRoom -> log.joinRoom(command, now, config, seed)
        is LeaveRoom -> log.leaveRoom(command, now, config)
        is StartGame -> log.startGame(now, config, seed)
        is PlayCard -> log.playCard(command, now, seed, config)
        is DrawCard -> log.drawCard(command, now, seed)
        is PassTurn -> log.passTurn(command, now, config)
        is CallUno -> log.callUno(command, now)
        is ChallengeUno -> log.challengeUno(command, now, seed)
        is ReconnectPlayer -> log.reconnect(command, now)
        is DisconnectPlayer -> log.disconnect(command, now, config)
        is ForfeitPlayer -> log.forfeit(command, now, config)
        // Whatever `expireOverdue` just produced is the entire answer.
        is Tick -> if (state.exists) log.accept() else log.reject(Rejection.ROOM_NOT_FOUND)
    }
}

/** Standalone so the HTTP layer can flush deadlines on a read as well as on a command. */
fun expire(state: RoomState, now: Instant, config: EngineConfig = EngineConfig()): List<Event> =
    Log(state).also { it.expireOverdue(now, config) }.events

/**
 * The earliest moment `expire` could have anything to say about this room, or null if it is waiting
 * on nothing. The `rooms` projection caches it so the timer worker can find due rooms with an index
 * lookup instead of replaying every aggregate (P5 E1) — the deadlines themselves stay here, where
 * they are decided.
 */
fun nextDeadline(state: RoomState, config: EngineConfig = EngineConfig()): Instant? = when {
    !state.exists || state.status == RoomStatus.COMPLETED -> null
    // The room's own clock: the last arrival starts the window, so a late joiner gets a full one.
    state.status == RoomStatus.WAITING ->
        (state.players.maxOfOrNull { it.joinedAt } ?: state.createdAt)?.plusSeconds(config.waitingRoomExpirySeconds)
    // Mirrors `expireOverdue` below, deadline for deadline: the turn timer only while a game is
    // running, the challenge window and the reconnection windows whenever they are open. A property
    // test holds the two together, because a deadline the engine acts on but this does not advertise
    // is one the worker would never come for.
    else -> {
        val disconnections = state.players.mapNotNull { (it.connection as? ConnectionStatus.Disconnected)?.deadline }
        val running = state.game?.takeIf { it.status == GameStatus.IN_PROGRESS }
        (disconnections + listOfNotNull(running?.turnTimerDeadline, state.game?.challengeWindow?.expiresAt)).minOrNull()
    }
}

/**
 * Accumulates events while keeping a working state in step with them, so a command that emits five
 * events decides the fifth against the state the first four produced. It also means `decide` and
 * `evolve` cannot drift apart: there is only one implementation of what an event does.
 */
private class Log(initial: RoomState) {
    var state = initial
        private set
    val events = mutableListOf<Event>()

    fun emit(event: Event) {
        events += event
        state = evolve(state, event)
    }

    fun accept(): Decision = Decision.Accepted(events.toList())
    fun reject(reason: Rejection): Decision = Decision.Rejected(reason, events.toList())
}

// ---------------------------------------------------------------- room lifecycle

private fun Log.createRoom(command: CreateRoom, now: Instant): Decision {
    if (state.exists) return reject(Rejection.ROOM_ALREADY_EXISTS)
    emit(RoomCreated(command.roomType, command.creatorId, command.maxPlayers, now, command.tournament))
    emit(PlayerJoined(command.creatorId, playerCount = 1, at = now))
    return accept()
}

private fun Log.joinRoom(command: JoinRoom, now: Instant, config: EngineConfig, seed: Long): Decision {
    if (!state.exists) return reject(Rejection.ROOM_NOT_FOUND)
    if (state.player(command.playerId) != null) return reject(Rejection.ALREADY_JOINED)
    if (state.status == RoomStatus.COMPLETED) return reject(Rejection.ROOM_COMPLETED)
    if (state.status != RoomStatus.WAITING) return reject(Rejection.ROOM_ALREADY_STARTED)
    if (state.players.size >= state.maxPlayers) return reject(Rejection.ROOM_FULL)

    emit(PlayerJoined(command.playerId, playerCount = state.players.size + 1, at = now))
    // E3: the backend starts the game itself once the room is playable, so `play --casual` is a
    // single call for the player instead of a create-then-host-starts dance. A tournament room is
    // filled and started in one transaction by whoever provisions it, so starting at `minPlayers`
    // here would deal the cards before the last assigned player had a seat.
    if (state.roomType == RoomType.CASUAL && state.players.size >= config.minPlayers) {
        startGameNow(now, config, seed)
    }
    return accept()
}

private fun Log.leaveRoom(command: LeaveRoom, now: Instant, config: EngineConfig): Decision {
    val player = state.player(command.playerId) ?: return reject(Rejection.NOT_A_MEMBER)
    if (state.status == RoomStatus.IN_PROGRESS && player.isActive) {
        forfeitPlayer(command.playerId, "left", now, config)
        return accept()
    }
    emit(PlayerLeft(command.playerId, playerCount = state.players.size - 1, at = now))
    return accept()
}

private fun Log.startGame(now: Instant, config: EngineConfig, seed: Long): Decision {
    if (!state.exists) return reject(Rejection.ROOM_NOT_FOUND)
    if (state.status == RoomStatus.IN_PROGRESS) return reject(Rejection.GAME_IN_PROGRESS)
    if (state.status == RoomStatus.COMPLETED) return reject(Rejection.ROOM_COMPLETED)
    if (state.activePlayers.size < config.minPlayers) return reject(Rejection.NOT_ENOUGH_PLAYERS)
    startGameNow(now, config, seed)
    return accept()
}

private fun Log.startGameNow(now: Instant, config: EngineConfig, seed: Long) {
    val order = state.activePlayers.map { it.playerId }
    val dealt = deal(order, seed, STARTING_HAND_SIZE)
    val initial = dealt.discard.last()
    // Invariant 14 says the first player chooses the colour for an initial Wild. Choosing it from
    // the recorded seed instead keeps the game startable without an extra round-trip and an
    // otherwise-unreachable "no active colour" state; recorded as a delta in CHANGELOG-design.md.
    val initialColor =
        if (initial.face.isWild) Color.entries.filter { it.isPlayable }[((seed % 4 + 4) % 4).toInt()]
        else initial.color

    emit(
        GameStarted(
            gameNumber = state.gamesPlayed + 1,
            playerOrder = order,
            initialDiscardCard = initial,
            initialColor = initialColor,
            seed = seed,
            turnTimeoutSeconds = config.turnTimeoutSeconds,
            at = now,
        ),
    )
    applyFirstCardRule(initial, now, seed)
}

/** Invariant 14: the initial discard's effect lands before the first player acts. */
private fun Log.applyFirstCardRule(initial: Card, now: Instant, seed: Long) {
    val game = state.game ?: return
    val first = game.currentPlayer
    when (initial.face) {
        Face.SKIP -> skipTo(first, game.turnOrder.peek(1), "first_card_effect", now)
        Face.REVERSE -> {
            emit(DirectionReversed(game.turnOrder.direction.toggled(), now))
            val order = state.game!!.turnOrder
            // Two players: reversing points back at the dealer, so the first player is skipped.
            // With more, the direction is all that changes — the first player still opens.
            if (order.size == 2) skipTo(first, order.peek(1), "first_card_effect", now)
        }
        Face.DRAW_TWO -> {
            forceDraw(first, 2, "draw_two", now, seed)
            skipTo(first, state.game!!.turnOrder.peek(1), "first_card_effect", now)
        }
        else -> Unit // number cards and a Wild (whose colour the deal already fixed) start play
    }
}

// ---------------------------------------------------------------- play

private fun Log.playCard(command: PlayCard, now: Instant, seed: Long, config: EngineConfig): Decision {
    val game = state.game ?: return reject(Rejection.NO_GAME_IN_PROGRESS)
    if (game.status != GameStatus.IN_PROGRESS) return reject(Rejection.NO_GAME_IN_PROGRESS)
    if (game.currentPlayer != command.playerId) return reject(Rejection.NOT_YOUR_TURN)

    val hand = game.hands[command.playerId] ?: return reject(Rejection.NOT_A_MEMBER)
    if (!hand.cards.contains(command.card)) return reject(Rejection.CARD_NOT_IN_HAND)
    if (!command.card.playableOn(game.top, game.activeColor)) return reject(Rejection.ILLEGAL_PLAY)
    // Invariant 12: a wild without a declared colour is rejected, never silently defaulted.
    if (command.card.face.isWild && command.chosenColor?.isPlayable != true) {
        return reject(Rejection.WILD_NEEDS_COLOR)
    }
    if (!command.card.face.isWild && command.chosenColor != null) return reject(Rejection.COLOR_ON_NON_WILD)

    closeChallengeWindow("next_turn", now)

    val remaining = hand.size - 1
    val effect = resolveEffect(command.card, now)
    emit(
        CardPlayed(
            playerId = command.playerId,
            card = command.card,
            newDiscardTop = command.card,
            playerCardCount = remaining,
            chosenColor = command.chosenColor,
            nextPlayerId = effect.nextPlayer,
            at = now,
        ),
    )
    effect.emitAfterPlay(this, now, seed)

    if (command.callingUno && remaining <= 1) emit(UnoCallMade(command.playerId, now))

    if (remaining == 0) {
        completeGame(winner = command.playerId, abandoned = false, now = now, config = config)
        return accept()
    }
    // Invariant 4: one card left opens the window whether or not they called.
    if (remaining == 1) {
        emit(
            ChallengeWindowOpened(
                targetPlayerId = command.playerId,
                targetCalledUno = state.game!!.hands.getValue(command.playerId).hasCalledUno,
                expiresAt = now.plusSeconds(CHALLENGE_WINDOW_SECONDS),
                at = now,
            ),
        )
    }
    return accept()
}

/**
 * What a card does to the ring, worked out before anything is emitted because `CardPlayed` has to
 * name the player who acts next (§4.1) — and after a Skip or a Draw Two that is not the neighbour.
 */
private class Effect(
    val nextPlayer: String,
    val emitAfterPlay: Log.(Instant, Long) -> Unit,
)

private fun Log.resolveEffect(card: Card, now: Instant): Effect {
    val game = state.game!!
    val order = game.turnOrder
    val actor = order.current
    return when (card.face) {
        Face.SKIP -> {
            val skipped = order.peek(1)
            val next = nextConnected(order, 2)
            Effect(next) { at, _ -> emit(TurnSkipped(skipped, next, "skip_card", at)) }
        }
        Face.REVERSE -> {
            val reversedOrder = order.reversed()
            if (order.size == 2) {
                // §3.2.8: reversing in a two-player game points back at the actor, so it is a skip.
                val skipped = reversedOrder.peek(1)
                Effect(actor) { at, _ ->
                    emit(DirectionReversed(reversedOrder.direction, at))
                    emit(TurnSkipped(skipped, actor, "reverse_2p", at))
                }
            } else {
                val next = nextConnected(reversedOrder, 1)
                Effect(next) { at, _ -> emit(DirectionReversed(reversedOrder.direction, at)) }
            }
        }
        Face.DRAW_TWO, Face.WILD_DRAW_FOUR -> {
            val count = if (card.face == Face.DRAW_TWO) 2 else 4
            val reason = if (card.face == Face.DRAW_TWO) "draw_two" else "wild_draw_four"
            val target = order.peek(1)
            val next = nextConnected(order, 2)
            Effect(next) { at, s ->
                forceDraw(target, count, reason, at, s)
                emit(TurnSkipped(target, next, reason, at))
            }
        }
        else -> {
            val next = nextConnected(order, 1)
            Effect(next) { _, _ -> }
        }
    }
}

private fun Log.drawCard(command: DrawCard, now: Instant, seed: Long): Decision {
    val game = state.game ?: return reject(Rejection.NO_GAME_IN_PROGRESS)
    if (game.status != GameStatus.IN_PROGRESS) return reject(Rejection.NO_GAME_IN_PROGRESS)
    if (game.currentPlayer != command.playerId) return reject(Rejection.NOT_YOUR_TURN)
    if (game.drewThisTurn) return reject(Rejection.ALREADY_DREW_THIS_TURN)

    closeChallengeWindow("next_turn", now)
    recycleIfShort(1, now, seed)
    val hand = state.game!!.hands.getValue(command.playerId)
    emit(CardDrawn(command.playerId, newCardCount = hand.size + 1, at = now))
    return accept()
}

private fun Log.passTurn(command: PassTurn, now: Instant, config: EngineConfig): Decision {
    val game = state.game ?: return reject(Rejection.NO_GAME_IN_PROGRESS)
    if (game.status != GameStatus.IN_PROGRESS) return reject(Rejection.NO_GAME_IN_PROGRESS)
    if (game.currentPlayer != command.playerId) return reject(Rejection.NOT_YOUR_TURN)
    if (!game.drewThisTurn) return reject(Rejection.MUST_DRAW_BEFORE_PASSING)

    closeChallengeWindow("next_turn", now)
    val next = nextConnected(state.game!!.turnOrder, 1)
    emit(TurnPassed(command.playerId, next, now))
    return accept()
}

private fun Log.callUno(command: CallUno, now: Instant): Decision {
    val game = state.game ?: return reject(Rejection.NO_GAME_IN_PROGRESS)
    val hand = game.hands[command.playerId] ?: return reject(Rejection.NOT_A_MEMBER)
    // §4.1: legal when holding the second-to-last card or having just played down to one.
    if (hand.size !in 1..2) return reject(Rejection.UNO_CALL_NOT_AVAILABLE)
    if (hand.hasCalledUno) return accept() // idempotent
    emit(UnoCallMade(command.playerId, now))
    return accept()
}

private fun Log.challengeUno(command: ChallengeUno, now: Instant, seed: Long): Decision {
    val game = state.game ?: return reject(Rejection.NO_GAME_IN_PROGRESS)
    val window = game.challengeWindow ?: return reject(Rejection.NO_OPEN_CHALLENGE)
    if (window.targetPlayerId != command.targetPlayerId) return reject(Rejection.NO_OPEN_CHALLENGE)
    if (state.player(command.challengerId) == null) return reject(Rejection.NOT_A_MEMBER)

    val target = game.hands[command.targetPlayerId] ?: return reject(Rejection.CHALLENGE_NOT_VALID)
    // A challenge is only valid against someone sitting at one card who did not call. Calling Uno
    // after the window opened still saves them, which is why this reads the hand and not the
    // snapshot taken when the window was created.
    if (target.size != 1 || target.hasCalledUno) return reject(Rejection.CHALLENGE_NOT_VALID)

    emit(UnoChallengeIssued(command.challengerId, command.targetPlayerId, now))
    recycleIfShort(UNO_PENALTY_CARDS, now, seed)
    emit(
        UnoChallengeResolved(
            challengerId = command.challengerId,
            targetPlayerId = command.targetPlayerId,
            challengeSucceeded = true,
            penaltyPlayerId = command.targetPlayerId,
            penaltyCardCount = UNO_PENALTY_CARDS,
            at = now,
        ),
    )
    emit(ChallengeWindowClosed(command.targetPlayerId, "resolved", now))
    return accept()
}

// ---------------------------------------------------------------- presence

private fun Log.disconnect(command: DisconnectPlayer, now: Instant, config: EngineConfig): Decision {
    val player = state.player(command.playerId) ?: return reject(Rejection.NOT_A_MEMBER)
    // D8: a redelivered SessionInvalidated must not re-open a window that already expired.
    if (player.connection !is ConnectionStatus.Connected) return accept()
    emit(
        PlayerDisconnected(
            playerId = command.playerId,
            reconnectionDeadline = now.plusSeconds(RECONNECTION_WINDOW_SECONDS),
            at = now,
        ),
    )
    skipDisconnectedCurrentPlayer(now, config)
    return accept()
}

private fun Log.reconnect(command: ReconnectPlayer, now: Instant): Decision {
    val player = state.player(command.playerId) ?: return reject(Rejection.NOT_A_MEMBER)
    when (val connection = player.connection) {
        is ConnectionStatus.Connected -> return accept() // idempotent
        is ConnectionStatus.Forfeited -> return reject(Rejection.RECONNECTION_EXPIRED)
        is ConnectionStatus.Disconnected ->
            if (now.isAfter(connection.deadline)) return reject(Rejection.RECONNECTION_EXPIRED)
    }
    emit(PlayerReconnected(command.playerId, now))
    return accept()
}

private fun Log.forfeit(command: ForfeitPlayer, now: Instant, config: EngineConfig): Decision {
    val player = state.player(command.playerId) ?: return reject(Rejection.NOT_A_MEMBER)
    if (!player.isActive) return accept() // idempotent by player status (§4.1)
    forfeitPlayer(command.playerId, command.reason, now, config)
    return accept()
}

private fun Log.forfeitPlayer(playerId: String, reason: String, now: Instant, config: EngineConfig) {
    emit(PlayerForfeited(playerId, reason, isTournament = state.roomType == RoomType.TOURNAMENT, at = now))
    endGameIfTooFewPlayers(now, config)
}

// ---------------------------------------------------------------- deadlines (E2)

private fun Log.expireOverdue(now: Instant, config: EngineConfig) {
    if (state.status == RoomStatus.WAITING) return expireWaitingRoom(now, config)
    if (state.status != RoomStatus.IN_PROGRESS) return

    state.game?.challengeWindow?.let { window ->
        if (!now.isBefore(window.expiresAt)) emit(ChallengeWindowClosed(window.targetPlayerId, "timeout", now))
    }

    // Forfeits first: they can remove the very player whose turn timer is about to fire, and
    // timing out a seat that no longer exists would be a phantom event in the log.
    state.players
        .mapNotNull { p -> (p.connection as? ConnectionStatus.Disconnected)?.let { p.playerId to it } }
        .filter { (_, d) -> now.isAfter(d.deadline) }
        .forEach { (playerId, _) ->
            if (state.status == RoomStatus.IN_PROGRESS) {
                forfeitPlayer(playerId, "reconnection_timeout", now, config)
            }
        }

    val game = state.game ?: return
    if (game.status != GameStatus.IN_PROGRESS) return
    val deadline = game.turnTimerDeadline ?: return
    if (!now.isAfter(deadline)) return

    // Invariant 13: draw for them if they have not drawn, then pass.
    val player = game.currentPlayer
    val autoAction = if (game.drewThisTurn) "pass" else "draw_and_pass"
    emit(TurnTimedOut(player, autoAction, now))
    if (!game.drewThisTurn) {
        recycleIfShort(1, now, deadline.epochSecond)
        emit(CardDrawn(player, newCardCount = state.game!!.hands.getValue(player).size + 1, at = now))
    }
    emit(TurnPassed(player, nextConnected(state.game!!.turnOrder, 1), now))

    // Until P5 a room everyone had walked away from was merely stuck; with a worker driving the
    // clock it would produce a timeout every turn, forever. Giving the seat up ends the game through
    // invariant 7 instead — no new event, and the last player present wins as they would anyway.
    if (state.game!!.timeouts(player) >= config.idleTimeoutsBeforeForfeit) {
        forfeitPlayer(player, "idle", now, config)
    }
}

/**
 * A room that never filled is not a game that stalled: nobody is owed a turn, so it just closes.
 * The window runs from the last arrival rather than from creation, so someone who joins late still
 * gets a full one instead of inheriting the tail of somebody else's.
 */
private fun Log.expireWaitingRoom(now: Instant, config: EngineConfig) {
    // Asks the same function the projection caches, rather than restating the rule. The two were
    // written out separately at first, which is a divergence waiting to happen: the cached deadline
    // is only useful because it is the *same* number this line compares against.
    val deadline = nextDeadline(state, config) ?: return
    if (now.isBefore(deadline)) return
    emit(RoomExpired("waiting_timeout", now))
}

// ---------------------------------------------------------------- shared mechanics

/** Invariant 8: a disconnected seat is passed over rather than left to stall the game. */
private fun Log.nextConnected(order: TurnOrder, steps: Int): String {
    val connected = order.activePlayers.count { state.player(it)?.connection is ConnectionStatus.Connected }
    if (connected == 0) return order.peek(steps)
    var extra = 0
    while (extra < order.size) {
        val candidate = order.peek(steps + extra)
        if (state.player(candidate)?.connection is ConnectionStatus.Connected) return candidate
        extra++
    }
    return order.peek(steps)
}

private fun Log.skipTo(skipped: String, next: String, reason: String, now: Instant) {
    emit(TurnSkipped(skipped, next, reason, now))
}

/** Emitted when the player holding the turn drops out, so the game does not wait on them. */
private fun Log.skipDisconnectedCurrentPlayer(now: Instant, config: EngineConfig) {
    val game = state.game ?: return
    if (game.status != GameStatus.IN_PROGRESS) return
    if (state.activePlayers.size < MIN_PLAYERS_TO_PLAY) return endGameIfTooFewPlayers(now, config)
    val current = game.currentPlayer
    if (state.player(current)?.connection is ConnectionStatus.Connected) return
    val next = nextConnected(game.turnOrder, 1)
    if (next != current) emit(TurnSkipped(current, next, "disconnection", now))
}

private fun Log.recycleIfShort(needed: Int, now: Instant, seed: Long) {
    val game = state.game ?: return
    if (game.deck.size >= needed) return
    val recyclable = game.discard.size - 1
    if (recyclable <= 0) return
    emit(DeckRecycled(newDeckSize = game.deck.size + recyclable, seed = seed, at = now))
}

private fun Log.forceDraw(target: String, count: Int, reason: String, now: Instant, seed: Long) {
    recycleIfShort(count, now, seed)
    val game = state.game!!
    val drawn = minOf(count, game.deck.size)
    if (drawn == 0) return
    emit(
        ForcedDraw(
            targetPlayerId = target,
            cardCount = drawn,
            newHandSize = game.hands.getValue(target).size + drawn,
            reason = reason,
            at = now,
        ),
    )
}

private fun Log.closeChallengeWindow(reason: String, now: Instant) {
    val window = state.game?.challengeWindow ?: return
    emit(ChallengeWindowClosed(window.targetPlayerId, reason, now))
}

/** Invariant 7: below two active players the game ends and the last one standing wins. */
private fun Log.endGameIfTooFewPlayers(now: Instant, config: EngineConfig) {
    val game = state.game ?: return
    if (game.status != GameStatus.IN_PROGRESS) return
    val remaining = state.activePlayers
    if (remaining.size >= MIN_PLAYERS_TO_PLAY) return
    completeGame(winner = remaining.firstOrNull()?.playerId, abandoned = true, now = now, config = config)
}

private fun Log.completeGame(winner: String?, abandoned: Boolean, now: Instant, config: EngineConfig) {
    val game = state.game ?: return
    val points = game.hands.mapValues { (_, hand) -> hand.points }
    // Winner first, then fewest points first; the playerId breaks ties so the order is the same on
    // every replay and Elo in P6 cannot depend on map iteration order.
    val rest = game.hands.keys
        .filter { it != winner }
        .sortedWith(compareBy({ points.getValue(it) }, { it }))
    emit(
        GameCompleted(
            roomType = state.roomType,
            gameNumber = game.gameNumber,
            finishingOrder = listOfNotNull(winner) + rest,
            cardPointTotals = points,
            isAbandoned = abandoned,
            completedAt = now,
            at = now,
        ),
    )
    if (state.gamesPlayed >= state.maxGames) {
        emit(RoomCompleted(state.roomType, state.game!!.finishingOrder, now))
    }
}
