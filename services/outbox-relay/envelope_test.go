package main

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func headers(t *testing.T, r outboxRow) map[string]string {
	t.Helper()
	m, err := message(r, roomGameplayDefaults())
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
	m, err := message(row(1, "room-abc", 42, "room.public.events"), roomGameplayDefaults())
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
	m, err := message(row(1, "room-abc", 1, "room.lifecycle.events"), roomGameplayDefaults())
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

	m, err := message(r, roomGameplayDefaults())
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
	if _, err := message(r, roomGameplayDefaults()); err == nil {
		t.Fatal("a row whose payload cannot be parsed must not be published")
	}
}

// The additive-growth rule, as a test. Three consumers read room-gameplay's format and P7 made the
// producer-specific parts configurable — so the defaults have to produce the same bytes they
// produced before, header for header and byte for byte. A change here is a change to a contract
// somebody else is already reading.
func TestTheDefaultEnvelopeHasNotMoved(t *testing.T) {
	r := row(7, "room-abc", 42, "room.lifecycle.events")
	r.eventType = "GameCompleted"
	r.correlationID = "corr-1"
	r.payload = []byte(`{"type":"GameCompleted","gameNumber":1,"at":"2026-08-11T12:00:00Z"}`)

	m, err := message(r, roomGameplayDefaults())
	if err != nil {
		t.Fatalf("message: %v", err)
	}

	wantHeaders := []struct{ key, value string }{
		{"ce-specversion", "1.0"},
		{"ce-id", "room-abc:42"},
		{"ce-source", "/room-gameplay"},
		{"ce-type", "com.unoarena.room.GameCompleted.v1"},
		{"ce-time", "2026-08-11T12:00:00Z"},
		{"ce-subject", "room-abc"},
		{"ce-correlationid", "corr-1"},
	}
	if len(m.Headers) != len(wantHeaders) {
		t.Fatalf("header count = %d, want %d — a new header is a wire change", len(m.Headers), len(wantHeaders))
	}
	for i, want := range wantHeaders {
		// Order included: a consumer reading these positionally is not this repo's problem, but a
		// silent reordering is still a change nobody asked for.
		if m.Headers[i].Key != want.key || string(m.Headers[i].Value) != want.value {
			t.Errorf("header %d = %s:%s, want %s:%s", i, m.Headers[i].Key, m.Headers[i].Value, want.key, want.value)
		}
	}

	const wantBody = `{"at":"2026-08-11T12:00:00Z","gameNumber":1,"roomId":"room-abc","sequenceNumber":42,"type":"GameCompleted"}`
	if string(m.Value) != wantBody {
		t.Errorf("body =\n  %s\nwant\n  %s", m.Value, wantBody)
	}
	if string(m.Key) != "room-abc" || m.Topic != "room.lifecycle.events" {
		t.Errorf("key/topic = %s/%s", m.Key, m.Topic)
	}
}

// The second producer, through the same code path: a different source, a different type prefix, and
// the id merged into the body under the name the tournament's own catalog entry uses.
func TestATournamentEventCarriesItsOwnIdentity(t *testing.T) {
	cfg := sourceConfig{
		source:     "/tournament",
		typePrefix: "com.unoarena.tournament.",
		keyColumn:  "tournament_id",
		bodyField:  "tournamentId",
	}
	r := row(3, "tour-1", 9, "tournament.lifecycle.events")
	r.eventType = "TournamentCompleted"
	r.payload = []byte(`{"type":"TournamentCompleted","champion":"alice","at":"2026-08-17T12:00:00Z"}`)

	m, err := message(r, cfg)
	if err != nil {
		t.Fatalf("message: %v", err)
	}

	h := map[string]string{}
	for _, header := range m.Headers {
		h[header.Key] = string(header.Value)
	}
	if h["ce-source"] != "/tournament" {
		t.Errorf("ce-source = %q", h["ce-source"])
	}
	if h["ce-type"] != "com.unoarena.tournament.TournamentCompleted.v1" {
		t.Errorf("ce-type = %q", h["ce-type"])
	}
	if h["ce-id"] != "tour-1:9" {
		t.Errorf("ce-id = %q — the dedup key every consumer uses", h["ce-id"])
	}

	var body map[string]any
	if err := json.Unmarshal(m.Value, &body); err != nil {
		t.Fatalf("body is not json: %v", err)
	}
	if body["tournamentId"] != "tour-1" {
		t.Errorf("tournamentId = %v — the body names its own aggregate", body["tournamentId"])
	}
	if _, present := body["roomId"]; present {
		t.Error("a tournament event has no room")
	}
	if string(m.Key) != "tour-1" {
		t.Errorf("key = %q, want the tournament — §3.3.2 partitions on it", m.Key)
	}
}

// Interpolated into SQL, so it is checked before it gets there. Configuration, not user input —
// which is a reason to be careful, not a reason to trust it.
func TestAKeyColumnThatIsNotAnIdentifierIsRefused(t *testing.T) {
	for _, bad := range []string{"room_id; drop table outbox", "room id", "ROOM_ID", "", "room_id--"} {
		if _, err := newOutbox(nil, bad); err == nil {
			t.Errorf("%q should not be accepted as a column name", bad)
		}
	}
	if _, err := newOutbox(nil, "tournament_id"); err != nil {
		t.Errorf("a plain column name should be accepted: %v", err)
	}
}
