package main

import (
	"encoding/json"
	"strconv"
	"time"

	"github.com/segmentio/kafka-go"
)

// The wire shape of docs/architecture/10-api-event-catalog.md §3: CloudEvents in binary mode, so
// the metadata lives in headers and the body stays the domain event a consumer actually wants.
//
// The payload is taken from the outbox row verbatim. It has already been through
// `publicPayload(event)` on the way in — the same filter the SSE stream uses — which is why the RNG
// seed in GameStarted and DeckRecycled cannot reach a topic. Nothing here re-derives it.
const (
	ceSpecVersion = "1.0"
	ceSource      = "/room-gameplay"
	ceTypePrefix  = "com.unoarena.room."
	ceTypeSuffix  = ".v1"
)

func message(row outboxRow) (kafka.Message, error) {
	value, occurredAt, err := renderBody(row)
	if err != nil {
		return kafka.Message{}, err
	}

	headers := []kafka.Header{
		{Key: "ce-specversion", Value: []byte(ceSpecVersion)},
		// Deterministic, so a redelivery after a crash is recognisably the same event: the room and
		// its sequence number already identify one event for all time (that pair is the primary key
		// of the log), which is what makes at-least-once safe to consume.
		{Key: "ce-id", Value: []byte(row.roomID + ":" + strconv.Itoa(row.sequenceNumber))},
		{Key: "ce-source", Value: []byte(ceSource)},
		{Key: "ce-type", Value: []byte(ceTypePrefix + row.eventType + ceTypeSuffix)},
		{Key: "ce-time", Value: []byte(occurredAt)},
		{Key: "ce-subject", Value: []byte(row.roomID)},
	}
	if row.correlationID != "" {
		headers = append(headers, kafka.Header{Key: "ce-correlationid", Value: []byte(row.correlationID)})
	}

	return kafka.Message{
		// The row names its own topic (room-gameplay's `topicFor`), so the relay carries no table of
		// event types and P6's consumers need nothing added here.
		Topic: row.topic,
		// Partition key: per-room ordering is what every consumer in §2.3.2 relies on.
		Key:     []byte(row.roomID),
		Value:   value,
		Headers: headers,
	}, nil
}

// roomId and sequenceNumber are merged in rather than wrapped around: the catalog's payloads carry
// them as fields, and an envelope-around-payload would make every consumer unwrap a level.
func renderBody(row outboxRow) ([]byte, string, error) {
	var payload map[string]json.RawMessage
	if err := json.Unmarshal(row.payload, &payload); err != nil {
		return nil, "", err
	}
	payload["roomId"] = json.RawMessage(strconv.Quote(row.roomID))
	payload["sequenceNumber"] = json.RawMessage(strconv.Itoa(row.sequenceNumber))

	out, err := json.Marshal(payload)
	return out, occurredAt(payload, row.createdAt), err
}

// `at` is the engine's server-authoritative timestamp — when the event happened, rather than when a
// row landed. They differ by the width of one transaction, and the first is the one that is true.
func occurredAt(payload map[string]json.RawMessage, fallback time.Time) string {
	var at string
	if raw, ok := payload["at"]; ok && json.Unmarshal(raw, &at) == nil && at != "" {
		return at
	}
	return fallback.UTC().Format(time.RFC3339)
}
