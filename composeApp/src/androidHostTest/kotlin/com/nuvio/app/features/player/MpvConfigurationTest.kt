package com.nuvio.app.features.player

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MpvConfigurationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun existingUserConfigurationAndBindingsAreLoadedInOrder() {
        val config = folder.newFile("mpv.conf").apply { writeText("sub-delay=0.5") }
        val bindings = folder.newFile("input.conf").apply { writeText("SPACE cycle pause") }
        val commands = mutableListOf<Pair<String, String>>()
        restoreLegacyMpvConfiguration(folder.root.path) { command, path -> commands += command to path }
        assertEquals(listOf("load-config-file" to config.path, "load-input-conf" to bindings.path), commands)
    }

    @Test fun absentUserConfigurationDoesNotIssueLoadCommands() {
        val commands = mutableListOf<Pair<String, String>>()
        restoreLegacyMpvConfiguration(folder.root.path) { command, path -> commands += command to path }
        assertEquals(emptyList<Pair<String, String>>(), commands)
    }

    @Test fun directoriesAreNotLoadedAsConfigurationFiles() {
        folder.newFolder("mpv.conf")
        folder.newFolder("input.conf")
        val commands = mutableListOf<Pair<String, String>>()
        restoreLegacyMpvConfiguration(folder.root.path) { command, path -> commands += command to path }
        assertEquals(emptyList<Pair<String, String>>(), commands)
    }
}
