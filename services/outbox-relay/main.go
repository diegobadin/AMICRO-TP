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
}

func configFromEnv() config {
	return config{
		port: env("PORT", defaultPort),
		// room-gameplay's own database and login role: the outbox belongs to that bounded context
		// and architecture §1 names this service as its only direct reader.
		databaseURL: fmt.Sprintf(
			"postgres://%s:%s@%s:%s/%s",
			env("DATABASE_USER", "room_gameplay"),
			os.Getenv("ROOM_GAMEPLAY_DB_PASSWORD"),
			env("DATABASE_HOST", "localhost"),
			env("DATABASE_PORT", "5432"),
			env("DATABASE_NAME", "room_gameplay"),
		),
		kafkaBrokers: env("KAFKA_BROKERS", "localhost:9092"),
		pollInterval: envDuration("POLL_INTERVAL_MS", time.Second),
		batchSize:    envInt("BATCH_SIZE", 200),
	}
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

	writer := newKafkaPublisher(cfg.kafkaBrokers)
	defer func() { _ = writer.Close() }()

	r := &relay{
		store:     &pgOutbox{pool: pool},
		publisher: writer,
		interval:  cfg.pollInterval,
		batchSize: cfg.batchSize,
	}

	mux := http.NewServeMux()
	mux.Handle("/metrics", promhttp.Handler())
	mux.HandleFunc("/", handler)
	server := &http.Server{Addr: ":" + cfg.port, Handler: mux, ReadHeaderTimeout: 5 * time.Second}

	go func() {
		logLine("info", "listening", map[string]any{
			"port": cfg.port, "brokers": cfg.kafkaBrokers, "batchSize": cfg.batchSize,
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
