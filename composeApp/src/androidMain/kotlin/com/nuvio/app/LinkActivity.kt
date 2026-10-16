package com.nuvio.app

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import com.nuvio.app.features.settings.AppIconPlatform

class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incomingIntent = intent
        val link = incomingIntent?.dataString
        if (incomingIntent != null && (link != null || incomingIntent.action == Intent.ACTION_SEND)) {
            startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = AppIconPlatform.currentLauncherComponent(this@LinkActivity)
                    putExtra(EXTRA_LINK, link)
                    // Keep Reshaped playlist shares intact while reusing the launcher task.
                    putExtra(EXTRA_INCOMING_INTENT, incomingIntent)
                    clipData = incomingIntent.clipData
                        ?: incomingIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                            ?.let { ClipData.newRawUri("shared playlist", it) }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        (incomingIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        }
        finish()
    }

    companion object {
        const val EXTRA_LINK = "link"
        internal const val EXTRA_INCOMING_INTENT = "reshaped_incoming_intent"
    }
}
