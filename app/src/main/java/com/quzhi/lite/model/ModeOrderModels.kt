package com.quzhi.lite.model

/**
 * 按套餐计费的设备（洗衣机 / 吹风机）的一个消费套餐。
 *
 * 来自 `GET /device/mode/list?smallTypeId=X`。
 *
 * ## 单位混乱，务必看清
 *
 * | 字段 | 单位 | 例（吹风机模式一） |
 * |---|---|---|
 * | [modeMoney] | **厘**（1000 厘 = 1 元） | `600` |
 * | [modeMoneyYuan] | 元（Double） | `0.6` |
 * | [modeTimeMinute] | **字符串**分钟 | `"3"` |
 * | [modeTimeQuantity] | 秒 | `180` |
 *
 * 换算用 `com.quzhi.lite.utils.MoneyFormat.milliToYuan()`，别自己写 `/1000.0`。
 *
 * 实测套餐：
 * - 吹风机（smallTypeId=5）：29 电吹风模式一 0.6元/3分、30 模式二 1.0元/5分、31 模式三 2.0元/10分
 * - 洗衣机（smallTypeId=3）：33 标准洗 4元/35分、34 快速洗 3元/25分、35 单脱水 1元/10分、36 大件洗 5元/45分
 */
data class WashMode(
    /** 下单时作为 `xfModel` 传，不是 [modeId] 也不是 [id] */
    val modeId: Int? = null,
    val id: Int? = null,
    val projectId: Long? = null,
    val smallTypeId: Int? = null,
    val modeName: String? = null,
    val modeDesc: String? = null,
    /** 价格，单位**厘** */
    val modeMoney: Int? = null,
    /** 价格，单位元 */
    val modeMoneyYuan: Double? = null,
    /** 时长，**字符串**分钟 */
    val modeTimeMinute: String? = null,
    /** 时长，秒 */
    val modeTimeQuantity: Int? = null,
    /** 官方展示串，如 `"0.6元/3分钟"` */
    val typeValue: String? = null,
    /** 1 = 官方默认选中这一档 */
    val isSelected: Int? = null
) {
    val selectedByDefault: Boolean get() = isSelected == 1
}

/**
 * 最近一单（`GET /order/query/lastOrder/byMac`）。
 *
 * ## 用途：**开发期免费拿 orderNo**
 *
 * `modeOrder` 的响应恒为 `{"data":null}`，真正的 orderNo 只在 MQTT
 * `app_downRate_{phone}` 推送里。但这个 GET 接口不花钱就能给出最近一单的
 * `orderNo` / 套餐名 / 金额，足以把解析和界面全部验证完。
 *
 * ## ⚠️ 有时间窗口，不能当「设备空闲」判据
 *
 * 实测：1 小时前的单查得到，19 天前的单返回 `data:null`；
 * 乱填一个不存在的 MAC 也返回同样的 `null`（不做错误区分）。
 * 所以**不要**用 `data == null` 判断「这台设备没人用过」。
 *
 * ## 字段来源已用原始抓包字节核对
 *
 * `data` 是**扁平对象**（不套一层包装），下面每个字段都在真实响应里出现过。
 * 其中 `liquid*` 那一组是饮水机双出水口用的，对本设备恒为 null，解析它们无害。
 */
data class ModeOrderInfo(
    val orderId: Long? = null,
    /** 20 位订单号，和 [orderId]（账单序号）不是一回事 */
    val orderNo: String? = null,
    val accountId: Long? = null,
    val deviceId: Int? = null,
    val smallTypeId: Int? = null,
    val macAddress: String? = null,
    val deviceName: String? = null,
    /** 预扣金额，单位**厘** */
    val preDeductMoney: Int? = null,
    /** 套餐 id（= [WashMode.modeId]） */
    val xfModel: Int? = null,
    val modeName: String? = null,
    /** 套餐价，单位**厘** */
    val modeMoney: Int? = null,
    /** 套餐时长，秒 */
    val modeTime: Int? = null,
    /** `yyyy-MM-dd HH:mm:ss`，和 MQTT 里的紧凑格式不是一回事 */
    val consumeDate: String? = null,
    val liquidModeName: String? = null,
    val liquidConsumeMoney: String? = null,
    val liquidOrderNo: String? = null,
    val liquidOrderStatus: String? = null
)

/**
 * `POST /order/tcpDevice/washer/status` 在**设备被占用**时返回的 `data`。
 *
 * ## 实测样本（2026-10-24 09:51，洗衣机被别人用着）
 *
 * ```json
 * {"success":true,"errorCode":307,"errorMessage":"设备正在使用中",
 *  "data":{"expireTimes":729,"orderNo":null,"xfModelName":null,
 *          "preDeductMoney":null,"isOwner":false,"modeMoney":null,
 *          "modeTime":null,"accountId":null, ...其余全是 null}}
 * ```
 *
 * 随后三轮 `expireTimes` 依次是 **729 → 649 → 589**，逐轮递减约 60 秒
 * （轮询间隔 20s × 3 轮），证明它就是**剩余秒数**。
 *
 * ## 为什么 `orderNo` 是 null
 *
 * 那不是你的单。服务端只告诉你「有人在用、还剩多久」，
 * 不给别人的订单号——这是对的，别指望从这里拿 orderNo。
 *
 * ## 字段一律可空
 *
 * 服务端在这一版把没意义的字段全填了 null（`modeMoney` / `accountId` 等），
 * 不同学校/版本可能填别的。**不要假设哪个字段一定有值。**
 */
data class WasherStatusData(
    /**
     * 剩余秒数。实测 729 → 649 → 589 递减。
     * ⚠️ 只在**别人占用**时见过；自己下单后是否也有值未采样。
     */
    val expireTimes: Long? = null,
    /** 当前占用者的订单号。**别人的单这里是 null** */
    val orderNo: String? = null,
    /**
     * 这个进行中的单是不是**自己**的。
     * 实测别人占用时为 `false`；自己的单未采样（推测是 true）。
     */
    val isOwner: Boolean? = null,
    val xfModelName: String? = null,
    val preDeductMoney: Long? = null,
    val modeMoney: Long? = null,
    val modeTime: Long? = null,
    val accountId: Long? = null,
    val liquidOrderNo: String? = null,
    val liquidOrderStatus: String? = null,
    val liquidModeName: String? = null,
    val liquidConsumeMoney: String? = null,
    val liquidOrderStatusDisplay: String? = null
)
