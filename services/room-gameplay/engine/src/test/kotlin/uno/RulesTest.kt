package uno

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The behaviour contract of requirements.md, case by case. */
class RulesTest {

    private val ab = listOf("a", "b")

    private fun play(state: RoomState, command: Command, at: java.time.Instant = T0, seed: Long = 7L) =
        decide(state, command, at, seed)

    // ---- invariant 12: wild colour declaration

    @Test
    fun `a wild without a declared colour is rejected, never defaulted`() {
        val state = position(mapOf("a" to hand("WILD", "R5"), "b" to hand("B1")), top = card("G3"))
        assertEquals(Rejection.WILD_NEEDS_COLOR, play(state, PlayCard("a", card("WILD"))).rejection())
        assertEquals(
            Rejection.WILD_NEEDS_COLOR,
            play(state, PlayCard("a", card("WILD"), Color.WILD)).rejection(),
        )
    }

    @Test
    fun `the active colour is set the instant a wild is accepted`() {
        val state = position(mapOf("a" to hand("WILD", "R5"), "b" to hand("B1")), top = card("G3"))
        val decision = play(state, PlayCard("a", card("WILD"), Color.BLUE))
        assertTrue(decision is Decision.Accepted)
        assertEquals(Color.BLUE, decision.events.fold(state, ::evolve).game!!.activeColor)
    }

    @Test
    fun `a colour declared on a plain card is rejected rather than ignored`() {
        val state = position(mapOf("a" to hand("G5"), "b" to hand("B1")), top = card("G3"))
        assertEquals(
            Rejection.COLOR_ON_NON_WILD,
            play(state, PlayCard("a", card("G5"), Color.BLUE)).rejection(),
        )
    }

    // ---- invariant 14: first card rule

    @Test
    fun `an initial skip skips the first player`() {
        val seed = seedDealing(ab) { it.face == Face.SKIP }
        val table = Table(ab, 0).also { it.send(CreateRoom("room-1", "a", maxPlayers = 2)) }
        val decision = decide(table.state, JoinRoom("b"), T0, seed, table.config)
        val skipped = decision.all<TurnSkipped>().single()
        assertEquals("first_card_effect", skipped.reason)
        assertEquals("a", skipped.skippedPlayerId)
        assertEquals("b", decision.events.fold(table.state, ::evolve).game!!.currentPlayer)
    }

    @Test
    fun `an initial draw two makes the first player draw and lose the turn`() {
        val seed = seedDealing(ab) { it.face == Face.DRAW_TWO }
        val table = Table(ab, 0).also { it.send(CreateRoom("room-1", "a", maxPlayers = 2)) }
        val decision = decide(table.state, JoinRoom("b"), T0, seed, table.config)
        val forced = decision.all<ForcedDraw>().single()
        assertEquals("a", forced.targetPlayerId)
        assertEquals(2, forced.cardCount)
        assertEquals(9, decision.events.fold(table.state, ::evolve).game!!.hands.getValue("a").size)
    }

    @Test
    fun `an initial reverse skips the first player in a two-player game`() {
        val seed = seedDealing(ab) { it.face == Face.REVERSE }
        val table = Table(ab, 0).also { it.send(CreateRoom("room-1", "a", maxPlayers = 2)) }
        val decision = decide(table.state, JoinRoom("b"), T0, seed, table.config)
        assertNotNull(decision.event<DirectionReversed>())
        assertEquals("b", decision.events.fold(table.state, ::evolve).game!!.currentPlayer)
    }

    @Test
    fun `a wild draw four is buried rather than starting the discard pile`() {
        // The deal re-draws past it, and the buried card stays in the game.
        for (seed in 0L..3000L) {
            val dealt = deal(ab, seed, STARTING_HAND_SIZE)
            assertFalse(dealt.discard.last().face == Face.WILD_DRAW_FOUR)
            val all = dealt.hands.values.flatMap { it.cards } + dealt.deck.cards + dealt.discard
            assertEquals(108, all.size)
        }
    }

