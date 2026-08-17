// Everything the service needs comes from the environment (consigna §6.4). The defaults are the
// local-development ones; the staging overlay sets the real values.

const val SERVICE = "tournament"

data class Config(
    val port: Int,
    /** The low configurable threshold the exam asks for: this many registrations start a tournament. */
    val minPlayers: Int,
    val roomSize: Int,
    val advanceCount: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String?,
    val kafkaBrokers: String,
    val roomGameplayUrl: String,
    /** The shared secret room-gameplay's `/internal` routes require (P7 D1). */
    val internalToken: String?,
    val reconcileIntervalMs: Long,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config = Config(
            port = env["PORT"]?.toIntOrNull() ?: 8083,
            minPlayers = env["TOURNAMENT_MIN_PLAYERS"]?.toIntOrNull() ?: 4,
            roomSize = env["TOURNAMENT_ROOM_SIZE"]?.toIntOrNull() ?: 2,
            advanceCount = env["TOURNAMENT_ADVANCE_COUNT"]?.toIntOrNull() ?: 1,
            databaseUrl = "jdbc:postgresql://${env["DATABASE_HOST"] ?: "localhost"}:" +
                "${env["DATABASE_PORT"] ?: "5432"}/${env["DATABASE_NAME"] ?: "tournament"}",
            databaseUser = env["DATABASE_USER"] ?: "tournament",
            databasePassword = env["TOURNAMENT_DB_PASSWORD"],
            kafkaBrokers = env["KAFKA_BROKERS"] ?: "localhost:9092",
            roomGameplayUrl = env["ROOM_GAMEPLAY_URL"] ?: "http://localhost:8081",
            internalToken = env["INTERNAL_TOKEN"],
            // The sweep that finishes what a crash interrupted. Short enough that a stuck round is
            // visible within a demo, long enough that an idle cluster is quiet.
            reconcileIntervalMs = env["RECONCILE_INTERVAL_MS"]?.toLongOrNull() ?: 5_000,
        )

        fun tournamentConfig(config: Config) =
            TournamentConfig(config.minPlayers, config.roomSize, config.advanceCount)
    }
}
