package app.liferecord.core

/** Schema v4 storage for synchronized goals and device-local goal analysis state. */
object GoalSchema {
    fun install(database: SqlDatabase) {
        val firstInstall = !tableExists(database, "goals")
        database.exec("CREATE TABLE IF NOT EXISTS goals(id TEXT PRIMARY KEY,payload_json TEXT NOT NULL)")
        database.exec("CREATE TABLE IF NOT EXISTS goal_confirmations(goal_id TEXT NOT NULL,milestone_id TEXT NOT NULL,payload_json TEXT NOT NULL,PRIMARY KEY(goal_id,milestone_id))")
        database.exec("CREATE TABLE IF NOT EXISTS goal_reviews(review_key TEXT PRIMARY KEY,goal_id TEXT NOT NULL,from_date TEXT NOT NULL,through_date TEXT NOT NULL,payload_json TEXT NOT NULL)")
        database.exec("CREATE INDEX IF NOT EXISTS goal_reviews_goal ON goal_reviews(goal_id,from_date,through_date)")
        database.exec("CREATE TABLE IF NOT EXISTS goal_analysis_permissions(goal_id TEXT PRIMARY KEY,spec_hash TEXT NOT NULL,endpoint TEXT NOT NULL,model TEXT NOT NULL,send_allowed INTEGER NOT NULL DEFAULT 0,automatic_allowed INTEGER NOT NULL DEFAULT 0)")
        database.exec("CREATE TABLE IF NOT EXISTS goal_review_runs(review_key TEXT PRIMARY KEY,run_id TEXT NOT NULL,state TEXT NOT NULL,input_hash TEXT NOT NULL,context_json TEXT NOT NULL,pending_json TEXT,request_sent INTEGER NOT NULL DEFAULT 0,external_attempted INTEGER NOT NULL DEFAULT 0,message TEXT NOT NULL DEFAULT '',retry_at INTEGER,started_at INTEGER NOT NULL)")
        database.exec("CREATE TABLE IF NOT EXISTS goal_review_coord(review_key TEXT PRIMARY KEY,run_id TEXT NOT NULL,holder TEXT NOT NULL,input_hash TEXT NOT NULL,token TEXT NOT NULL,state TEXT NOT NULL,expires_at INTEGER NOT NULL)")
        database.exec("CREATE TABLE IF NOT EXISTS goal_review_known(review_key TEXT PRIMARY KEY,state TEXT NOT NULL,peer_database_id TEXT NOT NULL)")
        if (firstInstall && tableExists(database, "sync_peer")) {
            database.exec("UPDATE sync_peer SET send_consent=0,receive_consent=0")
        }
    }

    private fun tableExists(database: SqlDatabase, name: String): Boolean =
        database.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", listOf(name)).use { it.next() }
}
