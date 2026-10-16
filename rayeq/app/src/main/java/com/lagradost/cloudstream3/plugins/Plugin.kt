package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources
import com.lagradost.cloudstream3.BasePlugin

abstract class Plugin : BasePlugin() {
    var openSettings: ((Context) -> Unit)? = null
    var resources: Resources? = null

    open fun load(context: Context) {
        load()
    }

    override fun load() {}

    data class Manifest(
        val name: String? = null,
        val pluginClassName: String? = null,
        val version: Int = 1,
        val requiresResources: Boolean = false
    )
}
