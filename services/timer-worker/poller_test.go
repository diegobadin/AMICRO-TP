package main

import (
	"context"
	"errors"
	"testing"
	"time"
)

type fakeRooms struct {
	batches [][]due
	calls   int
	err     error
	limit   int
}

func (f *fakeRooms) due(_ context.Context, limit int) ([]due, error) {
	f.limit = limit
	f.calls++
	if f.err != nil {
		return nil, f.err
	}
	if len(f.batches) == 0 {
		return nil, nil
	}
	batch := f.batches[0]
	f.batches = f.batches[1:]
	return batch, nil
}

type fakeTicks struct {
	ticked []string
	fail   map[string]error
}

func (f *fakeTicks) tick(_ context.Context, roomID string) error {
	f.ticked = append(f.ticked, roomID)
	return f.fail[roomID]
}

func overdue(ids ...string) []due {
	out := make([]due, 0, len(ids))
	for _, id := range ids {
		out = append(out, due{roomID: id, deadline: time.Now().Add(-90 * time.Second)})
	}
	return out
}

func TestSweepTicksEveryOverdueRoom(t *testing.T) {
	rooms := &fakeRooms{batches: [][]due{overdue("a", "b", "c")}}
	ticks := &fakeTicks{}
	w := &worker{rooms: rooms, ticks: ticks, batchSize: 50, interval: time.Second}

	if err := w.sweep(context.Background()); err != nil {
		t.Fatalf("sweep: %v", err)
	}
	if len(ticks.ticked) != 3 {
		t.Fatalf("ticked %v, want all three", ticks.ticked)
	}
	if rooms.limit != 50 {
		t.Errorf("batch size not passed to the query: %d", rooms.limit)
	}
}

// One unreachable room must not cost the others their tick — otherwise a single stuck room stalls
// every deadline in the cluster.
func TestSweepKeepsGoingAfterAFailedTick(t *testing.T) {
	rooms := &fakeRooms{batches: [][]due{overdue("a", "b", "c")}}
	ticks := &fakeTicks{fail: map[string]error{"b": errors.New("refused")}}
	w := &worker{rooms: rooms, ticks: ticks, batchSize: 10}

	if err := w.sweep(context.Background()); err != nil {
		t.Fatalf("a refused tick is not a failed sweep: %v", err)
	}
	if len(ticks.ticked) != 3 {
		t.Fatalf("ticked %v, want a, b and c attempted", ticks.ticked)
	}
}

func TestSweepReportsAnUnreadableProjection(t *testing.T) {
	w := &worker{rooms: &fakeRooms{err: errors.New("no database")}, ticks: &fakeTicks{}, batchSize: 10}
	if err := w.sweep(context.Background()); err == nil {
		t.Fatal("a database that cannot be read has to be reported, not treated as an empty sweep")
	}
}

func TestRunStopsWhenTheContextIsCancelled(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	w := &worker{
		rooms:     &fakeRooms{batches: [][]due{overdue("a")}},
		ticks:     &fakeTicks{},
		interval:  time.Millisecond,
		batchSize: 10,
	}

	done := make(chan struct{})
	go func() { w.run(ctx); close(done) }()
	time.Sleep(20 * time.Millisecond)
	cancel()

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("run must return when its context is cancelled, or a pod never terminates")
	}
}

func TestBackoffGrowsAndIsCapped(t *testing.T) {
	base := time.Second
	// Well past the cap: doubling from one second reaches thirty in five steps.
	wait := base
	for i := 0; i < 10; i++ {
		wait = backoff(wait, base)
	}
	if wait > time.Duration(float64(maxBackoff)*1.2) {
		t.Fatalf("backoff ran away to %s", wait)
	}
	if wait < base {
		t.Fatalf("backoff collapsed below the poll interval: %s", wait)
	}

	// And it never returns the same value twice in a row, or the jitter is not doing its job.
	first, second := backoff(base, base), backoff(base, base)
	if first == second {
		t.Fatal("backoff must be jittered")
	}
}