    @Test
    fun `an initial wild starts on a colour a player can match`() {
        val seed = seedDealing(ab) { it.face == Face.WILD }
        val table = Table(ab, 0).also { it.send(CreateRoom("room-1", "a", maxPlayers = 2)) }
        val decision = decide(table.state, JoinRoom("b"), T0, seed, table.config)
        val started = decision.event<GameStarted>()!!
        assertTrue(started.initialColor.isPlayable)
        assertEquals(started.initialColor, decision.events.fold(table.state, ::evolve).game!!.activeColor)
    }

    // ---- action cards (invariant 11)

    @Test
    fun `a skip card passes over the next player`() {
        val state = position(
            mapOf("a" to hand("RSKIP", "R1"), "b" to hand("B1"), "c" to hand("G1")),
            top = card("R3"),
        )
        val decision = play(state, PlayCard("a", card("RSKIP")))
        assertEquals("skip_card", decision.all<TurnSkipped>().single().reason)
        assertEquals("c", decision.event<CardPlayed>()!!.nextPlayerId)
    }

    @Test
    fun `a draw two makes the next player draw two and lose the turn`() {
        val state = position(
            mapOf("a" to hand("R+2", "R1"), "b" to hand("B1"), "c" to hand("G1")),
            top = card("R3"),
        )
        val decision = play(state, PlayCard("a", card("R+2")))
        val forced = decision.all<ForcedDraw>().single()
        assertEquals("b" to 2, forced.targetPlayerId to forced.cardCount)
        assertEquals("draw_two", forced.reason)
        assertEquals("c", decision.event<CardPlayed>()!!.nextPlayerId)
    }

    @Test
    fun `a wild draw four makes the next player draw four`() {
        val state = position(
            mapOf("a" to hand("WILD+4", "R1"), "b" to hand("B1"), "c" to hand("G1")),
            top = card("R3"),
        )
        val decision = play(state, PlayCard("a", card("WILD+4"), Color.GREEN))
        assertEquals(4, decision.all<ForcedDraw>().single().cardCount)
        assertEquals("c", decision.event<CardPlayed>()!!.nextPlayerId)
    }

    @Test
    fun `a reverse in a two-player game keeps the turn with the player who played it`() {
        val state = position(mapOf("a" to hand("RREV", "R1"), "b" to hand("B1")), top = card("R3"))
        val decision = play(state, PlayCard("a", card("RREV")))
        assertEquals("reverse_2p", decision.all<TurnSkipped>().single().reason)
        assertEquals("a", decision.event<CardPlayed>()!!.nextPlayerId)
    }

    @Test
    fun `a reverse with more than two players turns the direction around`() {
        val state = position(
            mapOf("a" to hand("RREV", "R1"), "b" to hand("B1"), "c" to hand("G1")),
            top = card("R3"),
        )
        val decision = play(state, PlayCard("a", card("RREV")))
        assertEquals(Direction.COUNTER_CLOCKWISE, decision.event<DirectionReversed>()!!.newDirection)
        assertEquals("c", decision.event<CardPlayed>()!!.nextPlayerId)
    }

    // ---- turn enforcement and legality (invariants 2, 3)

    @Test
    fun `playing out of turn is rejected`() {
        val state = position(mapOf("a" to hand("R5"), "b" to hand("B1")), top = card("R3"))
        assertEquals(Rejection.NOT_YOUR_TURN, play(state, PlayCard("b", card("B1"))).rejection())
    }

    @Test
    fun `playing a card that is not in hand is rejected`() {
        val state = position(mapOf("a" to hand("R5"), "b" to hand("B1")), top = card("R3"))
        assertEquals(Rejection.CARD_NOT_IN_HAND, play(state, PlayCard("a", card("R9"))).rejection())
    }

    @Test
    fun `playing a card that matches nothing is rejected`() {
        val state = position(mapOf("a" to hand("B9"), "b" to hand("B1")), top = card("R3"))
        assertEquals(Rejection.ILLEGAL_PLAY, play(state, PlayCard("a", card("B9"))).rejection())
    }

    @Test
    fun `passing without drawing first is rejected`() {
        val state = position(mapOf("a" to hand("B9"), "b" to hand("B1")), top = card("R3"))
        assertEquals(Rejection.MUST_DRAW_BEFORE_PASSING, play(state, PassTurn("a")).rejection())
    }

