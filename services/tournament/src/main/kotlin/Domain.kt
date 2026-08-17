// The Tournament aggregate of design §3.3, as the same decide/evolve pair room-gameplay uses for a
// room: a pure function of (state, command) to events, and a pure fold of an event into state.
// Nothing here reads a clock, a database or a socket — the edges do that and hand the values in.
//
// The rooms are NOT modelled here beyond their references (§3.3.1): a tournament holds player ids
// and room ids, never game state. Who won a match is the room's decision, and it arrives as one
// `advancingPlayers` list per room.

@file:UseSerializers(InstantSerializer::class)

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

enum class TournamentStatus { REGISTRATION, IN_PROGRESS, COMPLETED }

@Serializable
data class TournamentConfig(
    /** The low, configurable threshold the exam asks for; the tournament starts itself here. */
    val minPlayers: Int,
    val roomSize: Int,
    val advanceCount: Int,
)

data class RoomRef(
    val roomId: String,
    val players: List<String>,
    /** Null until the room reports. `emptyList()` is a real answer: nobody advanced from it. */
    val advancing: List<String>? = null,
) {
    val reported: Boolean get() = advancing != null
}

data class Round(
    val roundNumber: Int,
    val players: List<String>,
    val rooms: List<RoomRef>,
    val isFinal: Boolean,
) {
    val complete: Boolean get() = rooms.all { it.reported }
    val survivors: List<String> get() = rooms.flatMap { it.advancing ?: emptyList() }
}

data class TournamentState(
    val tournamentId: String = "",
    val status: TournamentStatus = TournamentStatus.REGISTRATION,
    val config: TournamentConfig = TournamentConfig(0, 0, 0),
    /** Registration order, which is also the seeding order — deterministic, and nobody's advantage. */
    val registered: List<String> = emptyList(),
    val rounds: List<Round> = emptyList(),
    val champion: String? = null,
    val finalPlacements: List<String> = emptyList(),
    val sequenceNumber: Int = 0,
    val createdAt: Instant? = null,
) {
    val exists: Boolean get() = createdAt != null
    val currentRound: Round? get() = rounds.lastOrNull()
    fun round(number: Int): Round? = rounds.firstOrNull { it.roundNumber == number }
    fun roomOf(roomId: String): Pair<Round, RoomRef>? =
        rounds.firstNotNullOfOrNull { round -> round.rooms.firstOrNull { it.roomId == roomId }?.let { round to it } }
}

// ---------------------------------------------------------------- commands

sealed interface Command

data class CreateTournament(val tournamentId: String, val config: TournamentConfig) : Command
data class RegisterPlayer(val playerId: String) : Command
data class UnregisterPlayer(val playerId: String) : Command
data object StartTournament : Command

/**
 * The round's rooms exist by the time this is decided: provisioning happens before the append, so a
 * `RoundStarted` never names a room that was not created (§7.4.2's "rooms_created / rooms_expected"
 * is a transaction here rather than a counter).
 */
data class StartRound(val roundNumber: Int, val rooms: List<RoomRef>, val isFinal: Boolean) : Command

/** Raised by the saga on `MatchCompleted` / `RoomExpired`. Idempotent by room, per §4.2. */
data class RecordRoomResult(val roomId: String, val advancingPlayers: List<String>) : Command

enum class Rejection {
    TOURNAMENT_NOT_FOUND,
    TOURNAMENT_ALREADY_EXISTS,
    REGISTRATION_CLOSED,
    ALREADY_REGISTERED,
    NOT_REGISTERED,
    NOT_ENOUGH_PLAYERS,
    ALREADY_STARTED,
    ROUND_ALREADY_STARTED,
    UNKNOWN_ROOM,
}

sealed interface Decision {
    val events: List<Event>

    data class Accepted(override val events: List<Event>) : Decision
    data class Rejected(val reason: Rejection, override val events: List<Event> = emptyList()) : Decision
}

// ---------------------------------------------------------------- events

/**
 * Names and payloads follow `docs/design/04-commands-events.md` §4.2 exactly, the way the engine's
 * events follow §4.1: these are the wire contract the moment the relay drains the outbox, so
 * renaming one later is a breaking change wearing a refactor's clothes.
 */
@Serializable
sealed interface Event {
    val at: Instant
}

@Serializable
@SerialName("TournamentCreated")
data class TournamentCreated(val config: TournamentConfig, override val at: Instant) : Event

@Serializable
@SerialName("PlayerRegistered")
data class PlayerRegistered(val playerId: String, val registeredCount: Int, override val at: Instant) : Event

@Serializable
@SerialName("PlayerUnregistered")
data class PlayerUnregistered(val playerId: String, override val at: Instant) : Event

@Serializable
@SerialName("TournamentStarted")
data class TournamentStarted(val totalPlayers: Int, val roundCount: Int, override val at: Instant) : Event

