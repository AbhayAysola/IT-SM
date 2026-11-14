-- GIN index for full-text duplicate candidate search used by IncidentAnalysisService.
-- Covers both title and description; 'english' dictionary applies stemming and stop-word removal.
-- The index expression must match the query expression in IncidentAnalysisService exactly.
CREATE INDEX idx_incidents_fts ON incidents
    USING GIN (to_tsvector('english', title || ' ' || description));