    @Test
    fun `drawing twice in one turn is rejected`() {
        val state = position(mapOf("a" to hand("B9"), "b" to hand("B1")), top = card("R3"))
        val after = play(state, DrawCard("a")).events.fold(state, ::evolve)
        assertEquals(Rejection.ALREADY_DREW_THIS_TURN, play(after, DrawCard("a")).rejection())
    }

    // ---- Uno! call and challenge (invariants 4, 5)

    @Test
    fun `calling uno then drawing leaves the player vulnerable again`() {
        val state = position(mapOf("a" to hand("R5", "B9"), "b" to hand("B1")), top = card("R3"))
        val called = play(state, CallUno("a")).events.fold(state, ::evolve)
        assertTrue(called.game!!.hands.getValue("a").hasCalledUno)
        val drawn = play(called, DrawCard("a")).events.fold(called, ::evolve)
        assertFalse(drawn.game!!.hands.getValue("a").hasCalledUno, "a draw has to clear the call")
    }

    @Test
    fun `a challenge against a player who did not call costs them two cards`() {
        val state = position(mapOf("a" to hand("R5", "B9"), "b" to hand("B1")), top = card("R3"))
        val played = play(state, PlayCard("a", card("R5"))).events.fold(state, ::evolve)
        assertNotNull(played.game!!.challengeWindow)

        val decision = play(played, ChallengeUno("b", "a"))
        val resolved = decision.event<UnoChallengeResolved>()!!
        assertTrue(resolved.challengeSucceeded)
        assertEquals("a", resolved.penaltyPlayerId)
        assertEquals(2, resolved.penaltyCardCount)
        val after = decision.events.fold(played, ::evolve)
        assertEquals(3, after.game!!.hands.getValue("a").size)
        assertNull(after.game!!.challengeWindow, "resolving the challenge closes the window")
    }

    @Test
    fun `a challenge against a player who did call is refused, not penalised`() {
        val state = position(mapOf("a" to hand("R5", "B9"), "b" to hand("B1")), top = card("R3"))
        val played = play(state, PlayCard("a", card("R5"), callingUno = true)).events.fold(state, ::evolve)
        val decision = play(played, ChallengeUno("b", "a"))
        assertEquals(Rejection.CHALLENGE_NOT_VALID, decision.rejection())
        assertTrue(decision.events.isEmpty(), "a refused challenge must not touch the log")
    }

    @Test
    fun `calling uno after the window opened still saves the player`() {
        val state = position(mapOf("a" to hand("R5", "B9"), "b" to hand("B1")), top = card("R3"))
        val played = play(state, PlayCard("a", card("R5"))).events.fold(state, ::evolve)
        val saved = play(played, CallUno("a")).events.fold(played, ::evolve)
        assertEquals(Rejection.CHALLENGE_NOT_VALID, play(saved, ChallengeUno("b", "a")).rejection())
    }

    @Test
    fun `a challenge with no open window is refused`() {
        val state = position(mapOf("a" to hand("R5", "B9"), "b" to hand("B1")), top = card("R3"))
        assertEquals(Rejection.NO_OPEN_CHALLENGE, play(state, ChallengeUno("b", "a")).rejection())
    }

    @Test
    fun `the window closes on its own five seconds later`() {
        val state = position(mapOf("a" to hand("R5", "B9"), "b" to hand("B1")), top = card("R3"))
        val played = play(state, PlayCard("a", card("R5"))).events.fold(state, ::evolve)
        val late = T0.plusSeconds(CHALLENGE_WINDOW_SECONDS + 1)
        val decision = play(played, ChallengeUno("b", "a"), at = late)
        assertEquals(Rejection.NO_OPEN_CHALLENGE, decision.rejection())
        assertEquals("timeout", decision.all<ChallengeWindowClosed>().single().reason)
    }

    // ---- deck recycle (§3.2.4)

    @Test
    fun `an exhausted deck recycles the discard pile except its top card`() {
        val buried = listOf(card("R1"), card("R2"), card("R4"), card("G7"))
        val state = position(
            mapOf("a" to hand("B9"), "b" to hand("B1")),
            top = card("R3"),
            deck = emptyList(),
            buried = buried,
        )
        val decision = play(state, DrawCard("a"))
        val recycled = decision.all<DeckRecycled>().single()
        assertEquals(buried.size, recycled.newDeckSize)

        val after = decision.events.fold(state, ::evolve)
        assertEquals(listOf(card("R3")), after.game!!.discard, "the top card stays on the pile")
        assertEquals(buried.size - 1, after.game!!.deck.size, "one of the recycled cards was drawn")
        assertEquals(2, after.game!!.hands.getValue("a").size)
    }

