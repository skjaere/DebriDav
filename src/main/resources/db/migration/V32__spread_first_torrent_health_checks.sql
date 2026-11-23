-- Torrents added before 1.0 have no last_verified (V17 added the column, V25 reconstructs rows
-- without one), so the first health-check poll after the upgrade would find the whole library
-- due at once. Give each a random last_verified within the past day instead: with the default
-- health-check.torrent-interval of one day, the first checks then spread over the next 24 hours,
-- leaving time to notice a misconfiguration before most of the library has been checked.
-- random() is evaluated per row. The column is a plain TIMESTAMP that Hibernate fills with UTC
-- wall-clock time, so take now() in UTC rather than in the session's (the JVM's) time zone.
UPDATE torrent
SET last_verified = (now() AT TIME ZONE 'UTC') - random() * interval '1 day'
WHERE last_verified IS NULL;
