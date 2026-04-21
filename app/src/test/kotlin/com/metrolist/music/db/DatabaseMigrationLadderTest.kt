package com.metrolist.music.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Static integrity checks for the Room migration ladder.
 *
 * These tests do NOT execute any SQL — they validate invariants you can read from the
 * repository state alone. They catch the most common ways migrations break between
 * releases:
 *   - Someone bumped `@Database(version = N)` but forgot to commit `schemas/N.json`.
 *   - Someone added a new `AutoMigration(from = X, to = Y)` but skipped a version.
 *   - A version N in the middle of the ladder has no migration pointing out of it
 *     (so upgrading past it would crash at runtime).
 *   - The JSON schema file's internal `database.version` doesn't match its filename.
 *
 * They are intentionally JVM-pure (no Android, no Room runtime, no emulator) so they
 * can run as a mandatory PR gate in under a second. Full SQL-executing migration
 * tests (MigrationTestHelper) are a separate concern and live in androidTest/.
 */
class DatabaseMigrationLadderTest {

    private val schemasDir: File by lazy { locateSchemasDir() }
    private val databaseSource: File by lazy { locateDatabaseSource() }

    @Test
    fun `schema files form a contiguous sequence from version 1`() {
        val versions = committedSchemaVersions()
        assertTrue("expected at least one committed schema under ${schemasDir.path}", versions.isNotEmpty())
        val expected = (1..versions.last()).toList()
        assertEquals(
            "schema versions must be contiguous with no gaps; missing=${expected - versions.toSet()}",
            expected,
            versions,
        )
    }

    @Test
    fun `code version equals highest committed schema version`() {
        val codeVersion = declaredDatabaseVersion()
        val highestSchema = committedSchemaVersions().last()
        assertEquals(
            "InternalDatabase declares version=$codeVersion but the highest committed schema is $highestSchema — " +
                "someone bumped the version without committing the new schema, or committed a schema without bumping.",
            highestSchema,
            codeVersion,
        )
    }

    @Test
    fun `each schema JSON declares a database version matching its filename`() {
        val versionRegex = Regex(""""version"\s*:\s*(\d+)""")
        for (version in committedSchemaVersions()) {
            val file = schemaFile(version)
            val match = versionRegex.find(file.readText())
                ?: fail("schema $file has no \"version\" field").let { return@let error("unreachable") }
            val declared = match.groupValues[1].toInt()
            assertEquals(
                "schema file ${file.name} declares version=$declared but its filename says $version",
                version,
                declared,
            )
        }
    }

    @Test
    fun `every version below the current has at least one outbound migration`() {
        val current = declaredDatabaseVersion()
        val migrations = declaredMigrations()
        val sources = migrations.map { it.first }.toSet()

        // For each intermediate version, there must be at least one migration that
        // uses it as the "from" side — otherwise a user on that version has no path
        // forward and the app will either crash or wipe data via destructive fallback.
        val orphans = (1 until current).filterNot { it in sources }
        assertTrue(
            "versions with no outgoing migration: $orphans " +
                "(each must appear as 'from' in at least one Migration or AutoMigration)",
            orphans.isEmpty(),
        )
    }

    @Test
    fun `all migration endpoints have a committed schema file`() {
        val schemas = committedSchemaVersions().toSet()
        val migrations = declaredMigrations()
        val missing = migrations
            .flatMap { (from, to) -> listOf(from, to) }
            .filterNot { it in schemas }
            .toSortedSet()
        assertTrue(
            "migrations reference versions with no committed schema: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `auto migrations step exactly one version at a time`() {
        // Room requires AutoMigration gaps of exactly 1. A gap >1 silently falls back
        // to destructive migration for affected users.
        val autos = declaredAutoMigrations()
        val badSteps = autos.filter { (from, to) -> to - from != 1 }
        assertTrue(
            "AutoMigration(from=X, to=Y) entries must satisfy Y == X+1: bad=$badSteps",
            badSteps.isEmpty(),
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun committedSchemaVersions(): List<Int> =
        schemasDir.listFiles { f -> f.name.endsWith(".json") }
            ?.map { it.nameWithoutExtension.toInt() }
            ?.sorted()
            ?: emptyList()

    private fun schemaFile(version: Int): File =
        File(schemasDir, "$version.json")

    private fun declaredDatabaseVersion(): Int {
        // Matches `version = 37,` inside the @Database annotation. We don't use
        // reflection because we don't want to pull Room into the test classpath.
        val regex = Regex("""version\s*=\s*(\d+)\s*,\s*\n\s*exportSchema""")
        val match = regex.find(databaseSource.readText())
            ?: error("could not find `version = N, exportSchema` pattern in ${databaseSource.name}")
        return match.groupValues[1].toInt()
    }

    private fun declaredAutoMigrations(): List<Pair<Int, Int>> {
        val regex = Regex("""AutoMigration\s*\(\s*from\s*=\s*(\d+)\s*,\s*to\s*=\s*(\d+)""")
        return regex.findAll(databaseSource.readText())
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toList()
    }

    /** Includes both AutoMigrations and manual `object : Migration(from, to)` definitions. */
    private fun declaredMigrations(): List<Pair<Int, Int>> {
        val autos = declaredAutoMigrations()
        val manualRegex = Regex("""Migration\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)""")
        val manuals = manualRegex.findAll(databaseSource.readText())
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toList()
        return autos + manuals
    }

    /** Resolves repo-relative paths regardless of which directory gradle picks as cwd. */
    private fun locateSchemasDir(): File = findUp("schemas/com.metrolist.music.db.InternalDatabase")

    private fun locateDatabaseSource(): File =
        findUp("src/main/kotlin/com/metrolist/music/db/MusicDatabase.kt")

    private fun findUp(relative: String): File {
        var cursor: File = File(".").absoluteFile
        repeat(6) {
            val candidate = File(cursor, relative)
            if (candidate.exists()) return candidate
            // Also try under an `app/` subdirectory — when tests run from the repo root.
            val candidateUnderApp = File(cursor, "app/$relative")
            if (candidateUnderApp.exists()) return candidateUnderApp
            cursor = cursor.parentFile ?: return@repeat
        }
        error("could not locate '$relative' walking up from ${File(".").absolutePath}")
    }
}
