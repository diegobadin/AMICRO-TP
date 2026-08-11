package main

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func headers(t *testing.T, r outboxRow) map[string]string {
	t.Helper()
	m, err := message(r)
	if err != nil {
		t.Fatalf("message: %v", err)
	}
	out := map[string]string{}
	for _, h := range m.Headers {
		out[h.Key] = string(h.Value)
	}
	return out
}

func TestMessageCarriesTheCatalogsCloudEventHeaders(t *testing.T) {
	r := row(7, "room-abc", 42, "room.lifecycle.events")
	r.eventType = "GameCompleted"
	r.correlationID = "corr-1"

	h := headers(t, r)
	want := map[string]string{
		"ce-specversion":   "1.0",
		"ce-id":            "room-abc:42",
		"ce-source":        "/room-gameplay",
		"ce-type":          "com.unoarena.room.GameCompleted.v1",
		"ce-subject":       "room-abc",
		"ce-correlationid": "corr-1",
	}
	for k, v := range want {
		if h[k] != v {
			t.Errorf("%s = %q, want %q", k, h[k], v)
		}
	}
}

// The event's own timestamp, not the row's: they differ by the width of a transaction and the
// first is the one that is true.
func TestCeTimeIsWhenTheEventHappened(t *testing.T) {
	r := row(1, "room-abc", 1, "room.public.events")
	if got := headers(t, r)["ce-time"]; got != "2026-08-11T12:00:00Z" {
		t.Errorf("ce-time = %q, want the payload's `at`", got)
	}

	// A payload without `at` still has to produce a valid time.
	r.payload = []byte(`{"type":"Whatever"}`)
	r.createdAt = time.Date(2026, 8, 11, 13, 30, 0, 0, time.UTC)
	if got := headers(t, r)["ce-time"]; got != "2026-08-11T13:30:00Z" {
		t.Errorf("ce-time = %q, want the row's created_at as the fallback", got)
	}
}

func TestCorrelationHeaderIsOmittedWhenThereIsNone(t *testing.T) {
	if _, present := headers(t, row(1, "r", 1, "room.public.events"))["ce-correlationid"]; present {
		t.Error("an empty correlation id should not become an empty header")
	}
}

func TestBodyMergesTheRoomAndSequenceIntoThePayload(t *testing.T) {
	m, err := message(row(1, "room-abc", 42, "room.public.events"))
	if err != nil {
		t.Fatalf("message: %v", err)
	}

	var body map[string]any
	if err := json.Unmarshal(m.Value, &body); err != nil {
		t.Fatalf("body is not json: %v", err)
	}
	if body["roomId"] != "room-abc" {
		t.Errorf("roomId = %v", body["roomId"])
	}
	if body["sequenceNumber"] != float64(42) {
		t.Errorf("sequenceNumber = %v", body["sequenceNumber"])
	}
	if body["type"] != "CardPlayed" {
		t.Errorf("the event's own fields must survive: %v", body)
	}
}

func TestTheMessageIsKeyedAndTopickedByTheRow(t *testing.T) {
	m, err := message(row(1, "room-abc", 1, "room.lifecycle.events"))
	if err != nil {
		t.Fatalf("message: %v", err)
	}
	if string(m.Key) != "room-abc" {
		t.Errorf("key = %q — per-room ordering depends on this", m.Key)
	}
	if m.Topic != "room.lifecycle.events" {
		t.Errorf("topic = %q, want the one the row names", m.Topic)
	}
}

// The relay never re-derives the privacy filter: it publishes the payload room-gameplay already
// put through `publicPayload`. This is the standing check that nothing here starts adding fields.
func TestNothingIsInventedBeyondRoomAndSequence(t *testing.T) {
	r := row(1, "room-abc", 5, "room.public.events")
	r.payload = []byte(`{"type":"GameStarted","gameNumber":1,"at":"2026-08-11T12:00:00Z"}`)

	m, err := message(r)
	if err != nil {
		t.Fatalf("message: %v", err)
	}
	var body map[string]any
	_ = json.Unmarshal(m.Value, &body)

	for key := range body {
		switch key {
		case "type", "gameNumber", "at", "roomId", "sequenceNumber":
		default:
			t.Errorf("unexpected field %q on the wire", key)
		}
	}
	if strings.Contains(string(m.Value), "seed") {
		t.Error("a seed on a public topic is the deck order")
	}
}

func TestAnUnreadablePayloadIsReportedRatherThanShipped(t *testing.T) {
	r := row(1, "r", 1, "room.public.events")
	r.payload = []byte(`not json at all`)
	if _, err := message(r); err == nil {
		t.Fatal("a row whose payload cannot be parsed must not be published")
	}
}
