package main

import (
	"encoding/json"
	"os"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
)

// Names are written out in full rather than assembled from a namespace, so what a dashboard queries
// is greppable in this file — and asserted verbatim in the tests, because the exposition format
// rewrites names it dislikes and "some metrics came back" is not an assertion.
var (
	ticksTotal = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "timerworker_ticks_total",
		Help: "Ticks sent to room-gameplay, by outcome",
	}, []string{"result"})

	sweepFailures = promauto.NewCounter(prometheus.CounterOpts{
		Name: "timerworker_sweep_failures_total",
		Help: "Polls of the rooms projection that failed",
	})

	// Counts the polls that worked, which the gauge below cannot tell you: a gauge that has never
	// been Set reads 0, exactly like one set to zero by a sweep that found nothing. The P5 drill
	// spent real time on that ambiguity — "is it idle or has it never run?" — so the answer is a
	// counter, not an inference.
	sweeps = promauto.NewCounter(prometheus.CounterOpts{
		Name: "timerworker_sweeps_total",
		Help: "Polls of the rooms projection that succeeded",
	})

	dueRooms = promauto.NewGauge(prometheus.GaugeOpts{
		Name: "timerworker_due_rooms",
		Help: "Rooms found overdue on the last sweep",
	})

	// How late a deadline was when the worker got to it — the number that says whether the timers
	// are prompt. It is measured against the deadline the database handed back, not against
	// anything this process remembers.
	tickLag = promauto.NewHistogram(prometheus.HistogramOpts{
		Name:    "timerworker_tick_lag_seconds",
		Help:    "Delay between a deadline passing and the tick that answered it",
		Buckets: []float64{0.5, 1, 2, 5, 10, 30, 60, 300},
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
