// The transactional outbox's second half. room-gameplay has written events and outbox rows in one
// transaction since P3, and until now nothing read them — every row in that table had
// `published_at IS NULL`, by design, waiting for the process that owns the publish.
//
// It is deliberately dull: read committed rows in id order, hand them to Kafka, mark them. All the
// interesting guarantees were bought upstream by the transaction; this just has to not lose them.

package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

const service = "outbox-relay"

const defaultPort = "8088"

// route is a pure, testable function mapping (method, path) to an HTTP status
// and a response body string.
func route(method, path string) (int, string) {
	switch {
	case path == "/health" && method == http.MethodGet:
		return http.StatusOK, `{"status":"ok","service":"` + service + `"}`
	default:
		return http.StatusNotFound, `{"status":"not_found","service":"` + service + `"}`
	}
}

func handler(w http.ResponseWriter, r *http.Request) {
	status, body := route(r.Method, r.URL.Path)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(body))
}

type config struct {
	port         string
	databaseURL  string
	kafkaBrokers string
	pollInterval time.Duration
	batchSize    int
	source       sourceConfig
}

func configFromEnv() config {
	defaults := roomGameplayDefaults()
	return config{
		port: env("PORT", defaultPort),
		// The producer's own database and login role: an outbox belongs to the bounded context that
		// writes it, and architecture §1 names this service as its only direct reader. Since P7 a
		// second copy of this binary drains the tournament's outbox, so the connection is
		// configuration — with room-gameplay's values as the defaults, so the running Deployment
		// needs no edit to keep working.
		databaseURL: fmt.Sprintf(
			"postgres://%s:%s@%s:%s/%s",
			env("DATABASE_USER", "room_gameplay"),
			databasePassword(),
			env("DATABASE_HOST", "localhost"),
			env("DATABASE_PORT", "5432"),
			env("DATABASE_NAME", "room_gameplay"),
		),
		kafkaBrokers: env("KAFKA_BROKERS", "localhost:9092"),
		pollInterval: envDuration("POLL_INTERVAL_MS", time.Second),
		batchSize:    envInt("BATCH_SIZE", 200),
		source: sourceConfig{
			source:     env("EVENT_SOURCE", defaults.source),
			typePrefix: env("CE_TYPE_PREFIX", defaults.typePrefix),
			keyColumn:  env("OUTBOX_KEY_COLUMN", defaults.keyColumn),
			bodyField:  env("BODY_ID_FIELD", defaults.bodyField),
		},
	}
}

// A generic name first, so a second instance does not have to pretend to be room-gameplay to read
// its own password; the original name still works, which is what keeps the shipped overlay valid.
func databasePassword() string {
	if v := os.Getenv("DATABASE_PASSWORD"); v != "" {
		return v
	}
	return os.Getenv("ROOM_GAMEPLAY_DB_PASSWORD")
}

func main() {
	cfg := configFromEnv()
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	pool, err := pgxpool.New(ctx, cfg.databaseURL)
	if err != nil {
		logLine("error", "startup-failed", map[string]any{"error": err.Error()})
		os.Exit(1)
	}
	defer pool.Close()

	// Refuse to start on a key column that is not a plain identifier, rather than build a query out
	// of it and find out later.
	store, err := newOutbox(pool, cfg.source.keyColumn)
	if err != nil {
		logLine("error", "startup-failed", map[string]any{"error": err.Error()})
		os.Exit(1)
	}

	writer := newKafkaPublisher(cfg.kafkaBrokers, cfg.source)
	defer func() { _ = writer.Close() }()

	r := &relay{
		store:     store,
		publisher: writer,
		interval:  cfg.pollInterval,
		batchSize: cfg.batchSize,
	}

	mux := http.NewServeMux()
	mux.Handle("/metrics", promhttp.Handler())
	mux.HandleFunc("/", handler)
	server := &http.Server{Addr: ":" + cfg.port, Handler: mux, ReadHeaderTimeout: 5 * time.Second}

	go func() {
		// The source is logged because two copies of this binary now run side by side, and "which
		// outbox is this one draining" is the first question when one of them looks idle.
		logLine("info", "listening", map[string]any{
			"port": cfg.port, "brokers": cfg.kafkaBrokers, "batchSize": cfg.batchSize,
			"source": cfg.source.source, "keyColumn": cfg.source.keyColumn,
		})
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logLine("error", "listen-failed", map[string]any{"error": err.Error()})
			os.Exit(1)
		}
	}()

	r.run(ctx)

	shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = server.Shutdown(shutdown)
	logLine("info", "stopped", nil)
}

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func envInt(key string, fallback int) int {
	var v int
	if _, err := fmt.Sscanf(os.Getenv(key), "%d", &v); err != nil || v <= 0 {
		return fallback
	}
	return v
}

func envDuration(key string, fallback time.Duration) time.Duration {
	if ms := envInt(key, 0); ms > 0 {
		return time.Duration(ms) * time.Millisecond
	}
	return fallback
}
