// Load, decide, append — command processing, and the one place that knows a clock, a database and
// another service all exist. The aggregate below it decides; the HTTP and Kafka edges above it
// decide status codes and offsets.

import java.time.Instant
import java.util.UUID

sealed interface Outcome {
    val state: TournamentState

    data class Ok(override val state: TournamentState, val events: List<Event> = emptyList()) : Outcome
    data class Refused(val reason: Rejection, override val state: TournamentState) : Outcome
    data object NotFound : Outcome {
        override val state: TournamentState get() = TournamentState()
    }
}

class Tournaments(
    private val store: Store,
    private val rooms: RoomProvisioner,
    private val config: Config,
    private val now: () -> Instant = Instant::now,
) {
    private val defaults = Config.tournamentConfig(config)

    fun load(tournamentId: UUID): LoadedTournament = store.load(tournamentId)

    fun open(): List<TournamentSummary> = store.openTournaments()

    fun bracket(tournamentId: UUID): List<BracketRoom> = store.bracket(tournamentId)

    fun create(correlationId: String?): Outcome {
        val tournamentId = UUID.randomUUID()
        val empty = TournamentState(tournamentId = tournamentId.toString())
        val decision = decide(empty, CreateTournament(tournamentId.toString(), defaults), now())
        val state = empty.after(decision.events)
        store.append(tournamentId, 0, decision.events, state, correlationId)
        Metrics.tournamentsCreated.increment()
        return Outcome.Ok(state, decision.events)
    }

    /**
     * A lost race re-decides against the state the winner wrote, exactly as room-gameplay's `submit`
     * does: two players registering at once is the normal case, not an error either of them should
     * see. Registration is what crosses the threshold, so this is also the path that starts a
     * tournament — and then round 1 has to be provisioned, which happens outside the transaction.
     */
    fun submit(tournamentId: UUID, command: Command, correlationId: String?, attempts: Int = 3): Outcome {
        repeat(attempts) {
            val loaded = store.load(tournamentId)
            if (!loaded.found) return Outcome.NotFound
            val state = loaded.state

            val decision = decide(state, command, now())
            if (decision is Decision.Rejected) return Outcome.Refused(decision.reason, state)
            if (decision.events.isEmpty()) return Outcome.Ok(state)

            val after = state.after(decision.events)
            if (store.append(tournamentId, state.sequenceNumber, decision.events, after, correlationId)
                is AppendResult.Committed
            ) {
                countBusiness(decision.events)
                // The tournament has just started; its first round does not exist yet.
                if (decision.events.any { it is TournamentStarted }) advance(tournamentId, correlationId)
                return Outcome.Ok(after, decision.events)
            }
        }
        return Outcome.Ok(store.load(tournamentId).state)
    }

    /**
     * Starts whatever round should exist now, and does nothing if one already does. This is the step
     * that talks to room-gameplay, so it runs OUTSIDE the transaction that appends `RoundStarted`:
     * the rooms are created first, then announced. A crash in between leaves rooms nobody has heard
     * of, and the next call re-provisions them under the same idempotency keys and gets the same
     * ids back — which is why the key is the round's own coordinates and not a fresh uuid.
     */
    fun advance(tournamentId: UUID, correlationId: String? = null): Outcome {
        val loaded = store.load(tournamentId)
        if (!loaded.found) return Outcome.NotFound
        val state = loaded.state
        if (state.status != TournamentStatus.IN_PROGRESS) return Outcome.Ok(state)

        val current = state.currentRound
        if (current != null && !current.complete) return Outcome.Ok(state)

        val survivors = current?.survivors ?: state.registered
        val roundNumber = (current?.roundNumber ?: 0) + 1
        if (survivors.size < MIN_ROOM_SIZE) return Outcome.Ok(state)

        val groups = assignRooms(survivors, state.config.roomSize)
        val isFinal = groups.size == 1
        val provisioned = groups.mapIndexed { index, players ->
            val room = rooms.provision(
                tournamentId = tournamentId.toString(),
                roundNumber = roundNumber,
                roomIndex = index,
                players = players,
                advanceCount = if (isFinal) 1 else advanceCountFor(players.size, state.config),
                correlationId = correlationId ?: "round-$roundNumber",
            )
            Metrics.roomsProvisioned.increment()
            RoomRef(room.roomId, players)
        }

        return submitStartRound(tournamentId, StartRound(roundNumber, provisioned, isFinal), correlationId)
    }

    private fun submitStartRound(tournamentId: UUID, command: StartRound, correlationId: String?): Outcome {
        val loaded = store.load(tournamentId)
        val state = loaded.state
        val decision = decide(state, command, now())
        if (decision is Decision.Rejected) return Outcome.Refused(decision.reason, state)
        val after = state.after(decision.events)
        store.append(tournamentId, state.sequenceNumber, decision.events, after, correlationId)
        countBusiness(decision.events)
        return Outcome.Ok(after, decision.events)
    }

    /**
     * The saga's entry point. Dedup and the state change share one transaction; a round that
     * completes here is advanced immediately rather than waiting for the reconciler, because a demo
     * should not have to wait for a sweep.
     */
    fun recordResult(
        roomId: String,
        advancingPlayers: List<String>,
        eventKey: String,
        correlationId: String?,
    ): String {
        val location = store.locate(roomId) ?: return "not_ours"
        val loaded = store.load(location.tournamentId)
        if (!loaded.found) return "not_ours"

        val state = loaded.state
        val decision = decide(state, RecordRoomResult(roomId, advancingPlayers), now())
        if (decision is Decision.Rejected) return "refused"

        val after = state.after(decision.events)
        val result = store.consume(
            eventKey = eventKey,
            tournamentId = location.tournamentId,
            baseSequence = state.sequenceNumber,
            events = decision.events,
            state = after,
            correlationId = correlationId,
        )
        if (result is AppendResult.Conflict) return "duplicate"

        countBusiness(decision.events)
        if (decision.events.any { it is RoundCompleted } && after.status == TournamentStatus.IN_PROGRESS) {
            advance(location.tournamentId, correlationId)
        }
        return if (decision.events.isEmpty()) "already_recorded" else "recorded"
    }

    /**
     * Finishes what a crash interrupted (§7.4.2's "partial round advancement"). Every step it calls
     * is idempotent, so a sweep that has nothing to do writes nothing — the same bargain the timer
     * worker made: a tick that finds nothing due is free.
     */
    fun reconcile(): Int {
        var advanced = 0
        store.needingAttention().forEach { tournamentId ->
            runCatching { advance(tournamentId) }
                .onSuccess { outcome -> if (outcome is Outcome.Ok && outcome.events.isNotEmpty()) advanced++ }
                .onFailure { error ->
                    Metrics.reconcileFailures.increment()
                    logError("action" to "reconcile-failed", "tournamentId" to tournamentId, "error" to error.toString())
                }
        }
        Metrics.reconcileSweeps.increment()
        return advanced
    }

    private fun countBusiness(events: List<Event>) = events.forEach { event ->
        when (event) {
            is PlayerRegistered -> Metrics.registrations.increment()
            is TournamentStarted -> Metrics.tournamentsStarted.increment()
            is RoundStarted -> Metrics.roundsStarted.increment()
            is RoundCompleted -> Metrics.roundsCompleted.increment()
            is TournamentCompleted -> Metrics.tournamentsCompleted.increment()
            else -> Unit
        }
    }
}

fun TournamentState.after(events: List<Event>): TournamentState = events.fold(this, ::evolve)