@Serializable
@SerialName("RoundStarted")
data class RoundStarted(
    val roundNumber: Int,
    val roomCount: Int,
    val roomIds: List<String>,
    val assignments: Map<String, List<String>>,
    override val at: Instant,
) : Event

/** §4.2 lists this separately from `RoundStarted`; it is the last round, named as one. */
@Serializable
@SerialName("FinalRoomCreated")
data class FinalRoomCreated(val roomId: String, val finalists: List<String>, override val at: Instant) : Event

@Serializable
@SerialName("RoomResultRecorded")
data class RoomResultRecorded(
    val roundNumber: Int,
    val roomId: String,
    val advancingPlayers: List<String>,
    override val at: Instant,
) : Event

@Serializable
@SerialName("RoundCompleted")
data class RoundCompleted(
    val roundNumber: Int,
    val advancingPlayersTotal: Int,
    override val at: Instant,
) : Event

/**
 * `champion` is null when the last room advanced nobody — a tournament everybody abandoned still
 * has to end, or its rounds wait for a result that is never coming.
 */
@Serializable
@SerialName("TournamentCompleted")
data class TournamentCompleted(
    val champion: String?,
    val finalPlacements: List<String>,
    override val at: Instant,
) : Event

// ---------------------------------------------------------------- decide

fun decide(state: TournamentState, command: Command, now: Instant): Decision {
    val log = Log(state)
    return when (command) {
        is CreateTournament -> log.create(command, now)
        is RegisterPlayer -> log.register(command, now)
        is UnregisterPlayer -> log.unregister(command, now)
        is StartTournament -> log.start(now)
        is StartRound -> log.startRound(command, now)
        is RecordRoomResult -> log.record(command, now)
    }
}

