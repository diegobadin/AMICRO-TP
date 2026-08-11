package main

import (
	"context"

	"github.com/jackc/pgx/v5/pgxpool"
)

// `order by id` is the whole ordering argument: ids are assigned by one bigserial in commit order,
// so draining in id order preserves per-room order without the relay knowing what a room is. The
// partial index `outbox_unpublished_idx` is what makes this a cheap lookup rather than a scan.
const unpublishedQuery = `select id, room_id, sequence_number, topic, event_type, payload,
                                 coalesce(correlation_id, ''), created_at
                          from outbox
                          where published_at is null
                          order by id
                          limit $1`

const markPublishedQuery = `update outbox set published_at = now() where id = any($1)`

// The two numbers that say whether the spine is healthy, both from the source of truth.
const backlogQuery = `select coalesce(extract(epoch from now() - min(created_at)), 0), count(*)
                      from outbox where published_at is null`

type pgOutbox struct{ pool *pgxpool.Pool }

func (p *pgOutbox) unpublished(ctx context.Context, limit int) ([]outboxRow, error) {
	rows, err := p.pool.Query(ctx, unpublishedQuery, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []outboxRow
	for rows.Next() {
		var r outboxRow
		if err := rows.Scan(
			&r.id, &r.roomID, &r.sequenceNumber, &r.topic, &r.eventType,
			&r.payload, &r.correlationID, &r.createdAt,
		); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

func (p *pgOutbox) markPublished(ctx context.Context, ids []int64) error {
	_, err := p.pool.Exec(ctx, markPublishedQuery, ids)
	return err
}

func (p *pgOutbox) backlog(ctx context.Context) (float64, int64, error) {
	var oldest float64
	var count int64
	err := p.pool.QueryRow(ctx, backlogQuery).Scan(&oldest, &count)
	return oldest, count, err
}
