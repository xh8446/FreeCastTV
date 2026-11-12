package com.freecast.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivationKeysTest {

    @Test
    fun validKeysMatchPattern() {
        // 5?1?8? 各位固定，? 为任意数字
        assertTrue(ActivationKeys.isValidFormat("501 180".replace(" ", "")))
        assertTrue(ActivationKeys.isValidFormat("501180"))
        assertTrue(ActivationKeys.isValidFormat("591980"))
        assertTrue(ActivationKeys.isValidFormat("521 380".replace(" ", "")))
        assertTrue(ActivationKeys.isValidFormat("501080"))
        assertTrue(ActivationKeys.isValidFormat("591 980".replace(" ", "")))
    }

    @Test
    fun invalidKeysRejected() {
        assertFalse(ActivationKeys.isValidFormat("401180")) // 首位非 5
        assertFalse(ActivationKeys.isValidFormat("502180")) // 第 3 位非 1
        assertFalse(ActivationKeys.isValidFormat("501170")) // 第 5 位非 8
        assertFalse(ActivationKeys.isValidFormat("50118"))  // 只有 5 位
        assertFalse(ActivationKeys.isValidFormat("5011800")) // 7 位
        assertFalse(ActivationKeys.isValidFormat("5a1180"))  // 含字母
        assertFalse(ActivationKeys.isValidFormat(""))        // 空
        assertFalse(ActivationKeys.isValidFormat(" 501180")) // 前导空格
    }
}