private class Log(initial: TournamentState) {
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

private fun Log.create(command: CreateTournament, now: Instant): Decision {
    if (state.exists) return reject(Rejection.TOURNAMENT_ALREADY_EXISTS)
    emit(TournamentCreated(command.config, now))
    return accept()
}

/**
 * Registering twice is a no-op rather than an error (§4.2): the CLI polls and retries, and a player
 * who is already in is in.
 */
private fun Log.register(command: RegisterPlayer, now: Instant): Decision {
    if (!state.exists) return reject(Rejection.TOURNAMENT_NOT_FOUND)
    if (state.status != TournamentStatus.REGISTRATION) return reject(Rejection.REGISTRATION_CLOSED)
    if (command.playerId in state.registered) return accept()

    emit(PlayerRegistered(command.playerId, state.registered.size + 1, now))
    // The threshold starts the tournament itself (D5): the demo is one command per player, with no
    // operator standing by to press start.
    if (state.registered.size >= state.config.minPlayers) startNow(now)
    return accept()
}

private fun Log.unregister(command: UnregisterPlayer, now: Instant): Decision {
    if (!state.exists) return reject(Rejection.TOURNAMENT_NOT_FOUND)
    if (state.status != TournamentStatus.REGISTRATION) return reject(Rejection.REGISTRATION_CLOSED)
    if (command.playerId !in state.registered) return reject(Rejection.NOT_REGISTERED)
    emit(PlayerUnregistered(command.playerId, now))
    return accept()
}

private fun Log.start(now: Instant): Decision {
    if (!state.exists) return reject(Rejection.TOURNAMENT_NOT_FOUND)
    if (state.status != TournamentStatus.REGISTRATION) return reject(Rejection.ALREADY_STARTED)
    if (state.registered.size < state.config.roomSize) return reject(Rejection.NOT_ENOUGH_PLAYERS)
    startNow(now)
    return accept()
}

private fun Log.startNow(now: Instant) {
    emit(TournamentStarted(state.registered.size, estimatedRounds(state.registered.size, state.config), now))
}

private fun Log.startRound(command: StartRound, now: Instant): Decision {
    if (state.status != TournamentStatus.IN_PROGRESS) return reject(Rejection.TOURNAMENT_NOT_FOUND)
    if (state.round(command.roundNumber) != null) return reject(Rejection.ROUND_ALREADY_STARTED)

    emit(
        RoundStarted(
            roundNumber = command.roundNumber,
            roomCount = command.rooms.size,
            roomIds = command.rooms.map { it.roomId },
            assignments = command.rooms.associate { it.roomId to it.players },
            at = now,
        ),
    )
    if (command.isFinal) {
        val room = command.rooms.single()
        emit(FinalRoomCreated(room.roomId, room.players, now))
    }
    return accept()
}

/**
 * The round-completion gate of §7.4.3, as a fold rather than a counter: a round is complete when
 * every one of its rooms has reported. A room reporting twice is the same room, so a redelivered
 * `MatchCompleted` cannot advance a round twice.
 */
private fun Log.record(command: RecordRoomResult, now: Instant): Decision {
    val found = state.roomOf(command.roomId) ?: return reject(Rejection.UNKNOWN_ROOM)
    val (round, room) = found
    if (room.reported) return accept()

    emit(RoomResultRecorded(round.roundNumber, room.roomId, command.advancingPlayers, now))

    val updated = state.round(round.roundNumber)!!
    if (!updated.complete) return accept()

    emit(RoundCompleted(round.roundNumber, updated.survivors.size, now))
    if (round.isFinal || updated.survivors.size <= 1) {
        // The placements: the champion first, then everyone who fell at the last hurdle, then the
        // rounds before that — a bracket read backwards.
        emit(TournamentCompleted(updated.survivors.firstOrNull(), placements(state), now))
    }
    return accept()
}

// ---------------------------------------------------------------- evolve

fun evolve(state: TournamentState, event: Event): TournamentState {
    val next = apply(state, event)
    return next.copy(sequenceNumber = state.sequenceNumber + 1)
}

fun replay(events: List<Event>, tournamentId: String = ""): TournamentState =
    events.fold(TournamentState(tournamentId = tournamentId), ::evolve)

private fun apply(state: TournamentState, event: Event): TournamentState = when (event) {
    is TournamentCreated -> state.copy(config = event.config, createdAt = event.at)

    is PlayerRegistered -> state.copy(registered = state.registered + event.playerId)

    is PlayerUnregistered -> state.copy(registered = state.registered - event.playerId)

    is TournamentStarted -> state.copy(status = TournamentStatus.IN_PROGRESS)

    is RoundStarted -> state.copy(
        rounds = state.rounds + Round(
            roundNumber = event.roundNumber,
            players = event.assignments.values.flatten(),
            rooms = event.roomIds.map { RoomRef(it, event.assignments[it] ?: emptyList()) },
            isFinal = false,
        ),
    )

    // Named after the round it belongs to rather than carrying its own copy of it: the round is
    // already in state, and two records of "which round is the final" could disagree.
    is FinalRoomCreated -> state.copy(
        rounds = state.rounds.map { if (it.rooms.any { room -> room.roomId == event.roomId }) it.copy(isFinal = true) else it },
    )

    is RoomResultRecorded -> state.copy(
        rounds = state.rounds.map { round ->
            if (round.roundNumber != event.roundNumber) {
                round
            } else {
                round.copy(
                    rooms = round.rooms.map {
                        if (it.roomId == event.roomId) it.copy(advancing = event.advancingPlayers) else it
                    },
                )
            }
        },
    )

    // The round's own state already says it is complete; this event is the announcement.
    is RoundCompleted -> state

    is TournamentCompleted -> state.copy(
        status = TournamentStatus.COMPLETED,
        champion = event.champion,
        finalPlacements = event.finalPlacements,
    )
}

// ---------------------------------------------------------------- pure helpers

/**
 * Seeding is the registration order, chunked. Deterministic on purpose: the same registrations
 * produce the same bracket on a replay, and a shuffle would make a stuck round impossible to
 * reproduce. Rating-based seeding is out of scope for P7.
 */
fun assignRooms(players: List<String>, roomSize: Int): List<List<String>> {
    if (players.size <= roomSize) return listOf(players)
    val rooms = players.chunked(roomSize).toMutableList()
    // A trailing room of one cannot play: fold it back into the previous room rather than creating
    // a table for a single player, which room-gameplay would refuse anyway.
    if (rooms.size > 1 && rooms.last().size < MIN_ROOM_SIZE) {
        val orphans = rooms.removeAt(rooms.size - 1)
        rooms[rooms.size - 1] = rooms.last() + orphans
    }
    return rooms
}

/** How many seats a room of this size gives up, never more than the room can spare. */
fun advanceCountFor(roomPlayers: Int, config: TournamentConfig): Int =
    config.advanceCount.coerceIn(1, (roomPlayers - 1).coerceAtLeast(1))

fun estimatedRounds(players: Int, config: TournamentConfig): Int {
    var remaining = players
    var rounds = 0
    while (remaining > config.roomSize && rounds < MAX_ESTIMATED_ROUNDS) {
        val rooms = assignRooms(List(remaining) { "p$it" }, config.roomSize)
        remaining = rooms.sumOf { advanceCountFor(it.size, config) }
        rounds++
    }
    return rounds + 1
}

/** Champion first, then the rounds in reverse: the further you got, the higher you placed. */
private fun placements(state: TournamentState): List<String> {
    val seen = LinkedHashSet<String>()
    state.rounds.reversed().forEach { round ->
        round.survivors.forEach { seen.add(it) }
        round.players.forEach { seen.add(it) }
    }
    state.registered.forEach { seen.add(it) }
    return seen.toList()
}

const val MIN_ROOM_SIZE = 2
private const val MAX_ESTIMATED_ROUNDS = 32
