package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/prometheus/client_golang/prometheus/promhttp"
)

// The exposition format rewrites names it dislikes — room-gameplay lost `rooms_created_total` to
// that in P3 — so what a dashboard will query is asserted verbatim rather than by counting series.
func TestMetricsExposeTheExactNamesDashboardsQuery(t *testing.T) {
	ticksTotal.WithLabelValues("sent").Inc()
	ticksTotal.WithLabelValues("failed").Inc()
	sweepFailures.Inc()
	dueRooms.Set(3)
	tickLag.Observe(1.5)

	res := httptest.NewRecorder()
	promhttp.Handler().ServeHTTP(res, httptest.NewRequest(http.MethodGet, "/metrics", nil))
	body := res.Body.String()

	for _, want := range []string{
		`timerworker_ticks_total{result="sent"}`,
		`timerworker_ticks_total{result="failed"}`,
		"timerworker_sweep_failures_total",
		"timerworker_due_rooms",
		"timerworker_tick_lag_seconds_bucket",
	} {
		if !strings.Contains(body, want) {
			t.Errorf("missing %q from /metrics", want)
		}
	}
}
