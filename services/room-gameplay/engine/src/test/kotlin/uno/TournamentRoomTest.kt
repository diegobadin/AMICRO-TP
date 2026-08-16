package uno

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What makes a tournament room different from a casual one, at the engine level (P7 F2). The match
 * itself — best-of-three, `MatchCompleted`, advancement — is F3; this is the seat-and-start
 * behaviour everything else stands on.
 */
class TournamentRoomTest {

    private val link = TournamentLink(tournamentId = "t-1", roundNumber = 1, advanceCount = 1)

    private fun createRoom(type: RoomType, size: Int) =
        CreateRoom("room-1", "a", type, maxPlayers = size, tournament = if (type == RoomType.TOURNAMENT) link else null)

    /**
     * The casual auto-start (E3) fires at `minPlayers`, which is two. A tournament room of four
     * would therefore deal the cards to the first two arrivals and refuse the other two with
     * ROOM_ALREADY_STARTED — so the auto-start is casual-only and provisioning starts the game.
     */
    @Test
    fun `a tournament room does not start itself when the second player sits down`() {
        val table = Table(listOf("a", "b", "c", "d"), seed = 3)
        table.send(createRoom(RoomType.TOURNAMENT, 4))
        listOf("b", "c", "d").forEach { table.send(JoinRoom(it)) }

        assertNull(table.state.game, "nobody dealt: a tournament room waits to be started")
        assertEquals(RoomStatus.WAITING, table.state.status)
        assertEquals(4, table.state.players.size, "all four got a seat, which is the point")

        table.send(StartGame(null))
        assertNotNull(table.state.game)
        assertEquals(4, table.state.game!!.hands.size, "everyone is holding cards, not just the first two")
    }

    @Test
    fun `a casual room still starts itself at min players`() {
        val table = Table(listOf("a", "b"), seed = 3)
        table.send(createRoom(RoomType.CASUAL, 2))
        table.send(JoinRoom("b"))

        assertNotNull(table.state.game, "P4's one-call `play --casual` depends on this and P7 must not move it")
    }

    @Test
    fun `the tournament link is on the room and survives a replay`() {
        val table = Table(listOf("a", "b"), seed = 3)
        table.send(createRoom(RoomType.TOURNAMENT, 2))

        assertEquals(link, table.state.tournament)

        val replayed = table.log.fold(RoomState(roomId = "room-1")) { state, event -> evolve(state, event) }
        assertEquals(link, replayed.tournament, "the link is in the log, not only in the command")
    }

    @Test
    fun `a casual room carries no tournament link`() {
        val table = Table(listOf("a", "b"), seed = 3)
        table.send(createRoom(RoomType.CASUAL, 2))

        assertNull(table.state.tournament)
        assertEquals(1, table.state.maxGames, "and it plays exactly one game")
    }

    @Test
    fun `a tournament room is a best-of-three`() {
        val table = Table(listOf("a", "b"), seed = 3)
        table.send(createRoom(RoomType.TOURNAMENT, 2))

        assertEquals(3, table.state.maxGames)
    }

    /** The seed-and-start sequence the provisioning endpoint folds into one transaction. */
    @Test
    fun `seating everyone then starting produces one game with every player in it`() {
        val table = Table(listOf("a", "b"), seed = 3)
        table.send(createRoom(RoomType.TOURNAMENT, 2))
        table.send(JoinRoom("b"))
        table.send(StartGame(null))

        val types = table.log.map { it::class.simpleName }
        assertEquals(listOf("RoomCreated", "PlayerJoined", "PlayerJoined", "GameStarted"), types.take(4))
        assertTrue(table.state.status == RoomStatus.IN_PROGRESS)
    }
}
