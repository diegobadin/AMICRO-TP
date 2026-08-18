import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole tournament, end to end, with a real database and a fake room-gameplay: registration →
 * rounds → champion, plus the two things a fake cannot lie about — that the outbox fills in the same
 * transaction as the log, and that a redelivered room result is refused by a primary key.
 */
class TournamentsTest {

    private lateinit var dataSource: HikariDataSource
    private lateinit var store: Store
    private lateinit var rooms: FakeRooms

    @BeforeTest
    fun setUp() {
        dataSource = freshDatabase()
        store = Store(dataSource)
        rooms = FakeRooms()
    }

    @AfterTest
    fun tearDown() = dataSource.close()

    private fun tournaments(provisioner: RoomProvisioner = rooms) = Tournaments(store, provisioner, testConfig)

    private fun openTournament(t: Tournaments = tournaments()): UUID {
        val outcome = t.create(null) as Outcome.Ok
        return UUID.fromString(outcome.state.tournamentId)
    }

    private fun register(t: Tournaments, id: UUID, vararg players: String) =
        players.forEach { t.submit(id, RegisterPlayer(it), null) }

    private fun saga(t: Tournaments) = SagaHandler(t)

    private fun matchCompleted(roomId: String, advancing: List<String>, seq: Int = 40): JsonObject = buildJsonObject {
        put("type", "MatchCompleted")
        put("roomId", roomId)
        put("sequenceNumber", seq)
        putJsonArray("advancingPlayers") { advancing.forEach { add(JsonPrimitive(it)) } }
    }

    private fun headers(roomId: String, seq: Int = 40, type: String = "MatchCompleted") = mapOf(
        "ce-id" to "$roomId:$seq",
        "ce-type" to "com.unoarena.room.$type.v1",
    )

    @Test
    fun `the threshold starts the tournament and provisions its first round`() {
        val t = tournaments()
        val id = openTournament(t)

        register(t, id, ALICE, BOB, CAROL)
        assertTrue(rooms.requests.isEmpty(), "three players is not a tournament")

        register(t, id, DAVE)

        val state = t.load(id).state
        assertEquals(TournamentStatus.IN_PROGRESS, state.status)
        assertEquals(2, rooms.requests.size, "four players, rooms of two")
        assertEquals(listOf(ALICE, BOB), rooms.requests[0].players, "seeded in registration order")
        assertEquals(1, rooms.requests[0].advanceCount)
        assertEquals(2, state.round(1)!!.rooms.size)
    }

    @Test
    fun `a full tournament reaches a champion`() {
        val t = tournaments()
        val id = openTournament(t)
        register(t, id, ALICE, BOB, CAROL, DAVE)

        val roundOne = t.load(id).state.round(1)!!.rooms
        roundOne.forEach { room ->
            saga(t).handle(headers(room.roomId), matchCompleted(room.roomId, listOf(room.players.first())))
        }

        val afterRoundOne = t.load(id).state
        assertEquals(2, afterRoundOne.rounds.size, "the survivors were seated in a second round")
        val finalRoom = afterRoundOne.round(2)!!.rooms.single()
        assertTrue(afterRoundOne.round(2)!!.isFinal)
        assertEquals(listOf(ALICE, CAROL), finalRoom.players)

        saga(t).handle(headers(finalRoom.roomId), matchCompleted(finalRoom.roomId, listOf(CAROL)))

        val done = t.load(id).state
        assertEquals(TournamentStatus.COMPLETED, done.status)
        assertEquals(CAROL, done.champion)
        assertEquals(4, done.finalPlacements.size)
    }

    /**
     * The trap P6 paid a drill for. `ce-type` is `com.unoarena.room.MatchCompleted.v1`, and a
     * consumer comparing that against the bare catalog name skips every event while looking
     * perfectly healthy — the tournament would sit there with rounds that never complete.
     */
    @Test
    fun `the event name comes from the body, not from the CloudEvents type header`() {
        // The case that discriminates: when the two disagree, the body is the contract. A header
        // that merely *agrees* proves nothing — comparing an unwrapped `ce-type` gives the same
        // answer, so a test where they always match cannot fail on the mistake it exists to catch.
        assertEquals(
            "MatchCompleted",
            eventName(mapOf("ce-type" to "com.unoarena.room.GameCompleted.v1"), matchCompleted("r", emptyList())),
        )
        // The raw URI is never the answer — that comparison is what silently skipped everything in P6.
        assertTrue(
            eventName(mapOf("ce-type" to "com.unoarena.room.MatchCompleted.v1"), matchCompleted("r", emptyList()))
                .none { it == '.' },
        )
        // And when the body somehow has no `type`, the header is unwrapped rather than dropped.
        assertEquals(
            "MatchCompleted",
            eventName(
                mapOf("ce-type" to "com.unoarena.room.MatchCompleted.v1"),
                buildJsonObject { put("roomId", "r") },
            ),
        )
        assertEquals("", eventName(emptyMap(), buildJsonObject { put("roomId", "r") }))
    }

