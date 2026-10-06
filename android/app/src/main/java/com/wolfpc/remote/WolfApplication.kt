package com.wolfpc.remote

import android.app.Application
import com.wolfpc.remote.data.SessionStore
import com.wolfpc.remote.data.net.LanClient
import com.wolfpc.remote.data.net.RealtimeClient
import com.wolfpc.remote.data.net.SupabaseClient
import com.wolfpc.remote.domain.CommandSender
import com.wolfpc.remote.notif.Notifier
import com.wolfpc.remote.notif.StateNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/**
 * Manual dependency container.
 *
 * Six objects and no cycles — a DI framework would add a build step and an
 * annotation processor to save nothing.
 */
class WolfApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val sessionStore by lazy { SessionStore(this) }
    val supabase by lazy { SupabaseClient(sessionStore) }
    val lan by lazy { LanClient() }
    val realtime by lazy { RealtimeClient(sessionStore, appScope) }
    val commandSender by lazy { CommandSender(sessionStore, supabase, lan) }
    val notifier by lazy { Notifier(this) }
    val stateNotifier by lazy { StateNotifier(notifier) }

    override fun onCreate() {
        super.onCreate()
        // Created before anything can post: Android silently drops a
        // notification whose channel does not exist yet.
        notifier.createChannels()
    }
}
