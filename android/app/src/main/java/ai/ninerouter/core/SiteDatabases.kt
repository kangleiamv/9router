package ai.ninerouter.core

/**
 * Pure helpers for on-device MySQL/MariaDB databases owned by hosted sites.
 *
 * The app connects with the bundled PHP PDO MySQL driver, using the
 * credentials of the MariaDB service it starts, so no external client binary
 * is needed. Databases are named after the site that owns them and every
 * statement is built from a validated identifier.
 */
object SiteDatabases {
    const val MAX_NAME = 40

    /** Schemas every server owns; the manager never lists them as user data. */
    val SYSTEM_SCHEMAS = setOf("information_schema", "mysql", "performance_schema", "sys")

    data class DbInfo(val name: String, val bytes: Long) {
        val user: Boolean get() = name !in SYSTEM_SCHEMAS
    }

    /** Sanitise a user value into a safe MySQL identifier. */
    fun normalize(raw: String): String {
        val cleaned = raw.trim().lowercase()
            .replace(Regex("[^a-z0-9_]"), "_")
            .trim('_')
            .take(MAX_NAME)
        require(cleaned.isNotEmpty()) { "Database names need at least one letter or digit." }
        return cleaned
    }

    /** Default schema for a hosted site, derived from its stable id. */
    fun siteDatabase(serviceId: String): String = normalize("site_$serviceId")

    fun createSql(name: String): String =
        "CREATE DATABASE IF NOT EXISTS ${quote(normalize(name))} " +
            "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"

    fun dropSql(name: String): String = "DROP DATABASE IF EXISTS ${quote(normalize(name))}"

    private fun quote(name: String): String {
        require(name.matches(Regex("[a-z0-9_]{1,$MAX_NAME}"))) {
            "Invalid database name '$name'."
        }
        return "`$name`"
    }

    /** MySQL URI shown in the database tab and injected as DATABASE_URL. */
    fun url(host: String, port: Int, name: String, user: String, password: String): String {
        val safePassword = java.net.URLEncoder.encode(password, "UTF-8")
        return "mysql://$user:$safePassword@$host:$port/$name"
    }

    fun parseList(output: String): List<DbInfo> {
        val lines = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.firstOrNull() != "OK") return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val name = line.substringBefore('\t').trim()
            if (!name.matches(Regex("[a-zA-Z0-9_$]{1,64}"))) return@mapNotNull null
            val bytes = line.substringAfter('\t', "0").toLongOrNull() ?: 0L
            DbInfo(name, bytes)
        }
    }

    /**
     * PDO program for the bundled PHP binary. Connection details and SQL are
     * passed through the environment, never through generated code.
     */
    val ADMIN_SCRIPT: String = """
        try {
            ${'$'}pdo = new PDO(
                "mysql:host=127.0.0.1;port=" . getenv("DB_PORT") . ";charset=utf8mb4",
                getenv("DB_USER"),
                getenv("DB_PASS"),
                [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_TIMEOUT => 8]
            );
            ${'$'}sql = getenv("DB_SQL");
            if (${'$'}sql === false || ${'$'}sql === "") {
                ${'$'}rows = ${'$'}pdo->query("SELECT table_schema AS name, COALESCE(SUM(data_length + index_length), 0) AS bytes FROM information_schema.tables GROUP BY table_schema ORDER BY table_schema")->fetchAll(PDO::FETCH_ASSOC);
                echo "OK\n";
                foreach (${'$'}rows as ${'$'}row) { echo ${'$'}row["name"] . "\t" . ${'$'}row["bytes"] . "\n"; }
            } else {
                ${'$'}pdo->exec(${'$'}sql);
                echo "OK";
            }
        } catch (Throwable ${'$'}e) {
            fwrite(STDERR, ${'$'}e->getMessage());
            exit(1);
        }
    """.trimIndent()

    /** Program that returns the server banner, used by "Test connection". */
    val VERSION_SCRIPT: String = """
        try {
            ${'$'}pdo = new PDO(
                "mysql:host=127.0.0.1;port=" . getenv("DB_PORT") . ";charset=utf8mb4",
                getenv("DB_USER"),
                getenv("DB_PASS"),
                [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_TIMEOUT => 8]
            );
            echo "OK " . ${'$'}pdo->query("SELECT VERSION()")->fetchColumn();
        } catch (Throwable ${'$'}e) {
            fwrite(STDERR, ${'$'}e->getMessage());
            exit(1);
        }
    """.trimIndent()
}
