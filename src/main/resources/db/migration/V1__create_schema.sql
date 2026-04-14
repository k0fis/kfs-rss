-- V1__create_schema.sql

CREATE TABLE users (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(64) UNIQUE NOT NULL,
    password    VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE feeds (
    id              BIGSERIAL PRIMARY KEY,
    url             TEXT UNIQUE NOT NULL,
    feed_hash       VARCHAR(8) NOT NULL UNIQUE,
    title           VARCHAR(500),
    site_url        TEXT,
    last_fetched_at TIMESTAMPTZ,
    last_error      TEXT,
    etag            TEXT,
    last_modified   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_feeds_hash ON feeds (feed_hash);

CREATE TABLE user_feeds (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    feed_id     BIGINT NOT NULL REFERENCES feeds(id) ON DELETE CASCADE,
    category    VARCHAR(255) NOT NULL DEFAULT '',
    added_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, feed_id)
);

CREATE INDEX idx_user_feeds_user ON user_feeds (user_id);
CREATE INDEX idx_user_feeds_feed ON user_feeds (feed_id);

CREATE TABLE articles (
    id              BIGSERIAL PRIMARY KEY,
    feed_id         BIGINT NOT NULL REFERENCES feeds(id) ON DELETE CASCADE,
    guid            TEXT NOT NULL,
    title           TEXT NOT NULL DEFAULT '',
    link            TEXT NOT NULL DEFAULT '',
    author          TEXT NOT NULL DEFAULT '',
    summary         TEXT NOT NULL DEFAULT '',
    content         TEXT NOT NULL DEFAULT '',
    image           TEXT NOT NULL DEFAULT '',
    published_at    TIMESTAMPTZ,
    fetched_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    search_vector   TSVECTOR,
    UNIQUE(feed_id, guid)
);

CREATE INDEX idx_articles_feed ON articles (feed_id);
CREATE INDEX idx_articles_published ON articles (published_at DESC);
CREATE INDEX idx_articles_search ON articles USING GIN (search_vector);

CREATE OR REPLACE FUNCTION articles_search_update() RETURNS TRIGGER AS $$
BEGIN
    NEW.search_vector :=
        setweight(to_tsvector('simple', COALESCE(NEW.title, '')), 'A') ||
        setweight(to_tsvector('simple', COALESCE(NEW.summary, '')), 'B') ||
        setweight(to_tsvector('simple', COALESCE(NEW.author, '')), 'C');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_articles_search
    BEFORE INSERT OR UPDATE OF title, summary, author ON articles
    FOR EACH ROW EXECUTE FUNCTION articles_search_update();

CREATE TABLE user_articles (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    article_id  BIGINT NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    is_read     BOOLEAN NOT NULL DEFAULT FALSE,
    is_starred  BOOLEAN NOT NULL DEFAULT FALSE,
    read_at     TIMESTAMPTZ,
    starred_at  TIMESTAMPTZ,
    UNIQUE(user_id, article_id)
);

CREATE INDEX idx_user_articles_user ON user_articles (user_id);
CREATE INDEX idx_user_articles_starred ON user_articles (user_id) WHERE is_starred = TRUE;
