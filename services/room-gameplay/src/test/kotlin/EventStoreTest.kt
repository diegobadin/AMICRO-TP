import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import uno.CreateRoom
import uno.Decision
import uno.EngineConfig
import uno.Event
import uno.JoinRoom
import uno.RoomState
import uno.RoomStatus
import uno.decide
import uno.evolve

/**
 * AC-P3.3 and AC-P3.4 against a **real** Postgres. A fake store cannot prove that a failure leaves
 * no rows or that two writers cannot take the same sequence number, so these are not run in the
 * hermetic unit stage — `TEST_DATABASE_URL` points them at the CI service container or at the kind
 * cluster's database, and the suite fails loudly rather than skipping silently when it is unset.
 */
class EventStoreTest {

    private lateinit var dataSource: HikariDataSource
    private lateinit var store: EventStore
    private val roomId: UUID = UUID.randomUUID()

    @BeforeTest
    fun setUp() {
        dataSource = connect()
        migrate(dataSource)
        store = EventStore(dataSource)
    }

    @AfterTest
    fun tearDown() {
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute("drop trigger if exists outbox_fault on outbox")
                it.execute("delete from room_events where room_id = '$roomId'")
                it.execute("delete from outbox where room_id = '$roomId'")
                it.execute("delete from rooms where room_id = '$roomId'")
                it.execute("delete from idempotency_keys where room_id = '$roomId'")
            }
        }
        dataSource.close()
    }

    private fun created(): List<Event> {
        val decision = decide(RoomState(roomId = roomId.toString()), CreateRoom(roomId.toString(), "alice", maxPlayers = 4), NOW, 1L)
        return (decision as Decision.Accepted).events
    }

    @Test
    fun `a committed append is readable back as the same aggregate`() {
        val events = created()
        val state = events.fold(RoomState(roomId = roomId.toString()), ::evolve)
        assertEquals(AppendResult.Committed(events.size), store.append(roomId, 0, events, state, "corr-1"))

        val loaded = store.load(roomId)
        assertTrue(loaded.found)
        assertEquals(state, loaded.state)
    }

    @Test
    fun `events and outbox rows land in the same transaction`() {
        val events = created()
        store.append(roomId, 0, events, events.fold(RoomState(roomId = roomId.toString()), ::evolve), "corr-1")
        assertEquals(events.size, count("room_events"))
        assertEquals(events.size, count("outbox"))
        assertEquals(1, count("rooms"))
    }

    /**
     * AC-P3.3. The fault is injected in the database, not in the code: a trigger that raises on any
     * outbox insert is the closest thing to a real mid-command failure, and it needs no production
     * hook that could itself be wrong.
     */
    @Test
    fun `a failure writing the outbox leaves no events, no outbox rows and no consumed sequence`() {
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute(
                    """create or replace function fail_outbox() returns trigger as $$
                       begin raise exception 'injected outbox failure'; end; $$ language plpgsql""",
                )
                it.execute("create trigger outbox_fault before insert on outbox for each row execute function fail_outbox()")
            }
        }

        val events = created()
        val state = events.fold(RoomState(roomId = roomId.toString()), ::evolve)
        assertFailsWith<java.sql.SQLException> { store.append(roomId, 0, events, state, "corr-1") }

        assertEquals(0, count("room_events"), "the game log must be untouched")
        assertEquals(0, count("outbox"), "no outbox row may survive the rollback")
        assertEquals(0, count("rooms"), "the projection rolls back with everything else")
        assertEquals(0, store.load(roomId).state.sequenceNumber, "the sequence number was never consumed")

        // With the fault removed the very same command succeeds: nothing was half-applied.
        dataSource.connection.use { c -> c.createStatement().use { it.execute("drop trigger outbox_fault on outbox") } }
        assertEquals(AppendResult.Committed(events.size), store.append(roomId, 0, events, state, "corr-1"))
    }

    /** AC-P3.4: exactly one of two writers racing the same sequence number commits. */
    @Test
    fun `two writers reaching for the same sequence number cannot both win`() {
        val create = created()
        val base = create.fold(RoomState(roomId = roomId.toString()), ::evolve)
        store.append(roomId, 0, create, base, "corr-1")

        val config = EngineConfig(minPlayers = 4)
        val bob = (decide(base, JoinRoom("bob"), NOW, 2L, config) as Decision.Accepted).events
        val carol = (decide(base, JoinRoom("carol"), NOW, 3L, config) as Decision.Accepted).events

        val first = store.append(roomId, base.sequenceNumber, bob, base.after(bob), "corr-2")
        val second = store.append(roomId, base.sequenceNumber, carol, base.after(carol), "corr-3")

        assertTrue(first is AppendResult.Committed, "the first writer commits")
        assertEquals(AppendResult.Conflict, second, "the loser is told to reconcile, not silently merged")

        // The loser can reconcile: reload, re-decide against the winner's state, and win the retry.
        val reloaded = store.load(roomId).state
        val retry = (decide(reloaded, JoinRoom("carol"), NOW, 3L, config) as Decision.Accepted).events
        assertTrue(store.append(roomId, reloaded.sequenceNumber, retry, reloaded.after(retry), "corr-3") is AppendResult.Committed)
        assertEquals(listOf("alice", "bob", "carol"), store.load(roomId).state.players.map { it.playerId })
    }

    @Test
    fun `an idempotency key returns the original response instead of a second room`() {
        val events = created()
        val state = events.fold(RoomState(roomId = roomId.toString()), ::evolve)
        assertNull(store.findIdempotent("key-1", "alice"))
        store.append(roomId, 0, events, state, "corr-1", IdempotentCreate("key-1", "alice", """{"roomId":"$roomId"}"""))
        assertEquals("""{"roomId": "$roomId"}""", store.findIdempotent("key-1", "alice"))
        assertNull(store.findIdempotent("key-1", "someone-else"), "a key belongs to the player who used it")
    }

    @Test
    fun `the projection answers the room list and the player-to-rooms lookup`() {
        val events = created()
        store.append(roomId, 0, events, events.fold(RoomState(roomId = roomId.toString()), ::evolve), "corr-1")

        assertNotNull(store.listJoinable().firstOrNull { it.roomId == roomId.toString() })
        assertEquals(listOf(roomId), store.activeRoomsOf("alice"))
        assertEquals(emptyList(), store.activeRoomsOf("nobody"))
    }

    @Test
    fun `a completed room drops out of the joinable list`() {
        val events = created()
        val state = events.fold(RoomState(roomId = roomId.toString()), ::evolve)
        store.append(roomId, 0, events, state.copy(status = RoomStatus.COMPLETED), "corr-1")
        assertNull(store.listJoinable().firstOrNull { it.roomId == roomId.toString() })
    }

    @Test
    fun `the idempotency sweep drops keys older than a day`() {
        val events = created()
        store.append(
            roomId, 0, events, events.fold(RoomState(roomId = roomId.toString()), ::evolve), "corr-1",
            IdempotentCreate("key-old", "alice", """{"roomId":"$roomId"}"""),
        )
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute("update idempotency_keys set created_at = now() - interval '25 hours' where key = 'key-old'")
            }
        }
        assertTrue(sweepIdempotencyKeys(dataSource) >= 1)
        assertNull(store.findIdempotent("key-old", "alice"))
    }

    @Test
    fun `migrating twice is a no-op`() {
        migrate(dataSource)
        migrate(dataSource)
    }

    private fun count(table: String): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement("select count(*) from $table where room_id = ?").use { statement ->
                statement.setObject(1, roomId)
                statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
        }

    private companion object {
        val NOW: java.time.Instant = java.time.Instant.parse("2026-08-08T12:00:00Z")

        fun connect(): HikariDataSource {
            val url = System.getenv("TEST_DATABASE_URL")
                ?: error(
                    "TEST_DATABASE_URL is not set. AC-P3.3 cannot be proved against a fake, so this " +
                        "suite refuses to pass by skipping. Point it at a Postgres, e.g. " +
                        "jdbc:postgresql://localhost:55432/room_gameplay",
                )
            return HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = url
                    username = System.getenv("TEST_DATABASE_USER") ?: "room_gameplay"
                    password = System.getenv("TEST_DATABASE_PASSWORD") ?: "test"
                    maximumPoolSize = 4
                },
            )
        }
    }
}
