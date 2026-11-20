package io.github.mangi.flyna

import android.app.Application
import io.github.mangi.flyna.apps.LauncherAppRepository
import io.github.mangi.flyna.apps.SidebarEntryRepository
import io.github.mangi.flyna.framework.FrameworkConnectionRepository

class FlynaApplication : Application() {
    internal lateinit var frameworkConnectionRepository: FrameworkConnectionRepository
        private set
    internal lateinit var launcherAppRepository: LauncherAppRepository
        private set

    internal lateinit var sidebarEntryRepository: SidebarEntryRepository
        private set

    override fun onCreate() {
        super.onCreate()
        frameworkConnectionRepository = FrameworkConnectionRepository(this).also { it.start() }
        sidebarEntryRepository = SidebarEntryRepository(this)
        launcherAppRepository = LauncherAppRepository(this).also { it.refresh() }
    }
}
