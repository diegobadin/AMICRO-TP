// One pool for the process. Sized small on purpose: a single kind node runs two JVMs plus the whole
// platform, and a pool bigger than the work queue only moves the wait from the app to Postgres.

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

fun pool(config: Config): DataSource = HikariDataSource(
    HikariConfig().apply {
        jdbcUrl = config.databaseUrl
        username = config.databaseUser
        password = config.databasePassword
        maximumPoolSize = 10
        // A cold cluster is still electing a Postgres primary while this pod starts; failing fast
        // and letting the kubelet retry beats holding a request open against nothing.
        connectionTimeout = 5_000
        initializationFailTimeout = -1
    },
)
