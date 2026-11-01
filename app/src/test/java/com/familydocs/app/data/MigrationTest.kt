package com.familydocs.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every database version an installed phone might still have (each one's schema is kept in app/schemas) upgrades to
 * the current one, and ends up exactly as the app expects it: a broken upgrade fails here instead of on a phone.
 */
@RunWith(RobolectricTestRunner::class)
// a plain Application: the app's own one opens the Android Keystore, which the JVM test runtime doesn't have
@Config(sdk = [34], application = android.app.Application::class)
class MigrationTest {
    private val folder = AppDatabase::class.java.canonicalName!!

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    private fun saved(): List<Int> =
        InstrumentationRegistry.getInstrumentation().context.assets.list(folder).orEmpty().mapNotNull { it.removeSuffix(".json").toIntOrNull() }.sorted()

    @Test fun everyOldVersionUpgradesToTheCurrentOne() {
        val versions = saved()
        if (versions.isEmpty()) return // schemas not exported yet (the first build after this test adds them)
        val latest = versions.last() // the current version's schema (written by every build)
        versions.filter { it < latest }.forEach { v ->
            val name = "upgrade-from-$v.db"
            helper.createDatabase(name, v).close()
            helper.runMigrationsAndValidate(name, latest, true, *ALL_MIGRATIONS).close()
        }
    }
}
