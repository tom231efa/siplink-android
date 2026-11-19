-- The language an administrator chose for the console (design §5.5): a code of
-- internal/i18n such as 'zh-Hans' or 'ar'; '' = none chosen (the browser decides).
ALTER TABLE admins ADD COLUMN language TEXT NOT NULL DEFAULT '';
