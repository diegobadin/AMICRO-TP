package main

import (
	"context"
	"strings"
	"time"

	"github.com/segmentio/kafka-go"
)

// One writer for both topics: the message carries its own, so nothing here has to know that
// `room.lifecycle.events` and `room.public.events` exist. RequireAll because a publish that the
// leader alone acknowledged is not the durability the consumers downstream are promised.
type kafkaPublisher struct{ writer *kafka.Writer }

func newKafkaPublisher(brokers string) *kafkaPublisher {
	return &kafkaPublisher{writer: &kafka.Writer{
		Addr:         kafka.TCP(strings.Split(brokers, ",")...),
		Balancer:     &kafka.Hash{}, // roomId → partition, so a room's events stay in order
		RequiredAcks: kafka.RequireAll,
		// The relay is the only writer and it retries the whole batch on failure, so a partial
		// success must be reported rather than smoothed over by the client.
		Async:                  false,
		AllowAutoTopicCreation: false,
		WriteTimeout:           10 * time.Second,
		BatchTimeout:           10 * time.Millisecond,
	}}
}

func (p *kafkaPublisher) publish(ctx context.Context, rows []outboxRow) error {
	messages := make([]kafka.Message, 0, len(rows))
	for _, row := range rows {
		m, err := message(row)
		if err != nil {
			return err
		}
		messages = append(messages, m)
	}
	return p.writer.WriteMessages(ctx, messages...)
}

func (p *kafkaPublisher) Close() error { return p.writer.Close() }
