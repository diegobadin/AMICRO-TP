// The mechanism behind log-before-broadcast (Architecture §2.5) and optimistic concurrency (E6).
//
// Plain JDBC on purpose (E5): the append-only SQL *is* the guarantee this phase exists to make, and
// it should be readable as SQL rather than inferred from an ORM's behaviour.

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.postgresql.util.PGobject
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import uno.Event
import uno.RoomState
import uno.decodeEvent
import uno.encodeEvent
import uno.evolve
import uno.eventType
import uno.replay

/** Postgres raises 23505 on a unique violation — here, two writers reaching for the same seq. */
private const val UNIQUE_VIOLATION = "23505"

data class LoadedRoom(val state: RoomState, val found: Boolean)

data class IdempotentCreate(val key: String, val playerId: String, val response: String)

sealed interface AppendResult {
    data class Committed(val sequenceNumber: Int) : AppendResult
    /** Someone else took the sequence number between the read and the write; the caller reconciles. */
    data object Conflict : AppendResult
}

class EventStore(private val dataSource: DataSource) {

    fun load(roomId: UUID): LoadedRoom {
        val events = mutableListOf<Event>()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select payload from room_events where room_id = ? order by sequence_number",
            ).use { statement ->
                statement.setObject(1, roomId)
                statement.executeQuery().use { rows ->
                    while (rows.next()) events += decodeEvent(Json.parseToJsonElement(rows.getString(1)) as JsonObject)
                }
            }
        }
        return LoadedRoom(replay(events, roomId.toString()), events.isNotEmpty())
    }

    /**
     * Events, outbox rows and the projection go in one transaction, and the caller only answers the
     * client after it returns. A failure anywhere inside leaves no events, no outbox rows and the
     * sequence number unconsumed — which is the whole of AC-P3.3, and why this is verified against
     * a real Postgres rather than a fake.
     */
    fun append(
        roomId: UUID,
        baseSequence: Int,
        events: List<Event>,
        state: RoomState,
        correlationId: String?,
        idempotency: IdempotentCreate? = null,
    ): AppendResult {
        if (events.isEmpty()) return AppendResult.Committed(baseSequence)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                insertEvents(connection, roomId, baseSequence, events, correlationId)
                insertOutbox(connection, roomId, baseSequence, events, correlationId)
                upsertProjection(connection, roomId, state)
                idempotency?.let { insertIdempotency(connection, roomId, it) }
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

    fun findIdempotent(key: String, playerId: String): String? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select response from idempotency_keys where key = ? and player_id = ?",
            ).use { statement ->
                statement.setString(1, key)
                statement.setString(2, playerId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        }

    fun listJoinable(): List<RoomSummary> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """select room_id, room_type, status, player_count, max_players
                   from rooms where status = 'WAITING' and player_count < max_players
                   order by created_at desc limit 100""",
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                RoomSummary(
                                    roomId = rows.getString(1),
                                    roomType = rows.getString(2),
                                    status = rows.getString(3),
                                    playerCount = rows.getInt(4),
                                    maxPlayers = rows.getInt(5),
                                ),
                            )
                        }
                    }
                }
            }
        }

    /** Every room a player is still seated in — how a `SessionInvalidated` finds its target (D8). */
    fun activeRoomsOf(playerId: String): List<UUID> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """select room_id from rooms
                   where status in ('WAITING', 'IN_PROGRESS') and players @> ?::jsonb""",
            ).use { statement ->
                statement.setString(1, """["$playerId"]""")
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(UUID.fromString(rows.getString(1))) }
                }
            }
        }

    private fun insertEvents(
        connection: Connection,
        roomId: UUID,
        baseSequence: Int,
        events: List<Event>,
        correlationId: String?,
    ) {
        connection.prepareStatement(
            "insert into room_events (room_id, sequence_number, type, payload, correlation_id) values (?, ?, ?, ?, ?)",
        ).use { statement ->
            events.forEachIndexed { index, event ->
                statement.setObject(1, roomId)
                statement.setInt(2, baseSequence + index + 1)
                statement.setString(3, eventType(event))
                statement.setObject(4, jsonb(encodeEvent(event)))
                statement.setString(5, correlationId)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertOutbox(
        connection: Connection,
        roomId: UUID,
        baseSequence: Int,
        events: List<Event>,
        correlationId: String?,
    ) {
        connection.prepareStatement(
            "insert into outbox (room_id, sequence_number, topic, event_type, payload, correlation_id) values (?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            events.forEachIndexed { index, event ->
                statement.setObject(1, roomId)
                statement.setInt(2, baseSequence + index + 1)
                statement.setString(3, topicFor(event))
                statement.setString(4, eventType(event))
                statement.setObject(5, jsonb(publicPayload(event)))
                statement.setString(6, correlationId)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun upsertProjection(connection: Connection, roomId: UUID, state: RoomState) {
        connection.prepareStatement(
            """insert into rooms (room_id, room_type, status, max_players, player_count, players,
                                  game_number, sequence_number, created_at, updated_at)
               values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, now())
               on conflict (room_id) do update set
                 status = excluded.status,
                 player_count = excluded.player_count,
                 players = excluded.players,
                 game_number = excluded.game_number,
                 sequence_number = excluded.sequence_number,
                 updated_at = now()""",
        ).use { statement ->
            statement.setObject(1, roomId)
            statement.setString(2, state.roomType.name)
            statement.setString(3, state.status.name)
            statement.setInt(4, state.maxPlayers)
            statement.setInt(5, state.players.size)
            statement.setString(6, state.players.joinToString(",", "[", "]") { "\"${it.playerId}\"" })
            state.game?.gameNumber?.let { statement.setInt(7, it) } ?: statement.setNull(7, java.sql.Types.INTEGER)
            statement.setInt(8, state.sequenceNumber)
            statement.setObject(9, java.sql.Timestamp.from(state.createdAt))
            statement.executeUpdate()
        }
    }

    private fun insertIdempotency(connection: Connection, roomId: UUID, record: IdempotentCreate) {
        connection.prepareStatement(
            "insert into idempotency_keys (key, player_id, room_id, response) values (?, ?, ?, ?::jsonb)",
        ).use { statement ->
            statement.setString(1, record.key)
            statement.setString(2, record.playerId)
            statement.setObject(3, roomId)
            statement.setString(4, record.response)
            statement.executeUpdate()
        }
    }

    private fun jsonb(value: JsonObject): PGobject = PGobject().apply {
        type = "jsonb"
        this.value = value.toString()
    }
}

/** Load, decide, append — retried on a lost race so a concurrent writer costs a retry, not an error. */
fun RoomState.after(events: List<Event>): RoomState = events.fold(this, ::evolve)
