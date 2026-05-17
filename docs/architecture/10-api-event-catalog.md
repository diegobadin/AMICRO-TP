# API & Event Catalog

> Optional enrichment deliverable (Architecture Checkpoint §7). OpenAPI fragments for critical endpoints and AsyncAPI outline for Kafka events.

---

## 1. REST API — OpenAPI Fragments

### 1.1 Room Gameplay (Critical Endpoints)

```yaml
openapi: "3.1.0"
info:
  title: UnoArena — Room Gameplay API
  version: "1.0.0"

paths:
  /rooms:
    post:
      operationId: createRoom
      summary: Create a new room
      parameters:
        - $ref: "#/components/parameters/IdempotencyKey"
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [roomType, maxPlayers]
              properties:
                roomType: { type: string, enum: [Casual, Tournament] }
                maxPlayers: { type: integer, minimum: 2, maximum: 10 }
      responses:
        "201": { description: Room created, headers: { Location: { schema: { type: string } } } }
        "200": { description: Idempotent replay — room already exists }
        "429": { description: Rate limited }

  /rooms/{roomId}/games/{gameId}/moves:
    post:
      operationId: appendMove
      summary: Append a move (PlayCard, DrawCard, PassTurn, CallUno, ChallengeUno)
      parameters:
        - $ref: "#/components/parameters/RoomId"
        - $ref: "#/components/parameters/GameId"
        - name: If-Match
          in: header
          required: true
          schema: { type: string }
          description: Current sequence number as ETag
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [type]
              properties:
                type:
                  type: string
                  enum: [play_card, draw_card, pass, call_uno, challenge_uno]
                card:
                  type: object
                  properties:
                    color: { type: string, enum: [Red, Yellow, Green, Blue, Wild] }
                    face: { type: string }
                chosenColor: { type: string, enum: [Red, Yellow, Green, Blue] }
                callingUno: { type: boolean }
                targetPlayerId: { type: string, format: uuid }
      responses:
        "201":
          description: Move accepted
          headers:
            Location: { schema: { type: string } }
            ETag: { schema: { type: string } }
          content:
            application/json:
              schema: { $ref: "#/components/schemas/PlayerGameState" }
        "412": { description: Precondition Failed — stale sequence number }
        "428": { description: Precondition Required — missing If-Match }
        "409": { description: Conflict — not your turn, illegal play, etc. }
        "422": { description: Unprocessable — invalid card, missing chosenColor for Wild }
        "429": { description: Rate limited }

  /rooms/{roomId}/games/{gameId}:
    get:
      operationId: getGameState
      summary: Player-scoped game state (for reconnect / hydration)
      parameters:
        - $ref: "#/components/parameters/RoomId"
        - $ref: "#/components/parameters/GameId"
        - name: If-None-Match
          in: header
          required: false
          schema: { type: string }
      responses:
        "200":
          description: Current game state for the requesting player
          headers:
            ETag: { schema: { type: string } }
          content:
            application/json:
              schema: { $ref: "#/components/schemas/PlayerGameState" }
        "304": { description: Not Modified }

components:
  parameters:
    RoomId:
      name: roomId
      in: path
      required: true
      schema: { type: string, format: uuid }
    GameId:
      name: gameId
      in: path
      required: true
      schema: { type: integer, minimum: 1, maximum: 3 }
    IdempotencyKey:
      name: Idempotency-Key
      in: header
      required: true
      schema: { type: string, format: uuid }

  schemas:
    PlayerGameState:
      type: object
      properties:
        roomId: { type: string, format: uuid }
        gameNumber: { type: integer }
        status: { type: string, enum: [InProgress, Completed] }
        hand: { type: array, items: { $ref: "#/components/schemas/Card" } }
        discardTop: { $ref: "#/components/schemas/Card" }
        activeColor: { type: string }
        currentPlayer: { type: string, format: uuid }
        direction: { type: string, enum: [Clockwise, CounterClockwise] }
        players:
          type: array
          items:
            type: object
            properties:
              playerId: { type: string, format: uuid }
              cardCount: { type: integer }
              connectionStatus: { type: string }
        sequenceNumber: { type: integer }
    Card:
      type: object
      properties:
        color: { type: string }
        face: { type: string }
```

