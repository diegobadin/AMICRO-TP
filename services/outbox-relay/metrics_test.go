package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/prometheus/client_golang/prometheus/promhttp"
)

// Architecture §5 asks this service for rows_published, publish_failure and lag_seconds. The
// exposition format rewrites names it dislikes — room-gameplay lost `rooms_created_total` to that
// in P3 — so the strings a dashboard will query are asserted verbatim.
func TestMetricsExposeTheExactNamesDashboardsQuery(t *testing.T) {
	rowsPublished.WithLabelValues("room.public.events").Inc()
	publishFailures.Inc()
	lagSeconds.Set(12)
	backlogRows.Set(596)
	backlogReads.Inc()

	res := httptest.NewRecorder()
	promhttp.Handler().ServeHTTP(res, httptest.NewRequest(http.MethodGet, "/metrics", nil))
	body := res.Body.String()

	for _, want := range []string{
		`outboxrelay_rows_published_total{topic="room.public.events"}`,
		"outboxrelay_publish_failures_total",
		"outboxrelay_lag_seconds",
		"outboxrelay_backlog_rows",
		// Without this, an unset lag gauge and a healthy one read the same.
		"outboxrelay_backlog_reads_total",
	} {
		if !strings.Contains(body, want) {
			t.Errorf("missing %q from /metrics", want)
		}
	}
}
