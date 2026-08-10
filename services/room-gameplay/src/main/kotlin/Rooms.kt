// Load, decide, append — the whole of command processing (Architecture §2.5, steps 1-6). The HTTP
// layer above this decides status codes; the engine below it decides rules; this is the only place
// that knows both a database and a clock exist.

import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import uno.Command
import uno.Decision
import uno.EngineConfig
import uno.Event
import uno.Rejection
import uno.RoomState
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
        // Built from the resulting state, because the response it stores is the room representation
        // and that is only known once the command has been decided — but it still has to be written
        // inside the same transaction as the events, or a retry could find a key with no room.
        idempotency: ((RoomState) -> IdempotentCreate)? = null,
        attempts: Int = 3,
    ): Outcome {
        repeat(attempts) {
            val loaded = store.load(roomId)
            val state = loaded.state
            if (expectedSequence != null && expectedSequence != state.sequenceNumber) return Outcome.Stale(state)

            val decision = decide(state, command, now(), seed(), engine)
            val after = state.after(decision.events)

            if (decision.events.isEmpty()) {
                return when (decision) {
                    is Decision.Accepted -> Outcome.Ok(state)
                    is Decision.Rejected -> Outcome.Refused(decision.reason, state)
                }
            }

            when (store.append(roomId, state.sequenceNumber, decision.events, after, correlationId, idempotency?.invoke(after))) {
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

    fun findIdempotent(key: String, playerId: String): String? = store.findIdempotent(key, playerId)
}
