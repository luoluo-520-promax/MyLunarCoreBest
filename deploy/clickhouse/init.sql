CREATE TABLE IF NOT EXISTS game_events
(
    event_time DateTime64(3) DEFAULT now64(3),
    event_name LowCardinality(String),
    player_id UInt32,
    props String
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(event_time)
ORDER BY (event_name, player_id, event_time);
