package com.marhaba.launcher

/** A fresh install establishes a baseline; only a subsequent boot starts playback. */
object BootLaunchPolicy {
    fun shouldLaunch(previous: Int?, current: Int): Boolean =
        previous != null && current >= 0 && previous != current
}