### 1.2 Tournament (Critical Endpoints)

```yaml
paths:
  /tournaments:
    post:
      operationId: createTournament
      summary: Create a tournament (admin)
      responses:
        "201": { description: Tournament created }

  /tournaments/{id}/register:
    post:
      operationId: registerPlayer
      summary: Register for a tournament
      responses:
        "201": { description: Registered }
        "409": { description: Already registered or tournament full/started }
    delete:
      operationId: unregisterPlayer
      responses:
        "204": { description: Unregistered }

  /tournaments/{id}/start:
    post:
      operationId: startTournament
      summary: Start tournament (admin)
      responses:
        "202": { description: Tournament starting (round generation async) }
        "409": { description: Already started or insufficient players }
```

---

## 2. AsyncAPI — Kafka Event Catalog

```yaml
asyncapi: "3.0.0"
info:
  title: UnoArena Event Catalog
  version: "1.0.0"
  description: Domain events published to Kafka topics

channels:
  room.public.events:
    address: room.public.events
    messages:
      CardPlayed:
        $ref: "#/components/messages/CardPlayed"
      CardDrawn:
        $ref: "#/components/messages/CardDrawn"
      GameStarted:
        $ref: "#/components/messages/GameStarted"
      PlayerJoined:
        $ref: "#/components/messages/PlayerJoined"
      # ... (all events listed in 01-service-architecture.md §2.3.2)
    bindings:
      kafka:
        partitions: 256
        topicConfiguration:
          retention.ms: 604800000  # 7 days

  room.lifecycle.events:
    address: room.lifecycle.events
    messages:
      GameCompleted:
        $ref: "#/components/messages/GameCompleted"
      MatchCompleted:
        $ref: "#/components/messages/MatchCompleted"
      RoomCompleted:
        $ref: "#/components/messages/RoomCompleted"
      RoomCreated:
        $ref: "#/components/messages/RoomCreated"
    bindings:
      kafka:
        partitions: 256
        topicConfiguration:
          retention.ms: 604800000

  tournament.room-creation:
    address: tournament.room-creation
    messages:
      RoomCreationRequested:
        $ref: "#/components/messages/RoomCreationRequested"
    bindings:
      kafka:
        partitions: 256

  tournament.lifecycle.events:
    address: tournament.lifecycle.events
    messages:
      TournamentStarted:
        $ref: "#/components/messages/TournamentStarted"
      RoundStarted:
        $ref: "#/components/messages/RoundStarted"
      RoomResultRecorded:
        $ref: "#/components/messages/RoomResultRecorded"
      RoundCompleted:
        $ref: "#/components/messages/RoundCompleted"
      TournamentCompleted:
        $ref: "#/components/messages/TournamentCompleted"
    bindings:
      kafka:
        partitions: 64

  identity.session-events:
    address: identity.session-events
    messages:
      SessionInvalidated:
        $ref: "#/components/messages/SessionInvalidated"
    bindings:
      kafka:
        partitions: 64

  ranking.events:
    address: ranking.events
    messages:
      EloUpdated:
        $ref: "#/components/messages/EloUpdated"
      TournamentPlacementUpdated:
        $ref: "#/components/messages/TournamentPlacementUpdated"
    bindings:
      kafka:
        partitions: 64

components:
  messages:
    CardPlayed:
      name: CardPlayed
      headers:
        type: object
        properties:
          ce-specversion: { type: string, const: "1.0" }
          ce-id: { type: string, format: uuid }
          ce-source: { type: string, const: "/room-gameplay" }
          ce-type: { type: string, const: "com.unoarena.room.CardPlayed.v1" }
          ce-time: { type: string, format: date-time }
          ce-subject: { type: string }
          ce-correlationid: { type: string }
      payload:
        type: object
        required: [roomId, playerId, card, playerCardCount, nextPlayerId, sequenceNumber]
        properties:
          roomId: { type: string, format: uuid }
          playerId: { type: string, format: uuid }
          card: { type: object, properties: { color: { type: string }, face: { type: string } } }
          chosenColor: { type: string, nullable: true }
          playerCardCount: { type: integer }
          nextPlayerId: { type: string, format: uuid }
          sequenceNumber: { type: integer }

    GameCompleted:
      name: GameCompleted
      payload:
        type: object
        required: [roomId, roomType, gameNumber, finishingOrder, isAbandoned, completedAt]
        properties:
          roomId: { type: string, format: uuid }
          roomType: { type: string, enum: [Casual, Tournament] }
          gameNumber: { type: integer }
          finishingOrder: { type: array, items: { type: string, format: uuid } }
          cardPointTotals: { type: object, additionalProperties: { type: integer } }
          isAbandoned: { type: boolean }
          completedAt: { type: string, format: date-time }

    MatchCompleted:
      name: MatchCompleted
      payload:
        type: object
        required: [roomId, matchResults, advancingPlayers]
        properties:
          roomId: { type: string, format: uuid }
          matchResults:
            type: object
            additionalProperties:
              type: object
              properties:
                wins: { type: integer }
                cumulativeCardPoints: { type: integer }
          advancingPlayers: { type: array, items: { type: string, format: uuid } }

    RoomCreationRequested:
      name: RoomCreationRequested
      payload:
        type: object
        required: [tournamentId, roundNumber, roomId, assignedPlayers, idempotencyKey]
        properties:
          tournamentId: { type: string, format: uuid }
          roundNumber: { type: integer }
          roomId: { type: string, format: uuid }
          assignedPlayers: { type: array, items: { type: string, format: uuid } }
          idempotencyKey: { type: string }

    SessionInvalidated:
      name: SessionInvalidated
      payload:
        type: object
        required: [playerId, oldSessionId, reason]
        properties:
          playerId: { type: string, format: uuid }
          oldSessionId: { type: string, format: uuid }
          reason: { type: string, enum: [new_login, admin_revoke, logout] }

    EloUpdated:
      name: EloUpdated
      payload:
        type: object
        required: [playerId, oldElo, newElo, delta, gameId]
        properties:
          playerId: { type: string, format: uuid }
          oldElo: { type: integer }
          newElo: { type: integer }
          delta: { type: integer }
          gameId: { type: string }

    # Remaining messages follow the same structure.
    # Full catalog: see docs/design/04-commands-events.md
    CardDrawn: { name: CardDrawn }
    GameStarted: { name: GameStarted }
    PlayerJoined: { name: PlayerJoined }
    RoomCreated: { name: RoomCreated }
    RoomCompleted: { name: RoomCompleted }
    TournamentStarted: { name: TournamentStarted }
    RoundStarted: { name: RoundStarted }
    RoomResultRecorded: { name: RoomResultRecorded }
    RoundCompleted: { name: RoundCompleted }
    TournamentCompleted: { name: TournamentCompleted }
    TournamentPlacementUpdated: { name: TournamentPlacementUpdated }
```

