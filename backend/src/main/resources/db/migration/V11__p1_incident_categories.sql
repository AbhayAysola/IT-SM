-- Incident categories (P1). Backs com.itsm.common.spi.CategoryCatalog and /api/admin/categories.
--
-- Categories are never deleted: incidents (P2) and later P1 tables reference them, so retiring one means
-- active = FALSE. `code` is the stable machine identifier (CategoryCatalog.findByCode); it cannot change.
CREATE TABLE incident_categories (
    id          BIGSERIAL    PRIMARY KEY,
    code        VARCHAR(40)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(300),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    version     BIGINT       NOT NULL DEFAULT 0,          -- JPA optimistic lock
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_incident_categories_code UNIQUE (code),
    -- Target of the keyword table's composite FK, which mirrors `active` onto every keyword row.
    CONSTRAINT uq_incident_categories_id_active UNIQUE (id, active),
    CONSTRAINT chk_category_code CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT chk_category_name CHECK (name = btrim(name) AND char_length(name) > 0),
    CONSTRAINT chk_category_description CHECK (description IS NULL OR (description = btrim(description) AND description <> ''))
);
-- Names are unique ignoring case ("Network" and "network" are the same category).
CREATE UNIQUE INDEX uq_incident_categories_name_ci ON incident_categories (lower(name));

-- Keywords feed P2's automatic categorisation. Stored normalised: trimmed, lower-case, single spaces.
CREATE TABLE incident_category_keywords (
    category_id     BIGINT      NOT NULL,
    keyword         VARCHAR(30) NOT NULL,
    category_active BOOLEAN     NOT NULL,                  -- copy of the owning category's `active`
    PRIMARY KEY (category_id, keyword),                    -- no duplicate keyword within a category
    CONSTRAINT fk_category_keyword_category FOREIGN KEY (category_id, category_active)
        REFERENCES incident_categories (id, active) ON UPDATE CASCADE,
    CONSTRAINT chk_category_keyword_normalised CHECK (
        keyword = lower(btrim(keyword))
        AND position('  ' IN keyword) = 0
        AND char_length(keyword) BETWEEN 2 AND 30
        AND keyword ~ '^[a-z0-9][a-z0-9 ''&./+-]*$'
    )
);
-- Two ACTIVE categories may never share a keyword (categorisation would be ambiguous). Deactivating a category
-- cascades category_active = FALSE onto its keywords, which frees them; reactivating cascades TRUE and fails with a
-- unique violation if another active category has claimed one meanwhile. This holds under concurrency.
CREATE UNIQUE INDEX uq_active_category_keyword ON incident_category_keywords (keyword) WHERE category_active;

-- The application inserts only (category_id, keyword); copy the owner's flag so the composite FK is satisfied.
CREATE FUNCTION incident_category_keywords_fill_active() RETURNS trigger AS $$
BEGIN
    SELECT active INTO NEW.category_active FROM incident_categories WHERE id = NEW.category_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_incident_category_keywords_fill_active
    BEFORE INSERT ON incident_category_keywords
    FOR EACH ROW EXECUTE FUNCTION incident_category_keywords_fill_active();

-- The nine categories from the project specification, so a fresh install can classify incidents immediately.
INSERT INTO incident_categories (code, name) VALUES
    ('HARDWARE', 'Hardware'), ('SOFTWARE', 'Software'), ('NETWORK', 'Network'), ('DATABASE', 'Database'),
    ('CLOUD', 'Cloud'), ('SECURITY', 'Security'), ('AUTHENTICATION', 'Authentication'),
    ('APPLICATION', 'Application'), ('STORAGE', 'Storage');

INSERT INTO incident_category_keywords (category_id, keyword)
SELECT c.id, k.keyword
FROM (VALUES
    ('HARDWARE', ARRAY['laptop', 'printer', 'monitor', 'keyboard', 'device']),
    ('SOFTWARE', ARRAY['install', 'crash', 'license', 'update', 'application error']),
    ('NETWORK', ARRAY['wifi', 'vpn', 'connection', 'latency', 'dns', 'router']),
    ('DATABASE', ARRAY['database', 'query', 'sql', 'deadlock', 'replication']),
    ('CLOUD', ARRAY['cloud', 'aws', 'azure', 'instance', 'deployment']),
    ('SECURITY', ARRAY['phishing', 'malware', 'breach', 'vulnerability', 'suspicious']),
    ('AUTHENTICATION', ARRAY['login', 'password', 'sso', 'mfa', 'locked out']),
    ('APPLICATION', ARRAY['payment', 'transaction', 'unavailable', 'error', 'outage']),
    ('STORAGE', ARRAY['disk', 'storage', 'backup', 'quota', 'file share'])
) AS seed (code, keywords)
JOIN incident_categories c ON c.code = seed.code
CROSS JOIN LATERAL unnest(seed.keywords) AS k (keyword);
