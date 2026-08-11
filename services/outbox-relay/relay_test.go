package main

import (
	"context"
	"errors"
	"testing"
	"time"
)

type fakeStore struct {
	rows     []outboxRow
	marked   []int64
	readErr  error
	markErr  error
	askedFor int
}

func (f *fakeStore) unpublished(_ context.Context, limit int) ([]outboxRow, error) {
	f.askedFor = limit
	if f.readErr != nil {
		return nil, f.readErr
	}
	return f.rows, nil
}

func (f *fakeStore) markPublished(_ context.Context, ids []int64) error {
	if f.markErr != nil {
		return f.markErr
	}
	f.marked = append(f.marked, ids...)
	return nil
}

func (f *fakeStore) backlog(context.Context) (float64, int64, error) {
	return float64(len(f.rows)), int64(len(f.rows)), nil
}

type fakePublisher struct {
	published [][]outboxRow
	err       error
	onPublish func()
}

func (f *fakePublisher) publish(_ context.Context, rows []outboxRow) error {
	if f.onPublish != nil {
		f.onPublish()
	}
	if f.err != nil {
		return f.err
	}
	f.published = append(f.published, rows)
	return nil
}

func row(id int64, roomID string, seq int, topic string) outboxRow {
	return outboxRow{
		id: id, roomID: roomID, sequenceNumber: seq, topic: topic, eventType: "CardPlayed",
		payload:   []byte(`{"type":"CardPlayed","playerId":"a","at":"2026-08-11T12:00:00Z"}`),
		createdAt: time.Date(2026, 8, 11, 12, 0, 0, 0, time.UTC),
	}
}

func TestDrainPublishesThenMarks(t *testing.T) {
	store := &fakeStore{rows: []outboxRow{
		row(1, "r1", 1, "room.lifecycle.events"),
		row(2, "r1", 2, "room.public.events"),
	}}
	r := &relay{store: store, publisher: &fakePublisher{}, batchSize: 200}

	published, err := r.drain(context.Background())
	if err != nil {
		t.Fatalf("drain: %v", err)
	}
	if published != 2 {
		t.Fatalf("published %d rows, want 2", published)
	}
	if len(store.marked) != 2 || store.marked[0] != 1 || store.marked[1] != 2 {
		t.Fatalf("marked %v, want both ids", store.marked)
	}
}

// The failure the outbox exists to make impossible: a row must never be recorded as delivered
// unless the broker took it.
func TestARefusedBatchIsNeverMarkedPublished(t *testing.T) {
	store := &fakeStore{rows: []outboxRow{row(1, "r1", 1, "room.public.events")}}
	r := &relay{store: store, publisher: &fakePublisher{err: errors.New("no broker")}, batchSize: 200}

	if _, err := r.drain(context.Background()); err == nil {
		t.Fatal("a batch the broker refused has to be reported")
	}
	if len(store.marked) != 0 {
		t.Fatalf("marked %v after a failed publish — those events would be lost", store.marked)
	}
}

// Marking happens strictly after publishing. Asserted by observing the store from inside the
// publisher, because the ordering is the whole guarantee and a refactor could silently invert it.
func TestNothingIsMarkedBeforeItIsPublished(t *testing.T) {
	store := &fakeStore{rows: []outboxRow{row(1, "r1", 1, "room.public.events")}}
	publisher := &fakePublisher{}
	publisher.onPublish = func() {
		if len(store.marked) != 0 {
			t.Error("rows were marked published before the broker was asked")
		}
	}
	r := &relay{store: store, publisher: publisher, batchSize: 200}

	if _, err := r.drain(context.Background()); err != nil {
		t.Fatalf("drain: %v", err)
	}
}

func TestDrainOfAnEmptyOutboxDoesNothing(t *testing.T) {
	store := &fakeStore{}
	publisher := &fakePublisher{}
	r := &relay{store: store, publisher: publisher, batchSize: 200}

	published, err := r.drain(context.Background())
	if err != nil || published != 0 {
		t.Fatalf("published %d, err %v", published, err)
	}
	if len(publisher.published) != 0 {
		t.Fatal("an empty outbox must not produce an empty batch on the broker")
	}
}

func TestDrainReportsAnUnreadableOutbox(t *testing.T) {
	r := &relay{
		store:     &fakeStore{readErr: errors.New("no database")},
		publisher: &fakePublisher{},
		batchSize: 200,
	}
	if _, err := r.drain(context.Background()); err == nil {
		t.Fatal("a database that cannot be read is not an empty outbox")
	}
}

func TestDrainPassesTheBatchSizeToTheQuery(t *testing.T) {
	store := &fakeStore{}
	r := &relay{store: store, publisher: &fakePublisher{}, batchSize: 17}
	_, _ = r.drain(context.Background())
	if store.askedFor != 17 {
		t.Fatalf("asked for %d rows, want the configured batch size", store.askedFor)
	}
}

func TestBackoffGrowsAndIsCapped(t *testing.T) {
	base := time.Second
	wait := base
	for i := 0; i < 10; i++ {
		wait = backoff(wait, base)
	}
	if wait > time.Duration(float64(maxBackoff)*1.2) {
		t.Fatalf("backoff ran away to %s", wait)
	}
	if backoff(base, base) == backoff(base, base) {
		t.Fatal("backoff must be jittered")
	}
}