---

## 3. Traceability: API/Event → Design Command/Event Catalog

| API Endpoint / Kafka Event | Maps to (Design Checkpoint) |
|---------------------------|----------------------------|
| `POST /rooms` | Command: `CreateRoom` → Event: `RoomCreated` |
| `POST /rooms/{id}/players/{pid}` | Command: `JoinRoom` → Event: `PlayerJoined` |
| `POST /rooms/{id}/games` | Command: `StartGame` → Event: `GameStarted` |
| `POST /rooms/{id}/games/{gid}/moves` (type=play_card) | Command: `PlayCard` → Events: `CardPlayed`, `ForcedDraw`, `TurnSkipped`, etc. |
| `POST /rooms/{id}/games/{gid}/moves` (type=draw_card) | Command: `DrawCard` → Event: `CardDrawn` |
| `POST /rooms/{id}/games/{gid}/moves` (type=challenge_uno) | Command: `ChallengeUno` → Events: `UnoChallengeIssued`, `UnoChallengeResolved` |
| Kafka: `GameCompleted` | Event: `GameCompleted` (Design §4.1) |
| Kafka: `MatchCompleted` | Event: `MatchCompleted` (Design §4.1) |
| Kafka: `RoomCreationRequested` | Event: `RoomCreationRequested` (Design §4.2) |
| Kafka: `SessionInvalidated` | Event: `SessionInvalidated` (Design §4.4) |
| Kafka: `EloUpdated` | Event: `EloUpdated` (Design §4.3) |
| Kafka: `TournamentCompleted` | Event: `TournamentCompleted` (Design §4.2) |
