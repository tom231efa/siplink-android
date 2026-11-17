-- The connection of external directories lives in external_directories since 0008
-- (design §2.5); a template's row keeps only what belongs to its use.

ALTER TABLE template_directories DROP COLUMN host;
ALTER TABLE template_directories DROP COLUMN port;
ALTER TABLE template_directories DROP COLUMN security;
ALTER TABLE template_directories DROP COLUMN bind_dn;
ALTER TABLE template_directories DROP COLUMN base_dn;
ALTER TABLE template_directories DROP COLUMN name_attributes;
ALTER TABLE template_directories DROP COLUMN number_attributes;
ALTER TABLE template_directories DROP COLUMN name_filter;
ALTER TABLE template_directories DROP COLUMN number_filter;

-- Second line behind the Go checks: a row refers to what its kind says.
CREATE TRIGGER template_directories_source_add BEFORE INSERT ON template_directories
WHEN (NEW.kind = 'external' AND NEW.external_id IS NULL) OR (NEW.kind = 'hosted' AND NEW.book_id IS NULL)
BEGIN SELECT RAISE(ABORT, 'directory without its source'); END;
CREATE TRIGGER template_directories_source_set BEFORE UPDATE ON template_directories
WHEN (NEW.kind = 'external' AND NEW.external_id IS NULL) OR (NEW.kind = 'hosted' AND NEW.book_id IS NULL)
BEGIN SELECT RAISE(ABORT, 'directory without its source'); END;
