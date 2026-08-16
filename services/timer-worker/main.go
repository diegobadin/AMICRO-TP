// The clock the aggregate never had. room-gameplay decides what a deadline means and has always
// settled overdue ones before judging a command (uno.expire) — but nothing ever arrived to make it
// look, so a room whose players walked away simply froze. This worker does one thing: find the
// rooms whose deadline has passed, and knock.
//
// It holds no game state and makes no rules. A tick that arrives late, twice, or with nothing due
// is an empty no-op, which is what lets the whole thing be at-least-once with no coordination.

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

const service = "timer-worker"

const defaultPort = "8089"

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
	port            string
	databaseURL     string
	roomGameplayURL string
	internalToken   string
	tickInterval    time.Duration
	batchSize       int
	httpTimeout     time.Duration
}

func configFromEnv() config {
	return config{
		port: env("PORT", defaultPort),
		// The same database and the same role as room-gameplay: the projection this reads is that
		// service's own, which is also why no new secret had to be sealed for it.
		databaseURL: fmt.Sprintf(
			"postgres://%s:%s@%s:%s/%s",
			env("DATABASE_USER", "room_gameplay"),
			os.Getenv("ROOM_GAMEPLAY_DB_PASSWORD"),
			env("DATABASE_HOST", "localhost"),
			env("DATABASE_PORT", "5432"),
			env("DATABASE_NAME", "room_gameplay"),
		),
		roomGameplayURL: env("ROOM_GAMEPLAY_URL", "http://localhost:8081"),
		internalToken:   os.Getenv("INTERNAL_TOKEN"),
		tickInterval:    envDuration("TICK_INTERVAL_MS", time.Second),
		batchSize:       envInt("TICK_BATCH_SIZE", 50),
		httpTimeout:     envDuration("HTTP_TIMEOUT_MS", 5*time.Second),
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

	// One session id per pod, minted once and logged, so a tick in room-gameplay's log can be
	// traced back to the process that sent it.
	sessionID := newSessionID()
	w := &worker{
		rooms: &pgRooms{pool: pool},
		ticks: &tickClient{
			baseURL:       cfg.roomGameplayURL,
			sessionID:     sessionID,
			internalToken: cfg.internalToken,
			client:        &http.Client{Timeout: cfg.httpTimeout},
		},
		interval:  cfg.tickInterval,
		batchSize: cfg.batchSize,
	}

	mux := http.NewServeMux()
	mux.Handle("/metrics", promhttp.Handler())
	mux.HandleFunc("/", handler)
	server := &http.Server{Addr: ":" + cfg.port, Handler: mux, ReadHeaderTimeout: 5 * time.Second}

	go func() {
		logLine("info", "listening", map[string]any{
			"port":           cfg.port,
			"sessionId":      sessionID,
			"roomGameplay":   cfg.roomGameplayURL,
			"tickIntervalMs": cfg.tickInterval.Milliseconds(),
		})
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logLine("error", "listen-failed", map[string]any{"error": err.Error()})
			os.Exit(1)
		}
	}()

	w.run(ctx)

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
