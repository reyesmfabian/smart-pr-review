package com.fabianreyes.smartprreview.git

import com.fabianreyes.smartprreview.git.ChangedFile.Status.A
import com.fabianreyes.smartprreview.git.ChangedFile.Status.D
import com.fabianreyes.smartprreview.git.ChangedFile.Status.M
import com.fabianreyes.smartprreview.git.ChangedFile.Status.R
import org.junit.Assert.assertEquals
import org.junit.Test

class GitParsersTest {
    @Test
    fun `name-status with renames and copies`() { // Review Focus 5
        val z = "\u0000"
        val out = "M${z}src/a.kt${z}R087${z}old/name.kt${z}new/name.kt${z}C075${z}src/a.kt${z}src/copy.kt${z}A${z}added.kt${z}D${z}gone.kt${z}T${z}mode.sh${z}"
        assertEquals(
            listOf(
                ChangedFile(M, "src/a.kt"),
                ChangedFile(R, "new/name.kt", oldPath = "old/name.kt"),
                ChangedFile(A, "src/copy.kt"),
                ChangedFile(A, "added.kt"),
                ChangedFile(D, "gone.kt"),
                ChangedFile(M, "mode.sh"),
            ),
            parseNameStatus(out),
        )
        assertEquals(emptyList<ChangedFile>(), parseNameStatus(""))
    }

    @Test
    fun `hunk ranges with and without context`() {
        val diff = "@@ -1,3 +1,4 @@\n+a\n@@ -10 +11 @@\n x\n@@ -20,2 +22,0 @@\n-gone\n-gone\n"
        assertEquals(listOf(LineRange(1, 4), LineRange(11, 11)), parseHunkRanges(diff))
        assertEquals(listOf(LineRange(1, 4), LineRange(11, 11), LineRange(22, 23)), parseChangedRanges(diff))
        // A deletion at the very top ("+0,0") still points at line 1.
        assertEquals(listOf(LineRange(1, 1)), parseChangedRanges("@@ -1,2 +0,0 @@\n-x\n-y\n"))
        assertEquals(emptyList<LineRange>(), parseHunkRanges(""))
    }
}
