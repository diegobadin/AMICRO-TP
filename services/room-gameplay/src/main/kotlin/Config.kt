// Everything the service needs to run comes from the environment, never from a baked-in default
// that would differ between a laptop and the cluster (consigna §6.4). The defaults here are the
// local-development ones; the staging overlay sets the real values.

const val SERVICE = "room-gameplay"

data class Config(
    val port: Int,
    val jwtSecret: String,
    // Shared with identity until P4's gateway owns validation; see CHANGELOG-design.md.
    val minPlayers: Int,
    val turnTimeoutSeconds: Long,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config = Config(
            port = env["PORT"]?.toIntOrNull() ?: 8081,
            jwtSecret = env["IDENTITY_JWT_SECRET"] ?: "dev-secret",
            minPlayers = env["ROOM_MIN_PLAYERS"]?.toIntOrNull() ?: 2,
            turnTimeoutSeconds = env["TURN_TIMEOUT_SECONDS"]?.toLongOrNull() ?: 30,
        )
    }
}
