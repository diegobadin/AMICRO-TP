package main

import (
	"context"
	"math/rand"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// The whole of what this service knows about the game: rooms have deadlines, and a room that is
// finished has none. `next_deadline` is written by room-gameplay in the same transaction as the
// events, so it can never describe a room that does not exist — and the comparison is against the
// database's own clock, so the worker never forms an opinion about what time it is.
const dueQuery = `select room_id, next_deadline from rooms
                  where next_deadline is not null and next_deadline <= now() and status <> 'COMPLETED'
                  order by next_deadline
                  limit $1`

type due struct {
	roomID   string
	deadline time.Time
}

type roomSource interface {
	due(ctx context.Context, limit int) ([]due, error)
}

type ticker interface {
	tick(ctx context.Context, roomID string) error
}

type pgRooms struct{ pool *pgxpool.Pool }

func (p *pgRooms) due(ctx context.Context, limit int) ([]due, error) {
	rows, err := p.pool.Query(ctx, dueQuery, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []due
	for rows.Next() {
		var d due
		if err := rows.Scan(&d.roomID, &d.deadline); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

type worker struct {
	rooms     roomSource
	ticks     ticker
	interval  time.Duration
	batchSize int
}

// One pass. A tick that fails is counted and left alone: the room is still due, so the next pass
// finds it again — the only retry this design needs, and the reason there is no queue.
func (w *worker) sweep(ctx context.Context) error {
	rooms, err := w.rooms.due(ctx, w.batchSize)
	if err != nil {
		sweepFailures.Inc()
		return err
	}
	dueRooms.Set(float64(len(rooms)))

	for _, room := range rooms {
		if ctx.Err() != nil {
			return nil
		}
		tickLag.Observe(time.Since(room.deadline).Seconds())
		if err := w.ticks.tick(ctx, room.roomID); err != nil {
			ticksTotal.WithLabelValues("failed").Inc()
			logLine("error", "tick-failed", map[string]any{"roomId": room.roomID, "error": err.Error()})
			continue
		}
		ticksTotal.WithLabelValues("sent").Inc()
		logLine("info", "tick", map[string]any{
			"roomId":        room.roomID,
			"lateBySeconds": time.Since(room.deadline).Seconds(),
		})
	}
	return nil
}

// Backoff applies to the *poll*, not to the tick: a database that is down should not be asked every
// second, whereas a room that is still due costs one row and would be found again anyway.
func (w *worker) run(ctx context.Context) {
	wait := w.interval
	for {
		select {
		case <-ctx.Done():
			return
		case <-time.After(wait):
		}

		if err := w.sweep(ctx); err != nil {
			wait = backoff(wait, w.interval)
			logLine("error", "sweep-failed", map[string]any{
				"error":     err.Error(),
				"retryInMs": wait.Milliseconds(),
			})
			continue
		}
		wait = w.interval
	}
}

const maxBackoff = 30 * time.Second

// Doubling with jitter, so a Postgres coming back does not meet every caller at the same instant.
// ±20% is enough to spread a herd without making the interval hard to reason about.
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
