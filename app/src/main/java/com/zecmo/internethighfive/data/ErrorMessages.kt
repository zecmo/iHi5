package com.zecmo.internethighfive.data

import java.io.IOException
import java.net.UnknownHostException

/**
 * Turns a thrown exception into something a player can read.
 *
 * Raw `e.message` from supabase-kt embeds the full REST URL, the project host, row ids
 * and the request headers — including `Authorization=[Bearer ...]` and `apikey`. Those
 * were being rendered directly on screen, which is both unreadable and leaks internals
 * to anyone glancing at the phone. The detail still belongs in logcat; never in the UI.
 */
fun friendlyError(action: String, e: Throwable): String {
    val raw = e.message.orEmpty()
    return when {
        e is UnknownHostException || raw.contains("Unable to resolve host", true) ->
            "$action — you appear to be offline."

        raw.contains("timeout", true) ->
            "$action — the connection timed out. Try again."

        // 42501 = RLS rejection. In practice this means the request ran before the
        // auth session finished loading, so it went out unauthenticated.
        raw.contains("42501") || raw.contains("row-level security", true) ->
            "$action — not signed in yet. Give it a second and try again."

        raw.contains("duplicate key", true) || raw.contains("23505") ->
            "$action — that already exists."

        e is IOException ->
            "$action — network problem. Check your connection."

        else -> "$action — something went wrong."
    }
}
