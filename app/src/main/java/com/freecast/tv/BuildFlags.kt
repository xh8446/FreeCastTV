package com.freecast.tv

/**
 * 构建期开关。
 *
 * PAYMENT_ENABLED = false：调试阶段，隐藏并禁用一切会员/付费/激活入口，
 * 所有设备一律按「未激活试用」运行（每次冷启动重置试用额度）。
 * 最终版调试完成后改为 true 即恢复正式商业逻辑。
 */
object BuildFlags {
    /** 会员/付费/激活入口开关：false = 全部隐藏（当前测试阶段），true = 恢复正式商业功能 */
    const val PAYMENT_ENABLED = false
}
