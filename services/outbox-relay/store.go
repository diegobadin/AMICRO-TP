package main

import (
	"context"
	"fmt"
	"regexp"

	"github.com/jackc/pgx/v5/pgxpool"
)

// `order by id` is the whole ordering argument: ids are assigned by one bigserial in commit order,
// so draining in id order preserves per-aggregate order without the relay knowing what a room or a
// tournament is. The partial index `outbox_unpublished_idx` is what makes this a cheap lookup
// rather than a scan.
//
// The key column is the one thing that differs between producers (`room_id`, `tournament_id`), so
// it is interpolated — and therefore validated. `keyColumnPattern` is the whole defence: it is
// configuration rather than user input, which is a reason to be careful with it, not a reason to
// skip the check.
const unpublishedQueryFormat = `select id, %s, sequence_number, topic, event_type, payload,
                                       coalesce(correlation_id, ''), created_at
                                from outbox
                                where published_at is null
                                order by id
                                limit $1`

var keyColumnPattern = regexp.MustCompile(`^[a-z_]+$`)

const markPublishedQuery = `update outbox set published_at = now() where id = any($1)`

// The two numbers that say whether the spine is healthy, both from the source of truth.
const backlogQuery = `select coalesce(extract(epoch from now() - min(created_at)), 0), count(*)
                      from outbox where published_at is null`

type pgOutbox struct {
	pool             *pgxpool.Pool
	unpublishedQuery string
}

func newOutbox(pool *pgxpool.Pool, keyColumn string) (*pgOutbox, error) {
	if !keyColumnPattern.MatchString(keyColumn) {
		return nil, fmt.Errorf("OUTBOX_KEY_COLUMN %q is not a plain column name", keyColumn)
	}
	return &pgOutbox{pool: pool, unpublishedQuery: fmt.Sprintf(unpublishedQueryFormat, keyColumn)}, nil
}

func (p *pgOutbox) unpublished(ctx context.Context, limit int) ([]outboxRow, error) {
	rows, err := p.pool.Query(ctx, p.unpublishedQuery, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []outboxRow
	for rows.Next() {
		var r outboxRow
		if err := rows.Scan(
			&r.id, &r.aggregateID, &r.sequenceNumber, &r.topic, &r.eventType,
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