    /**
     * The same rule, through the handler rather than the parser: a body that says `MatchCompleted`
     * is acted on even when the header says otherwise, because the header is routing metadata and
     * the body is the contract.
     */
    @Test
    fun `a room verdict is recorded on what the body says it is`() {
        val t = tournaments()
        val id = openTournament(t)
        register(t, id, ALICE, BOB, CAROL, DAVE)
        val room = t.load(id).state.round(1)!!.rooms.first()

        val outcome = saga(t).handle(
            mapOf("ce-id" to "${room.roomId}:40", "ce-type" to "com.unoarena.room.SomethingElse.v1"),
            matchCompleted(room.roomId, listOf(ALICE)),
        )

        assertEquals("recorded", outcome)
        assertEquals(listOf(ALICE), t.load(id).state.round(1)!!.rooms.first().advancing)
    }

    @Test
    fun `a casual room's events are not this service's business`() {
        val t = tournaments()
        openTournament(t)
        val strangerRoom = UUID.randomUUID().toString()

        val outcome = saga(t).handle(headers(strangerRoom), matchCompleted(strangerRoom, listOf(ALICE)))

        assertEquals("not_ours", outcome, "every casual game in the system lands on this topic too")
    }

    @Test
    fun `a redelivered room result is recorded once`() {
        val t = tournaments()
        val id = openTournament(t)
        register(t, id, ALICE, BOB, CAROL, DAVE)
        val room = t.load(id).state.round(1)!!.rooms.first()

        val first = saga(t).handle(headers(room.roomId), matchCompleted(room.roomId, listOf(ALICE)))
        val second = saga(t).handle(headers(room.roomId), matchCompleted(room.roomId, listOf(ALICE)))

        assertEquals("recorded", first)
        assertEquals("duplicate", second, "the ce-id is the dedup key and it has been seen")
        assertEquals(1, t.load(id).state.round(1)!!.rooms.count { it.reported })
    }

    /** §6.8.5: a room the clock closed advances nobody, and the round still has to close. */
    @Test
    fun `an expired room closes its round with nobody advancing`() {
        val t = tournaments()
        val id = openTournament(t)
        register(t, id, ALICE, BOB, CAROL, DAVE)
        val roundOne = t.load(id).state.round(1)!!.rooms

        saga(t).handle(headers(roundOne[0].roomId), matchCompleted(roundOne[0].roomId, listOf(ALICE)))
        saga(t).handle(
            headers(roundOne[1].roomId, type = "RoomExpired"),
            buildJsonObject {
                put("type", "RoomExpired")
                put("roomId", roundOne[1].roomId)
                put("reason", "waiting_timeout")
            },
        )

        val state = t.load(id).state
        assertTrue(state.round(1)!!.complete, "the round closed even though one room never played")
        assertEquals(listOf(ALICE), state.round(1)!!.survivors)
        assertEquals(TournamentStatus.COMPLETED, state.status, "one survivor has nobody left to play")
    }

    @Test
    fun `the outbox fills in the same transaction as the log`() {
        val t = tournaments()
        val id = openTournament(t)
        register(t, id, ALICE, BOB, CAROL, DAVE)

        val logged = rows("select type from tournament_events where tournament_id = '$id' order by sequence_number")
        val published = rows("select event_type from outbox where tournament_id = '$id' order by sequence_number")

        assertEquals(logged, published, "every event is on its way out, and nothing else is")
        assertTrue(logged.contains("TournamentCreated"))
        assertTrue(logged.contains("TournamentStarted"))
        assertTrue(logged.contains("RoundStarted"))
        assertEquals(
            0,
            count("select count(*) from outbox where published_at is not null"),
            "nothing has drained it yet — that is P7's second relay, and this is the seam it plugs into",
        )
    }

