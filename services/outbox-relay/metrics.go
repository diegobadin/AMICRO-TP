package main

import (
	"encoding/json"
	"os"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
)

// The three names architecture §5 asks this service for — rows_published, publish_failure,
// lag_seconds — plus the backlog they are read from. Written out in full and asserted verbatim in
// the tests, because the exposition format rewrites names it dislikes.
var (
	rowsPublished = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "outboxrelay_rows_published_total",
		Help: "Outbox rows delivered to Kafka, by topic",
	}, []string{"topic"})

	publishFailures = promauto.NewCounter(prometheus.CounterOpts{
		Name: "outboxrelay_publish_failures_total",
		Help: "Batches the broker did not accept",
	})

	// Age of the oldest row still waiting, straight from Postgres. This is the number that goes
	// wrong first when Kafka is unreachable, and the one an alert should watch.
	lagSeconds = promauto.NewGauge(prometheus.GaugeOpts{
		Name: "outboxrelay_lag_seconds",
		Help: "Age of the oldest unpublished outbox row",
	})

	backlogRows = promauto.NewGauge(prometheus.GaugeOpts{
		Name: "outboxrelay_backlog_rows",
		Help: "Outbox rows still unpublished",
	})
)

func logLine(level, action string, fields map[string]any) {
	entry := map[string]any{
		"ts":      time.Now().UTC().Format(time.RFC3339),
		"level":   level,
		"service": service,
		"action":  action,
	}
	for k, v := range fields {
		entry[k] = v
	}
	b, err := json.Marshal(entry)
	if err != nil {
		return
	}
	_, _ = os.Stdout.Write(append(b, '\n'))
}
