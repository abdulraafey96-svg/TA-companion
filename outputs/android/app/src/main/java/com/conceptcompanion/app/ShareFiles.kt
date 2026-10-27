package com.conceptcompanion.app

import android.content.Intent
import android.net.Uri

/** A pure helper so supported share actions and URI extraction can be unit tested. */
object ShareFiles {
    fun uris(intent: Intent?): List<Uri> {
        if (intent == null || (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE)) return emptyList()
        val result = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_SEND) (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let(result::add)
        else {
            @Suppress("DEPRECATION")
            val many = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            many?.let(result::addAll)
        }
        intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(result::add) }
        return result.distinct()
    }
    fun supported(mime: String?): Boolean = mime == "application/pdf" || mime?.startsWith("image/") == true
}
