-- Stratified sample of in-stock JLCPCB rows: up to 5000 per Second Category for the 40 largest, plus 2000 overall.
-- Deterministic (rowid hash instead of random()). Run:
--   rm -f $WORK/sample.db
--   sqlite3 "file:/var/tmp/kina-bench/parts-fts5.db?mode=ro" ".read scripts/research/extraction_coverage/sample.sql"
-- (WORK defaults to /var/tmp/kina-bench/extract; edit the ATTACH path below to change it.)
.timeout 600000
ATTACH 'file:/var/tmp/kina-bench/extract/sample.db?mode=rwc' AS s;
CREATE TABLE s.sample(rowid_ INTEGER, strat TEXT, "LCSC Part", "First Category","Second Category","MFR.Part","Package","Solder Joint","Manufacturer","Library Type","Description","Datasheet","Price","Stock");
CREATE TEMP TABLE top AS SELECT "Second Category" c, count(*) n FROM parts WHERE CAST(Stock AS INTEGER)>0 GROUP BY 1 ORDER BY n DESC LIMIT 40;
INSERT INTO s.sample SELECT rowid,'cat',"LCSC Part","First Category","Second Category","MFR.Part","Package","Solder Joint","Manufacturer","Library Type","Description","Datasheet","Price","Stock" FROM (SELECT rowid,*, row_number() OVER (PARTITION BY "Second Category" ORDER BY (rowid*2654435761)%4294967291) rn FROM parts WHERE CAST(Stock AS INTEGER)>0 AND "Second Category" IN (SELECT c FROM top)) WHERE rn<=5000;
INSERT INTO s.sample SELECT rowid,'random',"LCSC Part","First Category","Second Category","MFR.Part","Package","Solder Joint","Manufacturer","Library Type","Description","Datasheet","Price","Stock" FROM parts WHERE CAST(Stock AS INTEGER)>0 ORDER BY (rowid*40503+12345)%4294967291 LIMIT 2000;
SELECT strat, count(*) FROM s.sample GROUP BY 1;
