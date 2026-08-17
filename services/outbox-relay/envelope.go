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
// The payload is taken from the outbox row verbatim. For room-gameplay it has already been through
// `publicPayload(event)` on the way in — the same filter the SSE stream uses — which is why the RNG
// seed in GameStarted and DeckRecycled cannot reach a topic. Nothing here re-derives it.
//
// P7 made the four producer-specific values configuration rather than constants, because a second
// producer (tournament) drains its own outbox through a second copy of this binary. The defaults
// are room-gameplay's, byte for byte: three consumers read that format and the additive-growth rule
// says it must not move.
const ceSpecVersion = "1.0"

type sourceConfig struct {
	// `ce-source`: which service produced the event. "/room-gameplay", "/tournament".
	source string
	// `ce-type` is a reverse-DNS URI, and this is everything before the event name:
	// "com.unoarena.room." + "GameCompleted" + ".v1". Consumers classify on the BODY's `type`;
	// this header is routing metadata and always has been.
	typePrefix string
	// The outbox column holding the id the topic is partitioned by. Interpolated into SQL, so it is
	// validated at startup — it is configuration, not input, and it is treated as neither.
	keyColumn string
	// The field that id is merged into the body as: "roomId", "tournamentId".
	bodyField string
}

func roomGameplayDefaults() sourceConfig {
	return sourceConfig{
		source:     "/room-gameplay",
		typePrefix: "com.unoarena.room.",
		keyColumn:  "room_id",
		bodyField:  "roomId",
	}
}

func message(row outboxRow, cfg sourceConfig) (kafka.Message, error) {
	value, occurredAt, err := renderBody(row, cfg.bodyField)
	if err != nil {
		return kafka.Message{}, err
	}

	headers := []kafka.Header{
		{Key: "ce-specversion", Value: []byte(ceSpecVersion)},
		// Deterministic, so a redelivery after a crash is recognisably the same event: the aggregate
		// and its sequence number already identify one event for all time (that pair is the primary
		// key of the log), which is what makes at-least-once safe to consume.
		{Key: "ce-id", Value: []byte(row.aggregateID + ":" + strconv.Itoa(row.sequenceNumber))},
		{Key: "ce-source", Value: []byte(cfg.source)},
		{Key: "ce-type", Value: []byte(cfg.typePrefix + row.eventType + ".v1")},
		{Key: "ce-time", Value: []byte(occurredAt)},
		{Key: "ce-subject", Value: []byte(row.aggregateID)},
	}
	if row.correlationID != "" {
		headers = append(headers, kafka.Header{Key: "ce-correlationid", Value: []byte(row.correlationID)})
	}

	return kafka.Message{
		// The row names its own topic (the producer's `topicFor`), so the relay carries no table of
		// event types and no consumer needs anything added here.
		Topic: row.topic,
		// Partition key: per-aggregate ordering is what every consumer in §2.3.2 relies on.
		Key:     []byte(row.aggregateID),
		Value:   value,
		Headers: headers,
	}, nil
}

// The id and sequenceNumber are merged in rather than wrapped around: the catalog's payloads carry
// them as fields, and an envelope-around-payload would make every consumer unwrap a level.
func renderBody(row outboxRow, bodyField string) ([]byte, string, error) {
	var payload map[string]json.RawMessage
	if err := json.Unmarshal(row.payload, &payload); err != nil {
		return nil, "", err
	}
	payload[bodyField] = json.RawMessage(strconv.Quote(row.aggregateID))
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