    @Test
    fun `a recycle replays to the same deck order`() {
        val buried = listOf(card("R1"), card("R2"), card("R4"), card("G7"), card("Y8"))
        val state = position(
            mapOf("a" to hand("B9"), "b" to hand("B1")),
            top = card("R3"),
            deck = emptyList(),
            buried = buried,
        )
        val decision = play(state, DrawCard("a"))
        val once = decision.events.fold(state, ::evolve)
        val twice = decision.events.fold(state, ::evolve)
        assertEquals(once.game!!.deck.cards, twice.game!!.deck.cards)
    }

    // ---- presence (invariants 7, 8)

    @Test
    fun `a game with fewer than two active players ends with the last one standing`() {
        val state = position(mapOf("a" to hand("R5", "B9"), "b" to hand("B1"), "c" to hand("G1")), top = card("R3"))
        val afterOne = play(state, ForfeitPlayer("b", "left")).events.fold(state, ::evolve)
        assertEquals(GameStatus.IN_PROGRESS, afterOne.game!!.status, "two players is still a game")

        val decision = play(afterOne, ForfeitPlayer("c", "left"))
        val completed = decision.event<GameCompleted>()!!
        assertEquals("a", completed.finishingOrder.first())
        assertTrue(completed.isAbandoned, "a game everyone walked out of is abandoned, not won on merit")
        assertNotNull(decision.event<RoomCompleted>(), "a casual room closes with its only game")
    }

    @Test
    fun `a disconnected player's turn is skipped rather than stalling the game`() {
        val state = position(
            mapOf("a" to hand("R5"), "b" to hand("B1"), "c" to hand("G1")),
            top = card("R3"),
        )
        val disconnected = play(state, DisconnectPlayer("b", "session_superseded")).events.fold(state, ::evolve)
        val decision = play(disconnected, PlayCard("a", card("R5")))
        assertEquals("c", decision.event<CardPlayed>()!!.nextPlayerId, "b is disconnected and gets passed over")
    }

    @Test
    fun `a redelivered disconnect does not reopen an expired window`() {
        val state = position(mapOf("a" to hand("R5"), "b" to hand("B1"), "c" to hand("G1")), top = card("R3"))
        val once = play(state, DisconnectPlayer("b", "session_superseded")).events.fold(state, ::evolve)
        val deadline = (once.player("b")!!.connection as ConnectionStatus.Disconnected).deadline
        val again = play(once, DisconnectPlayer("b", "session_superseded"), at = T0.plusSeconds(30))
        assertTrue(again.events.none { it is PlayerDisconnected }, "the second delivery is a no-op")
        assertEquals(deadline, (once.player("b")!!.connection as ConnectionStatus.Disconnected).deadline)
    }

    @Test
    fun `reconnecting inside the window cancels the forfeit`() {
        val state = position(mapOf("a" to hand("R5"), "b" to hand("B1"), "c" to hand("G1")), top = card("R3"))
        val gone = play(state, DisconnectPlayer("b", "session_superseded")).events.fold(state, ::evolve)
        val back = play(gone, ReconnectPlayer("b"), at = T0.plusSeconds(30)).events.fold(gone, ::evolve)
        assertEquals(ConnectionStatus.Connected, back.player("b")!!.connection)

        val late = play(gone, ReconnectPlayer("b"), at = T0.plusSeconds(RECONNECTION_WINDOW_SECONDS + 5))
        assertEquals(Rejection.RECONNECTION_EXPIRED, late.rejection())
    }

    @Test
    fun `an expired reconnection window forfeits the player on the next command`() {
        val state = position(mapOf("a" to hand("R5"), "b" to hand("B1"), "c" to hand("G1")), top = card("R3"))
        val gone = play(state, DisconnectPlayer("b", "session_superseded")).events.fold(state, ::evolve)
        val later = T0.plusSeconds(RECONNECTION_WINDOW_SECONDS + 5)
        val decision = play(gone, PlayCard("a", card("R5")), at = later)
        val forfeited = decision.all<PlayerForfeited>().single()
        assertEquals("b", forfeited.playerId)
        assertEquals("reconnection_timeout", forfeited.reason)
    }

