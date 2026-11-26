package ua.olex.music.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetryAfterTest {
    @Test fun parsesBothHttpFormsWithoutOverflow() {
        assertEquals(12_000L, RetryAfter.milliseconds(" 12 ", 7))
        assertEquals(60_000L, RetryAfter.milliseconds("Wed, 21 Oct 2015 07:28:00 GMT", 1_445_412_420_000))
        assertEquals(0L, RetryAfter.milliseconds("Wed, 21 Oct 2015 07:28:00 GMT", Long.MAX_VALUE))
        assertNull(RetryAfter.milliseconds(Long.MAX_VALUE.toString(), 0))
        assertNull(RetryAfter.milliseconds("-1", 0))
        assertNull(RetryAfter.milliseconds("not an HTTP date", 0))
    }
}
