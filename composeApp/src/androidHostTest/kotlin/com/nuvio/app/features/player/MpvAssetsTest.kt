package com.nuvio.app.features.player

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MpvAssetsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun existingFontConfigurationAndLegacyFontArePreserved() {
        val config = folder.newFile("fonts.conf").apply { writeText("custom font search paths") }
        val font = folder.newFile("subfont.ttf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        prepareMpvAssets(folder.root, folder.root, { ByteArrayInputStream(byteArrayOf(4, 5)) })
        assertEquals("custom font search paths", config.readText())
        assertArrayEquals(byteArrayOf(1, 2, 3), font.readBytes())
        assertArrayEquals(byteArrayOf(4, 5), File(folder.root, "cacert.pem").readBytes())
    }

    @Test fun newInstallGetsFontconfigDefaultsAndAnEscapedCachePath() {
        val cache = folder.newFolder("cache & textures")
        prepareMpvAssets(folder.root, cache, { ByteArrayInputStream(byteArrayOf(4, 5)) })
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(folder.root, "fonts.conf"))
        assertEquals(cache.path, document.getElementsByTagName("cachedir").item(0).textContent)
        val dirs = document.getElementsByTagName("dir")
        assertEquals(listOf("/system/fonts/", "/product/fonts/"), (0 until dirs.length).map { dirs.item(it).textContent })
    }

    @Test fun missingCertificateAssetDoesNotDeleteFontsOrStopFontSetup() {
        val font = folder.newFile("subfont.ttf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var errors = 0
        prepareMpvAssets(folder.root, folder.root, { throw IOException("asset unavailable") }, { errors++ })
        assertEquals(1, errors)
        assertArrayEquals(byteArrayOf(1, 2, 3), font.readBytes())
        assertTrue(File(folder.root, "fonts.conf").isFile)
    }
}
