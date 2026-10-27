package com.conceptcompanion.app

import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test

class ShareFilesTest {
    @Test fun singlePdfShareReadsExtraStream() {
        val uri = Uri.parse("content://notes/export.pdf")
        val intent = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
        assertEquals(listOf(uri), ShareFiles.uris(intent))
        assertTrue(ShareFiles.supported(intent.type))
    }
    @Test fun multipleImageShareReadsAllStreams() {
        val a = Uri.parse("content://notes/a.png"); val b = Uri.parse("content://notes/b.jpg")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b))
        assertEquals(listOf(a, b), ShareFiles.uris(intent))
        assertTrue(ShareFiles.supported(intent.type))
    }
    @Test fun missingStreamIsEmptyAndUnsupportedIntentIgnored() {
        assertTrue(ShareFiles.uris(Intent(Intent.ACTION_SEND)).isEmpty())
        assertTrue(ShareFiles.uris(Intent(Intent.ACTION_VIEW)).isEmpty())
        assertFalse(ShareFiles.supported("text/plain"))
    }
}
