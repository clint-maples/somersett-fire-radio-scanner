package com.clintmaples.broadcastifyscanner.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens public Broadcastify Calls pages in Custom Tabs (browser fallback).
 * Does not fetch or play audio.
 */
object CallsOpener {
    fun open(context: Context, urls: List<String>) {
        val safe = urls.mapNotNull { sanitize(it) }.distinct()
        safe.forEach { url ->
            val uri = Uri.parse(url)
            val tabs = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .build()
            tabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                tabs.launchUrl(context, uri)
            } catch (_: ActivityNotFoundException) {
                val view = Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(view)
            }
        }
    }

    private fun sanitize(url: String): String? {
        if (!CallsPages.isPublicCallsTalkgroupUrl(url)) return null
        return url
    }
}
