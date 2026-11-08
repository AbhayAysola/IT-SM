CREATE TABLE incident_categories (
    id          BIGSERIAL    PRIMARY KEY,
    code        VARCHAR(40)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    keywords    TEXT[]       NOT NULL DEFAULT '{}',   -- feeds P2 smart categorisation
    active      BOOLEAN      NOT NULL DEFAULT TRUE,   -- soft delete only: incidents reference rows
    CONSTRAINT chk_category_code CHECK (code ~ '^[A-Z][A-Z0-9_]*$')
);

INSERT INTO incident_categories (code, name, keywords) VALUES
    ('HARDWARE',       'Hardware',       ARRAY['laptop','printer','monitor','keyboard','device']),
    ('SOFTWARE',       'Software',       ARRAY['install','crash','license','update','application error']),
    ('NETWORK',        'Network',        ARRAY['wifi','vpn','connection','latency','dns','router']),
    ('DATABASE',       'Database',       ARRAY['database','query','sql','deadlock','replication']),
    ('CLOUD',          'Cloud',          ARRAY['cloud','aws','azure','instance','deployment']),
    ('SECURITY',       'Security',       ARRAY['phishing','malware','breach','vulnerability','suspicious']),
    ('AUTHENTICATION', 'Authentication', ARRAY['login','password','sso','mfa','locked out']),
    ('APPLICATION',    'Application',    ARRAY['payment','transaction','unavailable','error','outage']),
    ('STORAGE',        'Storage',        ARRAY['disk','storage','backup','quota','file share']);
