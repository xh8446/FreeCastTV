package com.freecast.tv

/**
 * 激活密钥规则：6 位数字，格式为 5?1?8?（? 为任意数字）。
 * 纯逻辑类，便于单元测试。
 */
object ActivationKeys {

    private val PATTERN = Regex("^5[0-9]1[0-9]8[0-9]$")
    private val CODE_PATTERN = Regex("^[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$")

    fun isValidFormat(key: String): Boolean =
        PATTERN.matches(key) || CODE_PATTERN.matches(key.uppercase())
}
