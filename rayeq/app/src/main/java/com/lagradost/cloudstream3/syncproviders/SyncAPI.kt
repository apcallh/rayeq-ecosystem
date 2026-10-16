package com.lagradost.cloudstream3.syncproviders

// Stubs مكتفية بنفسها حتى تُحمَّل الإضافات التي تشير إليها دون أن تنهار
interface AuthAPI {
    val name: String
    val idPrefix: String
}

interface SyncAPI : AuthAPI

class SyncRepo(val api: SyncAPI)

data class AuthUser(val name: String?, val id: Int)

object AccountManager {
    val aniListApi = AniListApi()
}

class AniListApi : SyncAPI {
    override val name: String = "AniList"
    override val idPrefix: String = "anilist"
}
