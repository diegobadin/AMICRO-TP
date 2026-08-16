// Load, decide, append — the whole of command processing (Architecture §2.5, steps 1-6). The HTTP
// layer above this decides status codes; the engine below it decides rules; this is the only place
// that knows both a database and a clock exist.

import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import uno.Command
import uno.CreateRoom
import uno.Decision
import uno.EngineConfig
import uno.Event
import uno.JoinRoom
import uno.Rejection
import uno.RoomState
import uno.RoomType
import uno.StartGame
import uno.TournamentLink
import uno.decide
import uno.nextDeadline

sealed interface Outcome {
    val state: RoomState
    /** What was actually appended — the business counters are derived from this, not guessed at. */
    val events: List<Event>

    data class Ok(override val state: RoomState, override val events: List<Event> = emptyList()) : Outcome
    data class Refused(
        val reason: Rejection,
        override val state: RoomState,
        override val events: List<Event> = emptyList(),
    ) : Outcome

    /**
     * The caller's `If-Match` no longer matches; the state comes back so it can reconcile. Always
     * empty: a failed precondition is judged before anything is decided, and a lost race rolled
     * back — either way nothing reached the log.
     */
    data class Stale(override val state: RoomState) : Outcome {
        override val events: List<Event> get() = emptyList()
    }
}

