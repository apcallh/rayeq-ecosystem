package app.rayeq

import android.app.Application
import app.rayeq.auth.Auth
import app.rayeq.data.*

object Rayeq {
    fun init(app: Application) {
        Prefs.init(app)
        Library.init(app)
        Tmdb.init(app)
        SubSearch.init(app)
        Auth.init(app)
        AppInfo.init(app)
        SourceConfig.init(app)
        RevWatch.init(app)
        Presence.init()
        CloudSync.init(app)
        Notify.schedule(app)
    }
}
