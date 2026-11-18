package app.datame.movil

import org.junit.Assert.assertEquals
import org.junit.Test

class PathsTest {
    @Test
    fun joinsWithOneSlash() {
        assertEquals("/Móvil/Ana/Camera/2026/IMG_1.jpg", SyncWorker.join("/Móvil/Ana/", "Camera/2026", "IMG_1.jpg"))
        assertEquals("/Móvil/Documentos/a.pdf", SyncWorker.join("Móvil", "/Documentos/", "a.pdf"))
    }

    @Test
    fun numbersAName() {
        assertEquals("/a/IMG_1 (2).jpg", SyncWorker.numbered("/a/IMG_1.jpg", 2))
        assertEquals("/a/IMG_1 (3).jpg", SyncWorker.numbered("/a/IMG_1 (2).jpg", 3))
        assertEquals("/a/LEEME (2)", SyncWorker.numbered("/a/LEEME", 2))
        assertEquals("/a/.bashrc (2)", SyncWorker.numbered("/a/.bashrc", 2))
    }

    @Test
    fun safeNames() {
        assertEquals("a-b", Sources.safeName("a/b"))
        assertEquals("Sin nombre", Sources.safeName("  "))
    }
}
