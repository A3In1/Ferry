package dev.ferry.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Phase6Test {
    private val tmp = Files.createTempDirectory("ferry6").toFile()
    private val root = File(tmp, "root").apply { mkdirs() }
    private val outside = File(tmp, "outside").apply { mkdirs() }

    @After fun cleanup() { tmp.deleteRecursively() }

    @Test
    fun newGrantsOff() {
        assertEquals(emptySet<Grant>(), Grants.read { _, d -> d })
        assertEquals("Let your Mac browse this phone's storage and copy files from it", Grant.FILES.why)
    }

    @Test
    fun resolvesInsideRoot() {
        File(root, "DCIM/Camera").mkdirs()
        assertEquals(root.canonicalFile, PhoneFiles.resolve(root, ""))
        assertEquals(File(root, "DCIM/Camera").canonicalFile, PhoneFiles.resolve(root, "DCIM/Camera"))
        assertEquals(File(root, "DCIM/Camera").canonicalFile, PhoneFiles.resolve(root, "DCIM//Camera/"))
        assertEquals(File(root, "Not/There").canonicalFile, PhoneFiles.resolve(root, "Not/There")) // missing is the caller's check
    }

    @Test
    fun rejectsEscapes() {
        for (bad in listOf("..", "../outside", "DCIM/../../outside", "DCIM/..", "/etc/passwd", "/", "\\x", "a\u0000b")) {
            assertNull(bad, PhoneFiles.resolve(root, bad))
        }
        assertNull(PhoneFiles.resolve(root, "../root/x")) // even if it would land back inside
    }

    @Test
    fun rejectsSymlinkEscape() {
        File(outside, "secret.txt").writeText("x")
        val link = File(root, "link").toPath()
        try {
            Files.createSymbolicLink(link, outside.toPath())
        } catch (e: Exception) {
            return // no symlinks on this filesystem
        }
        assertNull(PhoneFiles.resolve(root, "link"))
        assertNull(PhoneFiles.resolve(root, "link/secret.txt"))
        assertEquals(emptyList<File>(), PhoneFiles.files(root, listOf("link/secret.txt")).first)
    }

    @Test
    fun listsFoldersFirstSkipsHidden() {
        File(root, "b.txt").writeText("hello")
        File(root, "A.txt").writeText("x")
        File(root, ".hidden").writeText("x")
        File(root, "zDir").mkdirs()
        File(root, "Music").mkdirs()
        val l = PhoneFiles.list(root, "")
        assertEquals("", l.error)
        assertEquals(listOf("Music", "zDir", "A.txt", "b.txt"), l.entries.map { it.name })
        assertEquals(listOf(true, true, false, false), l.entries.map { it.dir })
        assertEquals(5uL, l.entries.first { it.name == "b.txt" }.size)
    }

    @Test
    fun listErrorsAndCap() {
        File(root, "f.txt").writeText("x")
        assertTrue(PhoneFiles.list(root, "../outside").error.isNotEmpty())
        assertTrue(PhoneFiles.list(root, "f.txt").error.isNotEmpty())
        assertTrue(PhoneFiles.list(root, "missing").error.isNotEmpty())
        val big = File(root, "big").apply { mkdirs() }
        repeat(PhoneFiles.MAX_ENTRIES + 5) { File(big, "f$it").writeText("") }
        assertEquals(PhoneFiles.MAX_ENTRIES, PhoneFiles.list(root, "big").entries.size)
    }

    @Test
    fun getTakesRegularFilesOnly() {
        File(root, "Docs").mkdirs()
        File(root, "Docs/a.pdf").writeText("x")
        val (ok, bad) = PhoneFiles.files(root, listOf("Docs/a.pdf", "Docs", "", "../outside", "nope.txt"))
        assertEquals(listOf(File(root, "Docs/a.pdf").canonicalFile), ok)
        assertEquals(listOf("Docs", "", "../outside", "nope.txt"), bad)
    }
}
