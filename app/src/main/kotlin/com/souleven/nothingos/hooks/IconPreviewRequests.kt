package com.souleven.nothingos.hooks

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Messenger
import java.util.concurrent.atomic.AtomicReference

internal object IconPreviewRequests {
    val uri: Uri = Uri.parse("content://${RemotePreferenceProvider.AUTHORITY}/icon_previews")
    const val METHOD_TAKE = "take_icon_preview_request"
    const val KEY_COMPONENTS = "components"
    const val KEY_REPLY = "reply"

    private val pending = AtomicReference<Bundle>()

    fun request(context: Context, components: List<ComponentName>, reply: Messenger) {
        pending.set(Bundle().apply {
            putParcelableArrayList(KEY_COMPONENTS, ArrayList(components))
            putParcelable(KEY_REPLY, reply)
        })
        context.contentResolver.notifyChange(uri, null)
    }

    fun take(): Bundle? = pending.getAndSet(null)

    fun cancel(reply: Messenger) {
        pending.updateAndGet { request ->
            request?.takeUnless { it.getParcelable(KEY_REPLY, Messenger::class.java) == reply }
        }
    }
}
