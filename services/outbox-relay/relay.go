package main

import (
	"context"
	"math/rand"
	"time"
)

// The bridge the outbox has been waiting for since P3. room-gameplay writes events, outbox rows and
// the projection in one transaction; this drains the rows to Kafka afterwards. That ordering is the
// log-before-broadcast guarantee (architecture O1): a crash between the two costs a delay, never an
// event, and never a client told about something the log does not have.
type outboxRow struct {
	id             int64
	roomID         string
	sequenceNumber int
	topic          string
	eventType      string
	payload        []byte
	correlationID  string
	createdAt      time.Time
}

type outboxStore interface {
	unpublished(ctx context.Context, limit int) ([]outboxRow, error)
	markPublished(ctx context.Context, ids []int64) error
	backlog(ctx context.Context) (oldestSeconds float64, rows int64, err error)
}

type publisher interface {
	publish(ctx context.Context, rows []outboxRow) error
}

type relay struct {
	store     outboxStore
	publisher publisher
	interval  time.Duration
	batchSize int
}

// One batch. Publish first, mark second: the other order would let a crash in between leave rows
// marked as delivered that never reached a broker, which is the one failure the outbox exists to
// make impossible. Doing it this way can deliver an event twice instead — which consumers already
// have to handle, keyed on the room and sequence number the envelope carries.
func (r *relay) drain(ctx context.Context) (int, error) {
	rows, err := r.store.unpublished(ctx, r.batchSize)
	if err != nil {
		return 0, err
	}
	if len(rows) == 0 {
		return 0, nil
	}

	if err := r.publisher.publish(ctx, rows); err != nil {
		publishFailures.Inc()
		return 0, err
	}

	ids := make([]int64, 0, len(rows))
	for _, row := range rows {
		ids = append(ids, row.id)
		rowsPublished.WithLabelValues(row.topic).Inc()
	}
	if err := r.store.markPublished(ctx, ids); err != nil {
		// The rows are on the broker; failing to record that means they go again next pass. Loud,
		// because a persistent failure here is an unbounded duplicate stream rather than data loss.
		return len(rows), err
	}
	return len(rows), nil
}

// Read from the database, never from a cursor this process holds: P4's Redis outage looked healthy
// precisely because the heartbeat reported the gateway's own idea of progress. A number that comes
// from the thing being measured cannot lie about it in the same way.
func (r *relay) observeBacklog(ctx context.Context) {
	oldest, rows, err := r.store.backlog(ctx)
	if err != nil {
		// Left deliberately unlogged: the drain that ran a moment ago hit the same database and
		// reported it. What must not happen is the gauges quietly staying at a healthy-looking
		// value with nothing to say they are stale — hence the counter rather than a log line.
		return
	}
	backlogReads.Inc()
	lagSeconds.Set(oldest)
	backlogRows.Set(float64(rows))
}

func (r *relay) run(ctx context.Context) {
	wait := time.Duration(0)
	for {
		select {
		case <-ctx.Done():
			return
		case <-time.After(wait):
		}

		published, err := r.drain(ctx)
		r.observeBacklog(ctx)

		switch {
		case err != nil:
			wait = backoff(wait, r.interval)
			logLine("error", "drain-failed", map[string]any{
				"error": err.Error(), "retryInMs": wait.Milliseconds(),
			})
		case published > 0:
			logLine("info", "published", map[string]any{"rows": published})
			// A full batch means there is more waiting; keep going rather than sleeping on it.
			if published >= r.batchSize {
				wait = 0
			} else {
				wait = r.interval
			}
		default:
			wait = r.interval
		}
	}
}

const maxBackoff = 30 * time.Second

// A broker that is down is retried forever and the backlog simply grows — which is the outbox
// working, not failing. Gameplay is untouched throughout: the events were durable before this
// process ever saw them.
func backoff(current, base time.Duration) time.Duration {
	next := current * 2
	if next < base {
		next = base
	}
	if next > maxBackoff {
		next = maxBackoff
	}
	jitter := 1 + (rand.Float64()-0.5)*0.4 //nolint:gosec // spreading retries, not making secrets
	return time.Duration(float64(next) * jitter)
}