class Rooms(
    private val store: EventStore,
    config: Config,
    private val now: () -> Instant = Instant::now,
    private val seed: () -> Long = { Random.Default.nextLong() },
    // The realtime tier's source. It lives here rather than in the HTTP layer because commands
    // arrive from Kafka too — a `SessionInvalidated` produces `PlayerDisconnected`, and the players
    // watching that room have to see it like any other event.
    private val stream: RoomEvents = NoRoomEvents,
) {
    private val engine = EngineConfig(
        minPlayers = config.minPlayers,
        turnTimeoutSeconds = config.turnTimeoutSeconds,
        idleTimeoutsBeforeForfeit = config.idleTimeoutsBeforeForfeit,
        waitingRoomExpirySeconds = config.waitingRoomExpirySeconds,
    )

    fun load(roomId: UUID): LoadedRoom = store.load(roomId)

    fun listJoinable(): List<RoomSummary> = store.listJoinable()

    fun activeRoomsOf(playerId: String): List<UUID> = store.activeRoomsOf(playerId)

    /**
     * A lost race is not an error the player should see: the winner only moved the room forward, so
     * the command is re-decided against the new state and tried again. Only a caller who pinned a
     * specific sequence with `If-Match` gets a `412` instead, because for them the state they were
     * looking at is part of the request.
     */
    fun submit(
        roomId: UUID,
        command: Command,
        correlationId: String?,
        expectedSequence: Int? = null,
        attempts: Int = 3,
    ): Outcome {
        repeat(attempts) {
            val state = store.load(roomId).state
            if (expectedSequence != null && expectedSequence != state.sequenceNumber) return Outcome.Stale(state)

            val decision = decide(state, command, now(), seed(), engine)
            val after = state.after(decision.events)

            if (decision.events.isEmpty()) {
                return when (decision) {
                    is Decision.Accepted -> Outcome.Ok(state)
                    is Decision.Rejected -> Outcome.Refused(decision.reason, state)
                }
            }

            when (
                store.append(
                    roomId, state.sequenceNumber, decision.events, after, correlationId,
                    nextDeadline = nextDeadline(after, engine),
                )
            ) {
                is AppendResult.Committed -> {
                    stream.published(roomId, state.sequenceNumber, decision.events, correlationId)
                    return when (decision) {
                        is Decision.Accepted -> Outcome.Ok(after, decision.events)
                        is Decision.Rejected -> Outcome.Refused(decision.reason, after, decision.events)
                    }
                }
                // Someone else took the sequence number. With an explicit If-Match the caller has to
                // reconcile; otherwise loop and re-decide against what they wrote.
                AppendResult.Conflict -> if (expectedSequence != null) return Outcome.Stale(store.load(roomId).state)
            }
        }
        return Outcome.Stale(store.load(roomId).state)
    }

    /**
     * Creating a room is not `submit` with extra arguments: the id is brand new, so there is no
     * sequence number to lose a race for and nothing to retry. The only way the append can conflict
     * is another request having used the same `Idempotency-Key` first — in which case that request's
     * response *is* the answer, which is the whole point of the header.
     */
    fun create(
        playerId: String,
        maxPlayers: Int,
        idempotencyKey: String?,
        correlationId: String?,
        render: (RoomState) -> String,
    ): CreateOutcome {
        idempotencyKey?.let { key ->
            store.findIdempotent(key, playerId)?.let { return CreateOutcome.Replayed(it) }
        }

        val roomId = UUID.randomUUID()
        val empty = RoomState(roomId = roomId.toString())
        val command = CreateRoom(roomId.toString(), playerId, RoomType.CASUAL, maxPlayers)
        // A fresh room cannot refuse its own creation, so this is Accepted by construction.
        val events = decide(empty, command, now(), seed(), engine).events
        val state = empty.after(events)
        val record = idempotencyKey?.let { IdempotentCreate(it, playerId, render(state)) }
        return commitCreate(roomId, events, state, correlationId, record, idempotencyKey, playerId)
    }

    /**
     * P7 E1: a tournament room arrives complete — every assigned player seated and game 1 dealt, in
     * the single transaction that creates it. Three commands are folded rather than three requests
     * made, because a half-filled tournament room is a state the tournament would have to reconcile
     * and nothing else would ever repair.
     *
     * The caller is a service, so the idempotency key is not a header a client chose: it is
     * `tournamentId:roundNumber:roomIndex`, which is the same room by definition however many times
     * the round is retried.
     */
    fun provision(
        link: TournamentLink,
        players: List<String>,
        idempotencyKey: String,
        correlationId: String?,
        render: (RoomState) -> String,
    ): CreateOutcome {
        val owner = players.first()
        store.findIdempotent(idempotencyKey, owner)?.let { return CreateOutcome.Replayed(it) }

        val roomId = UUID.randomUUID()
        var state = RoomState(roomId = roomId.toString())
        val events = mutableListOf<Event>()
        // Seat everyone, then start. `startGame` is explicit here for the same reason `joinRoom` no
        // longer auto-starts a tournament room: the last player must be holding cards too.
        val commands = listOf(
            CreateRoom(roomId.toString(), owner, RoomType.TOURNAMENT, players.size, link),
        ) + players.drop(1).map { JoinRoom(it) } + StartGame(null)

        for (command in commands) {
            val decision = decide(state, command, now(), seed(), engine)
            if (decision is Decision.Rejected) return CreateOutcome.Refused(decision.reason)
            events += decision.events
            state = state.after(decision.events)
        }

        val record = IdempotentCreate(idempotencyKey, owner, render(state))
        return commitCreate(roomId, events, state, correlationId, record, idempotencyKey, owner)
    }

    private fun commitCreate(
        roomId: UUID,
        events: List<Event>,
        state: RoomState,
        correlationId: String?,
        record: IdempotentCreate?,
        idempotencyKey: String?,
        owner: String,
    ): CreateOutcome =
        when (store.append(roomId, 0, events, state, correlationId, record, nextDeadline(state, engine))) {
            is AppendResult.Committed -> {
                stream.published(roomId, 0, events, correlationId)
                CreateOutcome.Created(roomId, state, events)
            }
            AppendResult.Conflict -> idempotencyKey
                ?.let { store.findIdempotent(it, owner) }
                ?.let { CreateOutcome.Replayed(it) }
                ?: error("room $roomId conflicted on a fresh id with no idempotency key")
        }
}

sealed interface CreateOutcome {
    data class Created(val roomId: UUID, val state: RoomState, val events: List<Event>) : CreateOutcome
    /** This player already used this key; the response they got the first time is returned verbatim. */
    data class Replayed(val response: String) : CreateOutcome
    /** Only provisioning can get here: the assigned players do not make a playable room. */
    data class Refused(val reason: Rejection) : CreateOutcome
}
