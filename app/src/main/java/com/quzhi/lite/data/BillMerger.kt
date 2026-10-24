package com.quzhi.lite.data

import com.quzhi.lite.model.BillItem
import com.quzhi.lite.utils.AppLogger

/**
 * 「消费合并」——把多个板块的账单拼成一份，看到**今天实际花了多少**。
 *
 * ## 为什么需要
 *
 * 趣智校园按项目（板块）拆账：洗浴一个钱包、洗衣饮水吹风一个钱包，各自一套账单。
 * 想回答「我今天一共花了多少」，得把两个板块的账单加起来才算得出来。
 *
 * ## 这里只做「合并」，不做「切换」
 *
 * 合并靠的是**逐个板块去拉账单再拼起来**，而不是改全局认证参数。
 * 原因：本项目所有请求共用一套 `NetworkModule` 认证状态，切换会立刻影响
 * 正在显示的设备/钱包/订单。为了合账而临时改全局状态，一旦中途抛异常
 * 就会把 App 留在"半切"状态，用户看到的是错的数据。
 *
 * 所以 [BillMerger] 只接收「已经拉回来的各板块账单」，纯粹做合并排序。
 * 拉取由调用方负责（见 `MainViewModel.loadMergedTrend`），失败时降级为单板块。
 *
 * ## ⚠️ 合并后**不能**用来做业务判断
 *
 * 合并列表是**只读的展示数据**。关阀、结算、对账这些必须用当前板块自己的账单：
 * - 不同板块的 `orderNo` 可能撞号（都是 20 位数字，前缀规则相同）
 * - `SettlementReconciler` 按 orderNo/时间窗匹配，混入别的板块会匹配错
 */
object BillMerger {

    /**
     * 一个板块的账单快照。
     *
     * @param projectId 板块 id，合并后用来判断「这条属于哪边」
     * @param projectName 板块名（可能为空，界面显示「项目 3356」兜底）
     * @param bills 该板块最近几个月的账单（已经按接口原样拿回来）
     */
    data class BoardBills(
        val projectId: Long,
        val projectName: String,
        val bills: List<BillItem>
    ) {
        /** 板块短名：去掉学校前缀，只留业务名。界面空间有限 */
        val shortName: String get() = shortProjectName(projectName, projectId)
    }

    /**
     * 合并多个板块的账单。
     *
     * - 按 `consumeDate` **倒序**（最近的在前），和单板块列表的顺序一致
     * - `consumeDate` 相同时按 projectId 稳定排序，保证每次刷新顺序不变
     * - **不去重**：同一天两个板块各花一笔是两笔真实消费，去重会少算钱
     *
     * @return 合并后的账单。空输入返回空列表。
     */
    fun merge(boards: List<BoardBills>): List<BillItem> {
        if (boards.isEmpty()) return emptyList()
        if (boards.size == 1) return boards.first().bills
        return boards.flatMap { it.bills }
            .sortedWith(
                compareByDescending<BillItem> { it.consumeBillDTO.consumeDate }
                    .thenBy { it.consumeBillDTO.orderNo ?: "" }
            )
    }

    /**
     * 合并后的**总消费**（元）。用于和「当前板块一卡通余额」做对照。
     *
     * 只统计传进来的这批账单，不做时间过滤——时间范围由调用方拉取时决定
     * （现在拉最近 3 个月，和单板块 `loadBills` 一致）。
     *
     * 解析失败的单条直接跳过并记日志：账单里 `consumeMoney` 是字符串，
     * 服务端偶发返回空串，不能让它把整个合计搞挂。
     */
    fun totalYuan(bills: List<BillItem>): Double {
        var sum = 0.0
        for (item in bills) {
            val raw = item.consumeBillDTO.consumeMoney
            val v = raw.trim().toDoubleOrNull()
            if (v == null) {
                // 空串/异常值不参与合计，但要留痕：合计少算钱是会被用户发现的
                AppLogger.w("消费合并跳过无法解析的金额：${item.consumeBillDTO.description} → '$raw'")
                continue
            }
            sum += v
        }
        return sum
    }

    /**
     * 把板块名压短。
     *
     * 实测项目名是「某某大学洗浴」「某某大学洗衣饮水吹风」，
     * 前半截是学校名、后半截才是业务名。两个板块的**学校名相同**，
     * 并列显示时纯属浪费空间，所以只留业务名。
     *
     * 「某某大学洗浴」→「洗浴」
     * 「某某大学洗衣饮水吹风」→「洗衣饮水吹风」
     *
     * 识别不出学校前缀时**原样返回**，不做截断猜测——猜错了比显示长名字更糟。
     */
    fun shortProjectName(name: String, projectId: Long): String {
        val n = name.trim()
        if (n.isEmpty()) return "项目 $projectId"
        // 「X学院Y」「X大学Y」：去掉「学院/大学」及之前的部分
        for (marker in listOf("学院", "大学", "学校", "职院")) {
            val i = n.indexOf(marker)
            if (i >= 0) {
                val rest = n.substring(i + marker.length).trim()
                if (rest.isNotEmpty()) return rest
            }
        }
        return n
    }
}
