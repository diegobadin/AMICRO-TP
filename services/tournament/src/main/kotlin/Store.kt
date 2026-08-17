// Every database access the tournament makes, in room-gameplay's `EventStore` shape: load by
// replay, append events + outbox rows + projections in ONE transaction, and let the primary key on
// (tournament_id, sequence_number) be the concurrency control rather than a lock.

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.sql.SQLException
import java.sql.Types
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

const val OUTBOX_TOPIC = "tournament.lifecycle.events"
const val ROOM_EVENTS_SOURCE = "room.lifecycle.events"

private const val UNIQUE_VIOLATION = "23505"

data class LoadedTournament(val state: TournamentState, val found: Boolean)

sealed interface AppendResult {
    data class Committed(val sequenceNumber: Int) : AppendResult
    data object Conflict : AppendResult
}

data class RoomLocation(val tournamentId: UUID, val roundNumber: Int, val reported: Boolean)

class Store(private val dataSource: DataSource) {

    fun load(tournamentId: UUID): LoadedTournament {
        val events = mutableListOf<Event>()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select payload from tournament_events where tournament_id = ? order by sequence_number",
            ).use { statement ->
                statement.setObject(1, tournamentId)
                statement.executeQuery().use { rows ->
                    while (rows.next()) events += decodeEvent(Json.parseToJsonElement(rows.getString(1)) as JsonObject)
                }
            }
        }
        return LoadedTournament(replay(events, tournamentId.toString()), events.isNotEmpty())
    }

    /**
     * The rooms of a round are written here as well as into the log, because the saga arrives with
     * only a room id: `MatchCompleted` carries the match result, not the tournament it belongs to.
     */
    fun append(
        tournamentId: UUID,
        baseSequence: Int,
        events: List<Event>,
        state: TournamentState,
        correlationId: String?,
    ): AppendResult {
        if (events.isEmpty()) return AppendResult.Committed(baseSequence)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                insertEvents(connection, tournamentId, baseSequence, events, correlationId)
                insertOutbox(connection, tournamentId, baseSequence, events, correlationId)
                upsertProjection(connection, tournamentId, state)
                upsertRooms(connection, tournamentId, state, events)
                connection.commit()
                return AppendResult.Committed(baseSequence + events.size)
            } catch (e: SQLException) {
                connection.rollback()
                if (e.sqlState == UNIQUE_VIOLATION) return AppendResult.Conflict
                throw e
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    /**
     * Which tournament a room belongs to. Returns null for a room this service never provisioned —
     * every casual game in the system publishes to the same topic, and those are not ours.
     */
    fun locate(roomId: String): RoomLocation? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select tournament_id, round_number, reported_at from round_rooms where room_id = ?::uuid",
            ).use { statement ->
                statement.setString(1, roomId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) {
                        null
                    } else {
                        RoomLocation(
                            tournamentId = UUID.fromString(rows.getString(1)),
                            roundNumber = rows.getInt(2),
                            reported = rows.getTimestamp(3) != null,
                        )
                    }
                }
            }
        }

    /** True the first time this key is seen; false for a redelivery. */
    fun markConsumed(source: String, eventKey: String, connection: Connection): Boolean =
        connection.prepareStatement(
            "insert into consumed_events (source, event_key) values (?, ?) on conflict do nothing",
        ).use { statement ->
            statement.setString(1, source)
            statement.setString(2, eventKey)
            statement.executeUpdate() == 1
        }

    /**
     * The saga's write: dedup and the state change share one transaction, so a redelivery that
     * arrives while the first copy is still committing cannot record the same room twice.
     */
    fun consume(
        eventKey: String,
        tournamentId: UUID,
        baseSequence: Int,
        events: List<Event>,
        state: TournamentState,
        correlationId: String?,
    ): AppendResult {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                if (!markConsumed(ROOM_EVENTS_SOURCE, eventKey, connection)) {
                    connection.rollback()
                    return AppendResult.Conflict
                }
                if (events.isNotEmpty()) {
                    insertEvents(connection, tournamentId, baseSequence, events, correlationId)
                    insertOutbox(connection, tournamentId, baseSequence, events, correlationId)
                    upsertProjection(connection, tournamentId, state)
                    upsertRooms(connection, tournamentId, state, events)
                }
                connection.commit()
                return AppendResult.Committed(baseSequence + events.size)
            } catch (e: SQLException) {
                connection.rollback()
                if (e.sqlState == UNIQUE_VIOLATION) return AppendResult.Conflict
                throw e
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    fun openTournaments(limit: Int = 100): List<TournamentSummary> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """select tournament_id, status, player_count, min_players, current_round
                   from tournaments where status <> 'COMPLETED' order by created_at asc limit ?""",
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                TournamentSummary(
                                    tournamentId = rows.getString(1),
                                    status = rows.getString(2),
                                    playerCount = rows.getInt(3),
                                    minPlayers = rows.getInt(4),
                                    currentRound = rows.getInt(5),
                                ),
                            )
                        }
                    }
                }
            }
        }

    /**
     * Tournaments the reconciler should look at: in progress, and either without a round at all or
     * with one whose rooms have all reported. A crash between provisioning and the append lands
     * here, and so does a round whose last result arrived while the process was restarting.
     */
    fun needingAttention(limit: Int = 20): List<UUID> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select tournament_id from tournaments where status = 'IN_PROGRESS' order by updated_at asc limit ?",
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(UUID.fromString(rows.getString(1))) }
                }
            }
        }

    fun bracket(tournamentId: UUID): List<BracketRoom> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """select round_number, room_id, players, advancing, is_final
                   from round_rooms where tournament_id = ? order by round_number, created_at""",
            ).use { statement ->
                statement.setObject(1, tournamentId)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                BracketRoom(
                                    roundNumber = rows.getInt(1),
                                    roomId = rows.getString(2),
                                    players = Json.decodeFromString(rows.getString(3)),
                                    advancing = rows.getString(4)?.let { Json.decodeFromString<List<String>>(it) },
                                    isFinal = rows.getBoolean(5),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun insertEvents(
        connection: Connection,
        tournamentId: UUID,
        baseSequence: Int,
        events: List<Event>,
        correlationId: String?,
    ) = connection.prepareStatement(
        "insert into tournament_events (tournament_id, sequence_number, type, payload, correlation_id)" +
            " values (?, ?, ?, ?::jsonb, ?)",
    ).use { statement ->
        events.forEachIndexed { index, event ->
            statement.setObject(1, tournamentId)
            statement.setInt(2, baseSequence + index + 1)
            statement.setString(3, eventType(event))
            statement.setString(4, encodeEvent(event).toString())
            statement.setString(5, correlationId)
            statement.addBatch()
        }
        statement.executeBatch()
    }

    // No privacy filter on the way out, unlike room-gameplay's `publicPayload`: a tournament event
    // has no hands, no deck and no seed in it — there is nothing here a spectator may not see.
    private fun insertOutbox(
        connection: Connection,
        tournamentId: UUID,
        baseSequence: Int,
        events: List<Event>,
        correlationId: String?,
    ) = connection.prepareStatement(
        "insert into outbox (tournament_id, sequence_number, topic, event_type, payload, correlation_id)" +
            " values (?, ?, ?, ?, ?::jsonb, ?)",
    ).use { statement ->
        events.forEachIndexed { index, event ->
            statement.setObject(1, tournamentId)
            statement.setInt(2, baseSequence + index + 1)
            statement.setString(3, OUTBOX_TOPIC)
            statement.setString(4, eventType(event))
            statement.setString(5, encodeEvent(event).toString())
            statement.setString(6, correlationId)
            statement.addBatch()
        }
        statement.executeBatch()
    }

    private fun upsertProjection(connection: Connection, tournamentId: UUID, state: TournamentState) =
        connection.prepareStatement(
            """insert into tournaments (tournament_id, status, player_count, min_players, room_size,
                                        advance_count, current_round, sequence_number, created_at)
               values (?, ?, ?, ?, ?, ?, ?, ?, ?)
               on conflict (tournament_id) do update set
                 status = excluded.status,
                 player_count = excluded.player_count,
                 current_round = excluded.current_round,
                 sequence_number = excluded.sequence_number,
                 updated_at = now()""",
        ).use { statement ->
            statement.setObject(1, tournamentId)
            statement.setString(2, state.status.name)
            statement.setInt(3, state.registered.size)
            statement.setInt(4, state.config.minPlayers)
            statement.setInt(5, state.config.roomSize)
            statement.setInt(6, state.config.advanceCount)
            statement.setInt(7, state.currentRound?.roundNumber ?: 0)
            statement.setInt(8, state.sequenceNumber)
            statement.setObject(9, java.sql.Timestamp.from(state.createdAt ?: Instant.now()))
            statement.executeUpdate()
        }

    /**
     * Only the rooms the batch actually touched. Writing every room of every round on each append
     * would be correct and quadratic; the events say precisely which rooms changed.
     */
    private fun upsertRooms(
        connection: Connection,
        tournamentId: UUID,
        state: TournamentState,
        events: List<Event>,
    ) {
        val touched = events.flatMap { event ->
            when (event) {
                is RoundStarted -> event.roomIds
                is FinalRoomCreated -> listOf(event.roomId)
                is RoomResultRecorded -> listOf(event.roomId)
                else -> emptyList()
            }
        }.distinct()
        if (touched.isEmpty()) return

        connection.prepareStatement(
            """insert into round_rooms (room_id, tournament_id, round_number, players, advancing, is_final, reported_at)
               values (?::uuid, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
               on conflict (room_id) do update set
                 advancing = excluded.advancing,
                 is_final = excluded.is_final,
                 reported_at = excluded.reported_at""",
        ).use { statement ->
            touched.forEach { roomId ->
                val found = state.roomOf(roomId) ?: return@forEach
                val (round, room) = found
                statement.setString(1, roomId)
                statement.setObject(2, tournamentId)
                statement.setInt(3, round.roundNumber)
                statement.setString(4, Json.encodeToString(room.players))
                statement.setString(5, room.advancing?.let { Json.encodeToString(it) })
                statement.setBoolean(6, round.isFinal)
                if (room.reported) {
                    statement.setObject(7, java.sql.Timestamp.from(Instant.now()))
                } else {
                    statement.setNull(7, Types.TIMESTAMP)
                }
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }
}
