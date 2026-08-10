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
import uno.Rejection
import uno.RoomState
import uno.RoomType
import uno.decide

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
) {
    private val engine = EngineConfig(minPlayers = config.minPlayers, turnTimeoutSeconds = config.turnTimeoutSeconds)

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

            when (store.append(roomId, state.sequenceNumber, decision.events, after, correlationId)) {
                is AppendResult.Committed -> return when (decision) {
                    is Decision.Accepted -> Outcome.Ok(after, decision.events)
                    is Decision.Rejected -> Outcome.Refused(decision.reason, after, decision.events)
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

        return when (store.append(roomId, 0, events, state, correlationId, record)) {
            is AppendResult.Committed -> CreateOutcome.Created(roomId, state, events)
            AppendResult.Conflict -> idempotencyKey
                ?.let { store.findIdempotent(it, playerId) }
                ?.let { CreateOutcome.Replayed(it) }
                ?: error("room $roomId conflicted on a fresh id with no idempotency key")
        }
    }
}

sealed interface CreateOutcome {
    data class Created(val roomId: UUID, val state: RoomState, val events: List<Event>) : CreateOutcome
    /** This player already used this key; the response they got the first time is returned verbatim. */
    data class Replayed(val response: String) : CreateOutcome
}
