package com.hualala.linyu.data

import com.hualala.linyu.model.BillItem
import com.hualala.linyu.utils.PrefsHelper

/**
 * 一卡通余额。
 *
 * **首选真实值**：`GET /settlement/campus/userInfo` 直接返回一卡通余额
 * （趣智校园把易校园的接口代理了，所以不用去破易校园那套 native 签名）。
 *
 * **拿不到才回退估算**：没签约校园卡免密支付时服务端不给 `amount`，
 * 这时退回本地推算——
 *
 *     估算余额 = 用户手动填写的初始余额 − 填写时刻之后产生的消费
 *
 * 这段估算逻辑原先在 [com.hualala.linyu.ui.MainScreen] 和 [com.hualala.linyu.ui.WalletScreen]
 * 里各写了一遍，桌面小组件是第三份——而小组件那份当时漏了减法，直接显示没动过的初始值，
 * 于是「App 里余额变了、桌面上不变」。抽到这里，三处共用一份，不会再各算各的。
 */
object BalanceEstimator {

    /**
     * 一卡通真实余额；拿不到返回 null。
     *
     * 从 **Prefs** 读而不是从 ViewModel：小组件是另一个进程入口，
     * 它读不到 ViewModel 的内存状态，只能读持久化的那份。
     */
    fun realBalance(): Double? = PrefsHelper.campusBalance.toDoubleOrNull()

    /** 有没有真实余额可用（决定界面要不要标注「估算」） */
    fun hasRealBalance(): Boolean = realBalance() != null

    /** 账单里的日期字符串 → 毫秒时间戳；解析不了返回 0，会被当成「早于填余额的时刻」而不计入 */
    fun billTimeMs(consumeDate: String): Long = try {
        java.time.LocalDateTime.parse(consumeDate.replace(" ", "T"))
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (_: Exception) {
        0L
    }

    /** App 内用：直接吃接口返回的账单。有真实余额就直接返回它 */
    fun estimate(bills: List<BillItem>): Double = estimateFromEntries(
        bills.map {
            billTimeMs(it.consumeBillDTO.consumeDate) to
                (it.consumeBillDTO.consumeMoney.toDoubleOrNull() ?: 0.0)
        }
    )

    /**
     * 小组件用：吃本地快照折算出来的 (时间, 金额)。
     *
     * 这里必须**收口在同一份实现**——小组件读的是自己缓存的账单，
     * 字段是 Gson 反序列化出来的（可能缺字段），所以时间/金额缺失时按 0 处理，
     * 结果是「这笔不计入消费」，金额不会凭空变多。
     *
     * 真实余额在 [estimate] 里就返回了，走到这儿说明拿不到，才做本地推算。
     */
    fun estimateFromEntries(entries: List<Pair<Long, Double>>): Double {
        realBalance()?.let { return it }
        val initial = PrefsHelper.manualBalance
        if (initial.isEmpty()) return 0.0
        val since = PrefsHelper.manualBalanceTime
        val spent = entries.filter { it.first > since }.sumOf { it.second }
        return (initial.toDoubleOrNull() ?: 0.0) - spent
    }

    /**
     * 还没填过初始余额、也拿不到真实余额时，界面上用「—」而不是显示 0，
     * 免得被当成「余额为 0」。
     */
    fun format(balance: Double): String =
        if (!hasRealBalance() && PrefsHelper.manualBalance.isEmpty()) "¥ —"
        else "¥ %.2f".format(balance)
}