    @Test
    fun `the outbox names the tournament topic and keys on the tournament`() {
        val t = tournaments()
        val id = openTournament(t)

        assertEquals(listOf(OUTBOX_TOPIC), rows("select distinct topic from outbox where tournament_id = '$id'"))
        assertEquals(
            1,
            count("select count(*) from outbox where tournament_id = '$id'"),
            "the key column is the tournament id, which is what the relay partitions on",
        )
    }

    @Test
    fun `the round rooms index is what turns a room id into a round`() {
        val t = tournaments()
        val id = openTournament(t)
        register(t, id, ALICE, BOB, CAROL, DAVE)
        val room = t.load(id).state.round(1)!!.rooms.first()

        val located = store.locate(room.roomId)
        assertNotNull(located)
        assertEquals(id, located.tournamentId)
        assertEquals(1, located.roundNumber)
        assertNull(store.locate(UUID.randomUUID().toString()), "a room we never provisioned is not ours")
    }

    /**
     * The crash case §7.4.2 calls "partial round advancement": rooms created, `RoundStarted` never
     * written. The retry asks for the same rooms under the same idempotency keys and gets the same
     * ids back, so the round starts with the rooms that already exist rather than a second set.
     */
    @Test
    fun `a round interrupted between provisioning and the append is finished by the reconciler`() {
        val t = tournaments()
        val id = openTournament(t)
        register(t, id, ALICE, BOB, CAROL)

        // Cross the threshold with provisioning broken, so the rooms are made but the round is not.
        val breaking = Tournaments(store, FakeRooms(failFrom = 1), testConfig)
        runCatching { breaking.submit(id, RegisterPlayer(DAVE), null) }

        assertEquals(TournamentStatus.IN_PROGRESS, t.load(id).state.status)
        assertTrue(t.load(id).state.rounds.isEmpty(), "the round was never announced")

        assertEquals(1, t.reconcile(), "the sweep finishes what the crash interrupted")
        val state = t.load(id).state
        assertEquals(1, state.rounds.size)
        assertEquals(2, state.round(1)!!.rooms.size)

        assertEquals(0, t.reconcile(), "and a second sweep has nothing to do")
    }

    @Test
    fun `a bracket is readable for a finished tournament`() {
        val t = tournaments()
        val id = openTournament(t)
        register(t, id, ALICE, BOB, CAROL, DAVE)
        t.load(id).state.round(1)!!.rooms.forEach { room ->
            saga(t).handle(headers(room.roomId), matchCompleted(room.roomId, listOf(room.players.first())))
        }

        val bracket = t.bracket(id)
        assertEquals(3, bracket.size, "two rooms in round one, one final")
        assertTrue(bracket.filter { it.roundNumber == 1 }.all { it.advancing != null })
        assertTrue(bracket.single { it.isFinal }.roundNumber == 2)
    }

    /**
     * The P7 drill's most expensive finding: four bots reported a successful registration and only
     * three existed, so the threshold was never reached and nothing anywhere said why. Losing every
     * attempt at a sequence number is a real outcome and must be reported as one — a client that is
     * told "created" for a write that did not happen cannot retry, because it does not know it
     * needs to.
     */
    @Test
    fun `a command that loses every race says so instead of reporting success`() {
        val t = tournaments()
        val id = openTournament(t)

        // Zero attempts is the same condition as exhausting them, without racing anything.
        val outcome = t.submit(id, RegisterPlayer(ALICE), null, attempts = 0)

        assertTrue(outcome is Outcome.Contended, "a write that did not happen is not an Ok")
        assertTrue(t.load(id).state.registered.isEmpty(), "and nothing was written")
    }

    @Test
    fun `four players registering at once all end up registered`() {
        val t = tournaments()
        val id = openTournament(t)

        val threads = listOf(ALICE, BOB, CAROL, DAVE).map { player ->
            Thread {
                // What the CLI does with a 409: ask again, because nothing was recorded.
                repeat(10) {
                    if (t.submit(id, RegisterPlayer(player), null) !is Outcome.Contended) return@Thread
                    Thread.sleep(50)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(20_000) }

        val state = t.load(id).state
        assertEquals(4, state.registered.size, "every player who was told they registered is in: ${state.registered}")
        assertEquals(TournamentStatus.IN_PROGRESS, state.status, "and the threshold was reached")
    }

    private fun rows(sql: String): List<String> = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    private fun count(sql: String): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> rows.next(); rows.getInt(1) }
        }
    }
}
