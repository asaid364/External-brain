package app.liferecord.core

object RecordSchema {
    const val VERSION = 4

    fun create(database: SqlDatabase) {
        database.exec("CREATE TABLE records(id TEXT PRIMARY KEY, body TEXT NOT NULL, local TEXT NOT NULL, zone TEXT NOT NULL, offset TEXT NOT NULL, epoch INTEGER NOT NULL, created TEXT NOT NULL, modified TEXT, deleted TEXT, report_revision INTEGER NOT NULL DEFAULT 0, report_lifecycle INTEGER NOT NULL DEFAULT 0)")
        database.exec("CREATE TABLE drafts(id TEXT PRIMARY KEY, record_id TEXT UNIQUE REFERENCES records(id) ON DELETE CASCADE, new_slot INTEGER UNIQUE CHECK(new_slot=1), body TEXT NOT NULL, local TEXT NOT NULL, zone TEXT NOT NULL, offset TEXT, CHECK((record_id IS NULL AND new_slot IS NOT NULL) OR (record_id IS NOT NULL AND new_slot IS NULL)))")
        database.exec("CREATE TABLE revisions(id INTEGER PRIMARY KEY AUTOINCREMENT, record_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE, before_body TEXT NOT NULL, before_local TEXT NOT NULL, before_zone TEXT NOT NULL, before_offset TEXT NOT NULL, before_epoch INTEGER NOT NULL, after_body TEXT NOT NULL, after_local TEXT NOT NULL, after_zone TEXT NOT NULL, after_offset TEXT NOT NULL, after_epoch INTEGER NOT NULL, modified TEXT NOT NULL)")
        createReportSchema(database)
        SchemaV3.install(database)
        GoalSchema.install(database)
    }

    fun upgrade(database: SqlDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion in 1..newVersion && newVersion <= VERSION) { "不支持的数据库版本，未修改原有内容" }
        if (oldVersion < 2) {
            database.exec("ALTER TABLE records ADD COLUMN report_revision INTEGER NOT NULL DEFAULT 0")
            database.exec("ALTER TABLE records ADD COLUMN report_lifecycle INTEGER NOT NULL DEFAULT 0")
            createReportSchema(database)
        }
        if (oldVersion < 3) SchemaV3.install(database)
        if (oldVersion < 4) GoalSchema.install(database)
    }

    private fun createReportSchema(database: SqlDatabase) {
        database.exec("CREATE TABLE IF NOT EXISTS report_days(date TEXT PRIMARY KEY, input_hash TEXT NOT NULL, generated_at TEXT NOT NULL, service TEXT NOT NULL, model TEXT NOT NULL)")
        database.exec("CREATE TABLE IF NOT EXISTS report_paragraphs(date TEXT NOT NULL, position INTEGER NOT NULL, text TEXT NOT NULL, PRIMARY KEY(date,position), FOREIGN KEY(date) REFERENCES report_days(date) ON DELETE CASCADE)")
        database.exec("CREATE TABLE IF NOT EXISTS report_paragraph_records(date TEXT NOT NULL, paragraph_position INTEGER NOT NULL, position INTEGER NOT NULL, record_id TEXT NOT NULL, PRIMARY KEY(date,paragraph_position,position), FOREIGN KEY(date,paragraph_position) REFERENCES report_paragraphs(date,position) ON DELETE CASCADE)")
        database.exec("CREATE TABLE IF NOT EXISTS report_paragraph_answers(date TEXT NOT NULL, paragraph_position INTEGER NOT NULL, position INTEGER NOT NULL, answer_id TEXT NOT NULL, PRIMARY KEY(date,paragraph_position,position), FOREIGN KEY(date,paragraph_position) REFERENCES report_paragraphs(date,position) ON DELETE CASCADE)")
        database.exec("CREATE TABLE IF NOT EXISTS report_sources(date TEXT NOT NULL, position INTEGER NOT NULL, record_id TEXT NOT NULL, version TEXT NOT NULL, PRIMARY KEY(date,position), UNIQUE(date,record_id), FOREIGN KEY(date) REFERENCES report_days(date) ON DELETE CASCADE)")
        database.exec("CREATE TABLE IF NOT EXISTS report_questions(date TEXT NOT NULL, id TEXT NOT NULL, text TEXT NOT NULL, answer TEXT NOT NULL DEFAULT '', answered_at TEXT, skipped INTEGER NOT NULL DEFAULT 0, applicable INTEGER NOT NULL DEFAULT 1, position INTEGER NOT NULL, PRIMARY KEY(date,id))")
        database.exec("CREATE TABLE IF NOT EXISTS report_question_records(date TEXT NOT NULL, question_id TEXT NOT NULL, position INTEGER NOT NULL, record_id TEXT NOT NULL, source_version TEXT NOT NULL, PRIMARY KEY(date,question_id,position), FOREIGN KEY(date,question_id) REFERENCES report_questions(date,id) ON DELETE CASCADE)")
        database.exec("CREATE TABLE IF NOT EXISTS report_answer_history(id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, question_id TEXT NOT NULL, answer TEXT NOT NULL, skipped INTEGER NOT NULL, changed_at TEXT NOT NULL)")
        database.exec("CREATE TABLE IF NOT EXISTS report_runs(date TEXT PRIMARY KEY, running INTEGER NOT NULL DEFAULT 0, automatic_attempt INTEGER NOT NULL DEFAULT 0, generation INTEGER NOT NULL DEFAULT 0, claim_token TEXT, status TEXT NOT NULL DEFAULT '', message TEXT NOT NULL DEFAULT '', retry_at INTEGER)")
        database.exec("CREATE TABLE IF NOT EXISTS report_service_cooldowns(service TEXT PRIMARY KEY, until_epoch INTEGER NOT NULL)")
    }
}
