package com.burgess.schedule.school.sync.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CdpProbeTest {
    @Test fun stripsQueryFragmentAndUserInfo() {
        assertEquals(
            "https://testjwgl.sdbi.edu.cn/h5/index.html",
            safePageUrl("https://someone:secret@testjwgl.sdbi.edu.cn/h5/index.html?JSESSIONID=secret&token=abc#private")
        )
    }

    @Test fun rejectsNonWebTargets() {
        assertNull(safePageUrl("file:///data/user/0/private"))
        assertNull(safePageUrl("javascript:alert(1)"))
    }
}
