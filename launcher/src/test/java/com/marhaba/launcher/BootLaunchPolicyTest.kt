package com.marhaba.launcher

import org.junit.Assert.*
import org.junit.Test

class BootLaunchPolicyTest {
    @Test fun startsOnlyOnNewBoot() {
        assertFalse(BootLaunchPolicy.shouldLaunch(null, 10))
        assertFalse(BootLaunchPolicy.shouldLaunch(10, 10))
        assertTrue(BootLaunchPolicy.shouldLaunch(10, 11))
        assertFalse(BootLaunchPolicy.shouldLaunch(10, -1))
    }
}
