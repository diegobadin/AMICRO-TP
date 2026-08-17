import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The aggregate's behaviour contract, decided with no database and no broker. Everything the
 * tournament actually guarantees — a round advances only when every room reports, a redelivery
 * changes nothing, terminal is terminal — is here rather than in an integration test, because
 * these are rules and not plumbing.
 */
class DomainTest {

    private val t0: Instant = Instant.parse("2026-08-17T12:00:00Z")
    private val config = TournamentConfig(minPlayers = 4, roomSize = 2, advanceCount = 1)
    private val id = "11111111-1111-1111-1111-111111111111"

    private fun created(): TournamentState =
        TournamentState(tournamentId = id).after(decide(TournamentState(tournamentId = id), CreateTournament(id, config), t0).events)

    private fun TournamentState.send(command: Command): Pair<TournamentState, Decision> {
        val decision = decide(this, command, t0)
        return after(decision.events) to decision
    }

    private fun registered(count: Int): TournamentState {
        var state = created()
        repeat(count) { state = state.send(RegisterPlayer("p$it")).first }
        return state
    }

    @Test
    fun `a tournament starts itself at the threshold, and not before`() {
        var state = created()
        repeat(3) { state = state.send(RegisterPlayer("p$it")).first }
        assertEquals(TournamentStatus.REGISTRATION, state.status, "three of four is not a tournament yet")

        state = state.send(RegisterPlayer("p3")).first
        assertEquals(TournamentStatus.IN_PROGRESS, state.status, "the fourth registration is the start")
        assertEquals(4, state.registered.size)
    }

    @Test
    fun `registering twice is not an error, and does not seat you twice`() {
        val (state, decision) = registered(2).send(RegisterPlayer("p0"))
        assertTrue(decision is Decision.Accepted)
        assertTrue(decision.events.isEmpty(), "nothing happened, so nothing is written")
        assertEquals(2, state.registered.size)
    }

    @Test
    fun `registration closes when the tournament starts`() {
        val (_, decision) = registered(4).send(RegisterPlayer("late"))
        assertEquals(Rejection.REGISTRATION_CLOSED, (decision as Decision.Rejected).reason)
    }

    @Test
    fun `a round is not complete until every room has reported`() {
        var state = registered(4)
        val rooms = listOf(RoomRef("r1", listOf("p0", "p1")), RoomRef("r2", listOf("p2", "p3")))
        state = state.send(StartRound(1, rooms, isFinal = false)).first

        state = state.send(RecordRoomResult("r1", listOf("p0"))).first
        assertFalse(state.round(1)!!.complete, "one of two rooms is not a round")
        assertEquals(TournamentStatus.IN_PROGRESS, state.status)

        val (after, decision) = state.send(RecordRoomResult("r2", listOf("p2")))
        assertTrue(after.round(1)!!.complete)
        assertTrue(decision.events.any { it is RoundCompleted }, "the last room closes the round")
        assertEquals(listOf("p0", "p2"), after.round(1)!!.survivors)
    }

    /**
     * At-least-once is real: the relay publishes before it marks. A room reporting twice must not
     * advance a round twice, or a bracket grows a round nobody played.
     */
    @Test
    fun `a room that reports twice is recorded once`() {
        var state = registered(4)
        state = state.send(StartRound(1, listOf(RoomRef("r1", listOf("p0", "p1"))), isFinal = false)).first
        state = state.send(RecordRoomResult("r1", listOf("p0"))).first

        val (after, decision) = state.send(RecordRoomResult("r1", listOf("p1")))
        assertTrue(decision.events.isEmpty(), "the second report is not news")
        assertEquals(listOf("p0"), after.round(1)!!.survivors, "and it does not overwrite the first")
    }

    @Test
    fun `a room nobody provisioned is refused`() {
        val (_, decision) = registered(4).send(RecordRoomResult("someone-elses-room", listOf("p0")))
        assertEquals(Rejection.UNKNOWN_ROOM, (decision as Decision.Rejected).reason)
    }