    // ---- turn timer (invariant 13)

    @Test
    fun `an expired turn timer draws for the player and passes`() {
        val state = position(mapOf("a" to hand("B9"), "b" to hand("B1")), top = card("R3"))
        val late = T0.plusSeconds(31)
        val decision = play(state, DrawCard("b"), at = late)
        val timedOut = decision.event<TurnTimedOut>()!!
        assertEquals("a", timedOut.playerId)
        assertEquals("draw_and_pass", timedOut.autoAction)
        // The timeout is settled before the incoming command is judged, so b's draw is accepted.
        assertTrue(decision is Decision.Accepted)
        assertEquals("b", decision.events.fold(state, ::evolve).game!!.currentPlayer)
    }

    @Test
    fun `a timeout after the player already drew only passes`() {
        val state = position(mapOf("a" to hand("B9"), "b" to hand("B1")), top = card("R3"))
        val drew = play(state, DrawCard("a")).events.fold(state, ::evolve)
        val decision = play(drew, DrawCard("b"), at = T0.plusSeconds(31))
        assertEquals("pass", decision.event<TurnTimedOut>()!!.autoAction)
        assertTrue(decision.all<CardDrawn>().none { it.playerId == "a" })
    }

    // ---- room lifecycle

    @Test
    fun `a room that is full refuses another player`() {
        val table = Table(listOf("a", "b"), 1)
        table.send(CreateRoom("room-1", "a", maxPlayers = 2))
        table.send(JoinRoom("b"))
        assertEquals(Rejection.ROOM_ALREADY_STARTED, table.send(JoinRoom("c")).rejection())
    }

    @Test
    fun `joining twice is refused and joining a started room is refused`() {
        val table = Table(listOf("a", "b", "c"), 1, EngineConfig(minPlayers = 3))
        table.send(CreateRoom("room-1", "a", maxPlayers = 3))
        assertEquals(Rejection.ALREADY_JOINED, table.send(JoinRoom("a")).rejection())
        table.send(JoinRoom("b"))
        table.send(JoinRoom("c"))
        assertEquals(RoomStatus.IN_PROGRESS, table.state.status)
        assertEquals(Rejection.ROOM_ALREADY_STARTED, table.send(JoinRoom("d")).rejection())
    }

    @Test
    fun `the game starts by itself once the room has enough players`() {
        val table = Table(listOf("a", "b"), 1)
        table.send(CreateRoom("room-1", "a", maxPlayers = 4))
        assertEquals(RoomStatus.WAITING, table.state.status)
        val decision = table.send(JoinRoom("b"))
        assertNotNull(decision.event<GameStarted>(), "auto-start at minPlayers (E3)")
        assertEquals(7, table.state.game!!.hands.getValue("a").size)
    }

    @Test
    fun `leaving a room that has not started just gives the seat back`() {
        val table = Table(listOf("a", "b", "c"), 1, EngineConfig(minPlayers = 3))
        table.send(CreateRoom("room-1", "a", maxPlayers = 3))
        table.send(JoinRoom("b"))
        assertNotNull(table.send(LeaveRoom("b")).event<PlayerLeft>())
        assertEquals(1, table.state.players.size)
        assertEquals(Rejection.NOT_A_MEMBER, table.send(LeaveRoom("b")).rejection())
    }

    @Test
    fun `leaving a game in progress is a forfeit`() {
        val table = Table(listOf("a", "b", "c"), 1, EngineConfig(minPlayers = 3))
        table.send(CreateRoom("room-1", "a", maxPlayers = 3))
        table.send(JoinRoom("b"))
        table.send(JoinRoom("c"))
        assertNotNull(table.send(LeaveRoom("c")).event<PlayerForfeited>())
    }

    @Test
    fun `a hand's points are what the winner scores against`() {
        assertEquals(0, hand().points)
        assertEquals(5 + 20 + 50, hand("R5", "BSKIP", "WILD").points)
        assertEquals(20, hand("Y+2").points)
    }
}
