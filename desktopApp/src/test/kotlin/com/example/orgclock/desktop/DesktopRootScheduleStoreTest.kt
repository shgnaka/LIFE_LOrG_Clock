package com.example.orgclock.desktop

import com.example.orgclock.template.RootScheduleConfig
import com.example.orgclock.template.ScheduleRuleType
import com.example.orgclock.template.ScheduleWeekday
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopRootScheduleStoreTest {
    private val nodes = mutableListOf<Preferences>()

    @AfterTest
    fun cleanup() {
        nodes.asReversed().forEach { node ->
            runCatching {
                node.removeNode()
                node.parent()?.flush()
            }
        }
        nodes.clear()
    }

    @Test
    fun saveAndLoad_roundTripsTemplateAndScheduleConfig() {
        val store = DesktopRootScheduleStore(testNode())
        val config = RootScheduleConfig(
            rootUri = "/tmp/org-root",
            enabled = true,
            ruleType = ScheduleRuleType.Weekly,
            hour = 23,
            minute = 59,
            daysOfWeek = setOf(ScheduleWeekday.Monday, ScheduleWeekday.Friday),
            templateFileUri = "/tmp/org-root/templates/custom.org",
        )

        store.save(config)

        assertEquals(config, store.load(config.rootUri))
    }

    @Test
    fun load_defaultsWhenNothingPersisted() {
        val store = DesktopRootScheduleStore(testNode())

        assertEquals(
            RootScheduleConfig(rootUri = "/tmp/org-root"),
            store.load("/tmp/org-root"),
        )
    }

    @Test
    fun save_removesTemplateFileUriWhenCleared() {
        val store = DesktopRootScheduleStore(testNode())
        val rootUri = "/tmp/org-root"

        store.save(RootScheduleConfig(rootUri = rootUri, templateFileUri = "/tmp/org-root/template.org"))
        store.save(RootScheduleConfig(rootUri = rootUri, templateFileUri = null))

        assertEquals(null, store.load(rootUri).templateFileUri)
    }

    @Test
    fun load_clampsInvalidTimeAndFallsBackForInvalidRuleOrDays() {
        val prefs = testNode()
        val store = DesktopRootScheduleStore(prefs)
        val prefix = rootPrefix("/tmp/org-root")
        prefs.put("${prefix}rule_type", "NotARule")
        prefs.putInt("${prefix}hour", 99)
        prefs.putInt("${prefix}minute", -5)
        prefs.put("${prefix}days_of_week", "0,99")
        prefs.flush()

        assertEquals(
            RootScheduleConfig(
                rootUri = "/tmp/org-root",
                ruleType = ScheduleRuleType.Daily,
                hour = 23,
                minute = 0,
                daysOfWeek = setOf(ScheduleWeekday.Monday),
            ),
            store.load("/tmp/org-root"),
        )
    }

    private fun testNode(): Preferences =
        Preferences.userRoot()
            .node("com/example/orgclock/desktop/test/root-schedule/${System.nanoTime()}")
            .also(nodes::add)

    private fun rootPrefix(rootUri: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(rootUri.toByteArray())
        val hash = digest.take(8).joinToString("") { "%02x".format(it) }
        return "rs_${hash}_"
    }
}
