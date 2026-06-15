package com.zecmo.internethighfive

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime

object SupabaseClient {
    val client = createSupabaseClient(
        supabaseUrl = "https://vhmggyahyhriwhhqacbr.supabase.co",
        supabaseKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZobWdneWFoeWhyaXdoaHFhY2JyIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODAyODAyMTAsImV4cCI6MjA5NTg1NjIxMH0._hh3r1Ngt4wFuwjjrAJtGwJCc2i3g15R82bmSTD40OQ"
    ) {
        install(Auth)
        install(Functions)
        install(Postgrest)
        install(Realtime)
    }
}