    @Test
    fun `the final room produces a champion and a full placement list`() {
        var state = registered(4)
        state = state.send(
            StartRound(1, listOf(RoomRef("r1", listOf("p0", "p1")), RoomRef("r2", listOf("p2", "p3"))), isFinal = false),
        ).first
        state = state.send(RecordRoomResult("r1", listOf("p0"))).first
        state = state.send(RecordRoomResult("r2", listOf("p2"))).first
        state = state.send(StartRound(2, listOf(RoomRef("r3", listOf("p0", "p2"))), isFinal = true)).first

        val (after, decision) = state.send(RecordRoomResult("r3", listOf("p2")))

        val completed = decision.events.filterIsInstance<TournamentCompleted>().single()
        assertEquals("p2", completed.champion)
        assertEquals(TournamentStatus.COMPLETED, after.status)
        assertEquals("p2", completed.finalPlacements.first(), "the champion places first")
        assertEquals(
            setOf("p0", "p1", "p2", "p3"),
            completed.finalPlacements.toSet(),
            "everyone who registered has a placement",
        )
    }

    /** §6.8.5, at the tournament's end of it: a bracket everybody abandoned still has to finish. */
    @Test
    fun `a final that advances nobody still ends the tournament`() {
        var state = registered(4)
        state = state.send(StartRound(1, listOf(RoomRef("r1", listOf("p0", "p1"))), isFinal = true)).first

        val (after, decision) = state.send(RecordRoomResult("r1", emptyList()))

        val completed = decision.events.filterIsInstance<TournamentCompleted>().single()
        assertNull(completed.champion, "nobody won, and that is an answer")
        assertEquals(TournamentStatus.COMPLETED, after.status)
    }

    @Test
    fun `a round that leaves one survivor ends the tournament even when it was not the final`() {
        var state = registered(4)
        state = state.send(StartRound(1, listOf(RoomRef("r1", listOf("p0", "p1"))), isFinal = false)).first

        val (after, _) = state.send(RecordRoomResult("r1", listOf("p0")))
        assertEquals(TournamentStatus.COMPLETED, after.status, "there is nobody left to play against")
        assertEquals("p0", after.champion)
    }

    @Test
    fun `a completed tournament stays completed`() {
        var state = registered(4)
        state = state.send(StartRound(1, listOf(RoomRef("r1", listOf("p0", "p1"))), isFinal = true)).first
        state = state.send(RecordRoomResult("r1", listOf("p0"))).first

        val (after, decision) = state.send(RecordRoomResult("r1", listOf("p1")))
        assertTrue(decision.events.isEmpty())
        assertEquals("p0", after.champion, "a late redelivery does not crown somebody else")
    }

    @Test
    fun `a replay of the log rebuilds the same tournament`() {
        val events = mutableListOf<Event>()
        var state = TournamentState(tournamentId = id)
        listOf<Command>(
            CreateTournament(id, config),
            RegisterPlayer("p0"), RegisterPlayer("p1"), RegisterPlayer("p2"), RegisterPlayer("p3"),
            StartRound(1, listOf(RoomRef("r1", listOf("p0", "p1")), RoomRef("r2", listOf("p2", "p3"))), false),
            RecordRoomResult("r1", listOf("p0")),
            RecordRoomResult("r2", listOf("p2")),
        ).forEach { command ->
            val decision = decide(state, command, t0)
            events += decision.events
            state = state.after(decision.events)
        }

        assertEquals(state, replay(events, id), "the log is the authority; a rebuild must agree with it")
    }

    // ---- seeding

    @Test
    fun `seeding chunks the field in registration order`() {
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), assignRooms(listOf("a", "b", "c", "d"), 2))
    }

    /**
     * An odd field would otherwise leave a room of one, which room-gameplay refuses outright — so
     * the leftover joins the previous room rather than sitting at a table alone.
     */
    @Test
    fun `an odd field never leaves a room of one`() {
        val rooms = assignRooms(listOf("a", "b", "c"), 2)
        assertEquals(listOf(listOf("a", "b", "c")), rooms)
        assertTrue(assignRooms(listOf("a", "b", "c", "d", "e"), 2).all { it.size >= MIN_ROOM_SIZE })
    }

    @Test
    fun `a field that fits in one room is the final`() {
        assertEquals(listOf(listOf("a", "b")), assignRooms(listOf("a", "b"), 2))
    }

    @Test
    fun `a room never advances everyone in it`() {
        assertEquals(1, advanceCountFor(roomPlayers = 2, config = config))
        assertEquals(2, advanceCountFor(roomPlayers = 3, config = config.copy(advanceCount = 3)))
    }
}
