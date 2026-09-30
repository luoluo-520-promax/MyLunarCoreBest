# -*- coding: utf-8 -*-
"""Generate MyLunarCore shop IAP / paid gift-pack solutions Word document."""

from datetime import date
from pathlib import Path

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

DESKTOP = Path.home() / "Desktop"
OUTPUT = DESKTOP / ("MyLunarCore_" + "\u5546\u57ce\u6c2a\u91d1\u5546\u54c1\u4e0a\u67b6\u65b9\u6848" + ".docx")


def set_doc_fonts(doc: Document):
    style = doc.styles["Normal"]
    style.font.name = "微软雅黑"
    style.font.size = Pt(11)
    style._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")
    style.paragraph_format.space_after = Pt(6)
    style.paragraph_format.line_spacing = 1.15


def add_title(doc: Document, text: str):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run(text)
    run.bold = True
    run.font.size = Pt(22)
    run.font.color.rgb = RGBColor(25, 75, 140)
    run.font.name = "微软雅黑"
    run._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")


def add_subtitle(doc: Document, text: str):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run(text)
    run.font.size = Pt(11)
    run.font.color.rgb = RGBColor(100, 100, 100)
    run.font.name = "微软雅黑"
    run._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")


def add_heading(doc: Document, text: str, level: int = 1):
    h = doc.add_heading(text, level=level)
    for run in h.runs:
        run.font.name = "微软雅黑"
        run._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")
        if level == 1:
            run.font.color.rgb = RGBColor(25, 75, 140)


def add_body(doc: Document, text: str):
    p = doc.add_paragraph(text)
    for run in p.runs:
        run.font.name = "微软雅黑"
        run._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")


def add_bullet(doc: Document, text: str, level: int = 0):
    p = doc.add_paragraph(text, style="List Bullet")
    p.paragraph_format.left_indent = Cm(0.5 * (level + 1))
    for run in p.runs:
        run.font.name = "微软雅黑"
        run._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")


def add_code(doc: Document, text: str):
    p = doc.add_paragraph()
    p.paragraph_format.left_indent = Cm(0.5)
    run = p.add_run(text)
    run.font.name = "Consolas"
    run.font.size = Pt(9)
    run.font.color.rgb = RGBColor(30, 30, 30)


def add_table(doc: Document, headers, rows):
    table = doc.add_table(rows=1 + len(rows), cols=len(headers))
    table.style = "Table Grid"
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    hdr_cells = table.rows[0].cells
    for i, h in enumerate(headers):
        hdr_cells[i].text = h
        for p in hdr_cells[i].paragraphs:
            for run in p.runs:
                run.bold = True
                run.font.name = "微软雅黑"
                run._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")
                run.font.size = Pt(10)
    for r_idx, row in enumerate(rows):
        row_cells = table.rows[r_idx + 1].cells
        for c_idx, val in enumerate(row):
            row_cells[c_idx].text = val
            for p in row_cells[c_idx].paragraphs:
                for run in p.runs:
                    run.font.name = "微软雅黑"
                    run._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")
                    run.font.size = Pt(9)
    doc.add_paragraph()


def build():
    doc = Document()
    set_doc_fonts(doc)
    for section in doc.sections:
        section.top_margin = Cm(2)
        section.bottom_margin = Cm(2)
        section.left_margin = Cm(2.2)
        section.right_margin = Cm(2.2)

    add_title(doc, "MyLunarCore 商城氪金商品上架方案")
    add_subtitle(doc, "直接氪金 · 打折氪金礼包 · 其他氪金礼包  |  " + date.today().isoformat())
    add_body(
        doc,
        "本文基于当前 MyLunarCore 经济/商城实现（ShopConfigs.json + ShopApplicationService "
        "+ WalletApplicationService，仅支持游戏内货币扣款），给出三类付费商品的配置模型、"
        "支付发货链路、协议与落地步骤，便于策划上架与研发落地。",
    )

    # ---------- 1 现状 ----------
    add_heading(doc, "1. 现状与缺口", 1)
    add_body(doc, "当前商城能力：")
    add_bullet(doc, "配置文件 data/ShopConfigs.json：商品仅含 currencyId + price，走游戏币购买。")
    add_bullet(doc, "购买入口：BuyShopItem → ShopApplicationService.buy → 扣钱包并 addSimpleItem。")
    add_bullet(doc, "活动商店 ActivityConfigs.shopProducts 同样是活动代币兑换，不是真钱支付。")
    add_bullet(doc, "协议 economy_system.proto 的 ShopItem 无商品类型、无渠道 SKU、无折扣字段。")
    add_body(doc, "缺口：缺少「真钱下单 → 渠道支付 → 服务端验单发货 → 限购/折扣结算」整条链路，无法直接上架氪金档位与礼包。")

    # ---------- 2 总体方案 ----------
    add_heading(doc, "2. 总体方案（推荐）", 1)
    add_body(
        doc,
        "采用「统一商品目录 + 支付方式分流」：所有上架商品进入同一套 Shop/Product 配置；"
        "按 payType 分流为游戏币购买或真钱 IAP。真钱商品不直接扣 currency，而是创建充值订单，"
        "等渠道回调验签成功后再发货。",
    )
    add_heading(doc, "2.1 商品三类定义", 2)
    add_table(
        doc,
        ["品类", "productCategory", "玩家感知", "典型内容", "限购建议"],
        [
            [
                "直接氪金",
                "DIRECT_TOPUP",
                "充值页档位（6/30/68/128…）",
                "仅发放付费货币（如星琼/晶石）及首充双倍",
                "无日限；可有首充标记",
            ],
            [
                "打折氪金礼包",
                "DISCOUNT_PACK",
                "商城「特惠/限时折扣」页签",
                "货币+道具组合；展示原价/现价/折扣率",
                "日限/周限/活动期总限；强依赖时间窗",
            ],
            [
                "其他氪金礼包",
                "PAID_PACK",
                "月卡、新手礼、成长基金、通行证档等",
                "多日领取、条件解锁、或一次性大礼包",
                "账号终身限购 / 月限 / 赛季限",
            ],
        ],
    )
    add_heading(doc, "2.2 支付分流", 2)
    add_bullet(doc, "payType=CURRENCY：沿用现有 ShopApplicationService.buy（游戏币）。")
    add_bullet(doc, "payType=IAP：走新链路 CreateOrder → 客户端调渠道 → Verify/Callback → Grant。")
    add_bullet(doc, "currencyId=0 或省略表示真钱商品；price 仅作展示参考，实付以渠道 SKU 为准。")

    # ---------- 3 配置模型 ----------
    add_heading(doc, "3. 配置模型扩展（ShopConfigs）", 1)
    add_body(doc, "建议扩展 ShopItemConfig，向后兼容：旧字段保留，新字段缺省时仍按游戏币商品处理。")
    add_code(
        doc,
        """{
  "shopId": 2,
  "shopName": "氪金商城",
  "tabs": ["TOPUP", "DISCOUNT", "PACK"],
  "items": [
    {
      "shopItemId": 2001,
      "productCategory": "DIRECT_TOPUP",
      "payType": "IAP",
      "skuId": "com.mylunarcore.crystal.60",
      "displayName": "60晶石",
      "priceCents": 600,
      "currencyCode": "CNY",
      "rewards": [{"type": "CURRENCY", "currencyId": 2, "amount": 60}],
      "firstPurchaseBonus": [{"type": "CURRENCY", "currencyId": 2, "amount": 60}],
      "dailyLimit": 0,
      "totalLimit": 0,
      "sortOrder": 10,
      "enabled": true
    },
    {
      "shopItemId": 2101,
      "productCategory": "DISCOUNT_PACK",
      "payType": "IAP",
      "skuId": "com.mylunarcore.pack.weekend.68",
      "displayName": "周末特惠礼包",
      "priceCents": 6800,
      "originalPriceCents": 12800,
      "discountRate": 53,
      "saleStartAt": "2026-08-01T00:00:00+08:00",
      "saleEndAt": "2026-08-03T23:59:59+08:00",
      "rewards": [
        {"type": "CURRENCY", "currencyId": 2, "amount": 680},
        {"type": "ITEM", "itemId": 23001, "count": 10},
        {"type": "ITEM", "itemId": 101, "count": 5}
      ],
      "dailyLimit": 1,
      "totalLimit": 3,
      "sortOrder": 20,
      "enabled": true
    },
    {
      "shopItemId": 2201,
      "productCategory": "PAID_PACK",
      "payType": "IAP",
      "skuId": "com.mylunarcore.pack.monthly",
      "displayName": "月卡",
      "priceCents": 3000,
      "packKind": "MONTHLY_CARD",
      "rewards": [
        {"type": "CURRENCY", "currencyId": 2, "amount": 300},
        {"type": "DAILY_CLAIM", "currencyId": 2, "amount": 90, "days": 30}
      ],
      "totalLimit": 1,
      "limitPeriod": "MONTH",
      "sortOrder": 30,
      "enabled": true
    }
  ]
}""",
    )
    add_heading(doc, "3.1 关键字段说明", 2)
    add_table(
        doc,
        ["字段", "含义", "适用品类"],
        [
            ["productCategory", "DIRECT_TOPUP / DISCOUNT_PACK / PAID_PACK", "全部"],
            ["payType", "CURRENCY 或 IAP", "全部"],
            ["skuId", "渠道商品 ID（App Store / 微信 / 支付宝等）", "IAP"],
            ["priceCents / originalPriceCents", "现价/原价（分）；用于展示与对账", "打折礼包必填原价"],
            ["discountRate", "折扣百分比展示（可由现价/原价计算）", "DISCOUNT_PACK"],
            ["saleStartAt / saleEndAt", "上架时间窗；过期自动不下架可见或不可买", "打折/限时礼包"],
            ["rewards[]", "发货内容：货币、道具、多日领取", "全部"],
            ["firstPurchaseBonus", "首充双倍等一次性加成", "DIRECT_TOPUP"],
            ["packKind", "MONTHLY_CARD / STARTER / BATTLE_PASS / GROWTH 等", "PAID_PACK"],
            ["dailyLimit / totalLimit / limitPeriod", "日限、总限、周期（DAY/WEEK/MONTH/LIFETIME）", "礼包为主"],
        ],
    )

    # ---------- 4 三类方案细则 ----------
    add_heading(doc, "4. 三类商品落地细则", 1)

    add_heading(doc, "4.1 直接氪金（DIRECT_TOPUP）", 2)
    add_body(doc, "目标：稳定充值入口，发放付费货币，支撑后续游戏币商城消费。")
    add_bullet(doc, "展示：独立「充值」页签，档位固定（建议 6/30/68/128/328/648）。")
    add_bullet(doc, "发货：只发付费货币；首充可叠加 firstPurchaseBonus（账号维度标记 first_topup_done）。")
    add_bullet(doc, "风控：同一订单号幂等；禁止客户端直接加货币；必须服务端验单。")
    add_bullet(doc, "与现有钱包衔接：验单成功后调用 WalletApplicationService.add(currencyId, amount, \"iap:\"+orderId)。")
    add_bullet(doc, "上架步骤：配置 SKU → 渠道后台创建同价商品 → 写入 ShopConfigs → 热更 reload → 客户端拉表。")

    add_heading(doc, "4.2 打折氪金礼包（DISCOUNT_PACK）", 2)
    add_body(doc, "目标：限时刺激付费，必须同时满足「折扣展示」与「时间/次数约束」。")
    add_bullet(doc, "展示：原价划线 + 现价 + 折扣角标 + 倒计时（读 saleEndAt）。")
    add_bullet(doc, "校验：购买前检查 now∈[saleStartAt,saleEndAt]、enabled、限购剩余次数。")
    add_bullet(doc, "发货：rewards 批量发放（货币走 Wallet，道具走 ItemRepository / RewardDistributor）。")
    add_bullet(doc, "运营：同一礼包可复制为新 shopItemId 做下一期活动，避免改历史订单关联配置。")
    add_bullet(doc, "合规：折扣文案与真实折扣率一致；过期商品列表可隐藏或置灰不可点。")

    add_heading(doc, "4.3 其他氪金礼包（PAID_PACK）", 2)
    add_body(doc, "目标：覆盖月卡、新手礼、成长基金、通行证等非「纯充值档」的付费商品。")
    add_table(
        doc,
        ["packKind", "购买后行为", "限购", "实现要点"],
        [
            ["STARTER", "一次性道具+货币礼包", "LIFETIME=1", "账号标记 purchased"],
            ["MONTHLY_CARD", "立即发一笔 + 每日可领 N 天", "MONTH=1", "日领表 + 剩余天数"],
            ["GROWTH_FUND", "付费解锁档位，达标逐档领取", "LIFETIME=1", "进度与领取位图"],
            ["BATTLE_PASS", "解锁高级通行证奖励轨", "赛季=1", "与赛季 ID 绑定"],
            ["BUNDLE", "普通组合礼包（无日领）", "日/周/总限", "同 DISCOUNT 发货，无强制折扣"],
        ],
    )
    add_bullet(doc, "统一订单与发货，差异只在 GrantStrategy（按 packKind 选策略）。")
    add_bullet(doc, "月卡日领建议独立协议 DailyClaim，避免每次打开商城重复发货。")

    # ---------- 5 服务端架构 ----------
    add_heading(doc, "5. 服务端架构设计", 1)
    add_heading(doc, "5.1 新增模块（建议包路径）", 2)
    add_bullet(doc, "economy.iap.IapProductCatalog —— 读取扩展后的 ShopConfigs / 独立 IapProducts.json。")
    add_bullet(doc, "economy.iap.IapOrderService —— 创建订单、状态机、幂等。")
    add_bullet(doc, "economy.iap.IapVerifyGateway —— 对接渠道验签（可先 Mock，后接真实渠道）。")
    add_bullet(doc, "economy.iap.IapGrantService —— 按 rewards / packKind 发货，写 ledger。")
    add_bullet(doc, "economy.iap.PurchaseLimitService —— 日/周/月/终身限购计数（Redis 或 DB）。")
    add_heading(doc, "5.2 订单状态机", 2)
    add_code(
        doc,
        "CREATED → PAYING → PAID → GRANTED\n"
        "              ↘ FAILED / CLOSED\n"
        "PAID 若发货失败 → GRANT_RETRY（定时补发，禁止重复发货）",
    )
    add_heading(doc, "5.3 核心表（建议）", 2)
    add_table(
        doc,
        ["表", "用途", "关键字段"],
        [
            [
                "iap_order",
                "充值订单",
                "order_id, player_id, shop_item_id, sku_id, amount_cents, status, channel, channel_tx_id, created_at",
            ],
            [
                "iap_grant_log",
                "发货流水（幂等）",
                "order_id UNIQUE, rewards_json, granted_at",
            ],
            [
                "iap_purchase_limit",
                "限购计数",
                "player_id, shop_item_id, period_key, count",
            ],
            [
                "iap_entitlement",
                "月卡/基金权益",
                "player_id, pack_kind, expire_at, claim_bitmap",
            ],
        ],
    )
    add_heading(doc, "5.4 与现有代码衔接", 2)
    add_bullet(doc, "游戏币商品：继续 ShopApplicationService.buy，零改动或仅加 productCategory=CURRENCY_SHOP。")
    add_bullet(doc, "真钱发货币：复用 WalletApplicationService.add + wallet_ledger（reason=iap:orderId）。")
    add_bullet(doc, "真钱发道具：复用 ItemRepository / RewardDistributor，避免第二套背包逻辑。")
    add_bullet(doc, "热更：ShopConfigRepository.reload() 扩展后一并加载新字段；HotReloadCoordinator 注册不变。")

    # ---------- 6 协议 ----------
    add_heading(doc, "6. 协议扩展建议", 1)
    add_body(doc, "在 economy_system.proto 增补（示意）：")
    add_code(
        doc,
        """message ShopItem {
  uint32 shop_item_id = 1;
  uint32 item_id = 2;           // 兼容旧字段；IAP 礼包可忽略
  uint32 item_count = 3;
  uint32 currency_id = 4;       // 0 = 真钱
  uint32 price = 5;             // 游戏币价或展示用
  uint32 daily_limit = 6;
  string product_category = 7;  // DIRECT_TOPUP/DISCOUNT_PACK/PAID_PACK
  string pay_type = 8;          // CURRENCY/IAP
  string sku_id = 9;
  uint32 price_cents = 10;
  uint32 original_price_cents = 11;
  uint32 discount_rate = 12;
  int64 sale_end_at_ms = 13;
  uint32 remain_limit = 14;     // 服务端算好剩余可购次数
  string display_name = 15;
}

message CreateIapOrderCsReq {
  uint32 shop_id = 1;
  uint32 shop_item_id = 2;
}
message CreateIapOrderScRsp {
  int32 retcode = 1;
  string order_id = 2;
  string sku_id = 3;
  uint32 price_cents = 4;
}

message ConfirmIapOrderCsReq {
  string order_id = 1;
  string channel = 2;
  string channel_receipt = 3;   // 渠道票据/交易号
}
message ConfirmIapOrderScRsp {
  int32 retcode = 1;
  string order_id = 2;
  repeated CurrencyEntry remaining_currency = 3;
}""",
    )

    # ---------- 7 客户端 ----------
    add_heading(doc, "7. 客户端展示与购买流程", 1)
    add_bullet(doc, "拉表 GetShopList：按 product_category 分三个页签渲染。")
    add_bullet(doc, "CURRENCY：点购买 → BuyShopItem（现有）。")
    add_bullet(doc, "IAP：点购买 → CreateIapOrder → 调系统/渠道收银台 → ConfirmIapOrder → 刷新货币与背包。")
    add_bullet(doc, "打折页：未在时间窗内不展示或展示「已结束」；显示原价/现价/倒计时。")
    add_bullet(doc, "其他礼包：按 packKind 跳转月卡面板/基金面板/通行证面板。")

    # ---------- 8 上架运营流程 ----------
    add_heading(doc, "8. 策划上架操作清单", 1)
    add_heading(doc, "8.1 直接氪金档位", 2)
    add_bullet(doc, "确定档位金额与发放货币数量、是否首充双倍。")
    add_bullet(doc, "在各支付渠道创建 SKU，保证 skuId、实付金额一致。")
    add_bullet(doc, "写入 ShopConfigs（productCategory=DIRECT_TOPUP），enabled=true。")
    add_bullet(doc, "内网用 Mock 验单走通 Create→Confirm→钱包到账→ledger。")
    add_heading(doc, "8.2 打折氪金礼包", 2)
    add_bullet(doc, "定原价、现价、折扣文案、起止时间、日限/总限、奖励列表。")
    add_bullet(doc, "新建 shopItemId（不要复用已有订单关联的旧 ID）。")
    add_bullet(doc, "配置 saleStartAt/saleEndAt，热更后 QA 验证倒计时与过期不可买。")
    add_heading(doc, "8.3 其他氪金礼包", 2)
    add_bullet(doc, "选定 packKind，补齐日领/基金进度等附属配置。")
    add_bullet(doc, "配置 limitPeriod 与 totalLimit，防止重复购买。")
    add_bullet(doc, "联调权益面板（月卡剩余天数、每日领取按钮）。")

    # ---------- 9 分阶段落地 ----------
    add_heading(doc, "9. 分阶段实施计划", 1)
    add_table(
        doc,
        ["阶段", "内容", "产出"],
        [
            [
                "P0（1周）",
                "扩展配置模型 + Mock IAP 下单/验单/发货 + 直接氪金档位",
                "可内网充值加付费货币",
            ],
            [
                "P1（1周）",
                "打折礼包时间窗、限购、原价展示 + Reward 批量发货",
                "可上架限时特惠礼包",
            ],
            [
                "P2（1~2周）",
                "月卡/新手礼等 PAID_PACK 策略 + 日领协议",
                "可上架非纯充值礼包",
            ],
            [
                "P3",
                "对接真实渠道（微信/支付宝/商店内购）+ 对账与补发任务",
                "可外网正式售卖",
            ],
        ],
    )

    # ---------- 10 风险与验收 ----------
    add_heading(doc, "10. 风险控制与验收标准", 1)
    add_heading(doc, "10.1 风险", 2)
    add_bullet(doc, "重复发货：必须以 order_id 唯一约束；Confirm 接口幂等返回成功。")
    add_bullet(doc, "客户端伪造收据：生产环境必须服务端向渠道验签，Mock 仅限 dev/profile。")
    add_bullet(doc, "折扣过期仍可买：购买与建单双端校验 saleEndAt。")
    add_bullet(doc, "配置热更中间态：ShopConfigRepository 继续整体替换不可变 Map。")
    add_heading(doc, "10.2 验收用例", 2)
    add_bullet(doc, "直接氪金：支付成功后货币到账，wallet_ledger 有 iap 记录；重复 Confirm 不加倍。")
    add_bullet(doc, "打折礼包：窗内可买、窗外拒单；限购用尽后 CreateOrder 失败；奖励完整到账。")
    add_bullet(doc, "其他礼包：月卡购买后当日可领、次日可领、过期不可领；终身礼包不可二次购买。")
    add_bullet(doc, "游戏币商品回归：原 ShopId=1 的 BuyShopItem 行为与现网一致。")

    # ---------- 11 示例配置片段 ----------
    add_heading(doc, "11. 建议首批上架 SKU 示例", 1)
    add_table(
        doc,
        ["品类", "shopItemId", "展示名", "实付(元)", "核心奖励"],
        [
            ["直接氪金", "2001~2006", "60/300/680/1280/3280/6480 晶石", "6/30/68/128/328/648", "等额付费货币+首充双倍"],
            ["打折礼包", "2101", "新手特惠三件套", "6（原价 30）", "晶石+抽卡券+养成材料"],
            ["打折礼包", "2102", "周末补给包", "68（原价 128）", "晶石+体力+突破材料"],
            ["其他礼包", "2201", "月卡", "30", "立即 300 晶石 + 日领 90×30 天"],
            ["其他礼包", "2202", "启程礼包", "18", "限定头像框+材料+少量晶石（终身1次）"],
        ],
    )

    add_heading(doc, "12. 结论", 1)
    add_body(
        doc,
        "在不破坏现有游戏币商城的前提下，通过扩展商品配置（productCategory/payType/sku/rewards/限购/时间窗）"
        "并新增 IAP 订单-验单-发货链路，即可同时支持：①直接氪金档位；②打折氪金礼包；③月卡等其他氪金礼包。"
        "建议严格按 P0→P3 推进：先 Mock 打通发货与限购，再接真实渠道与对账。",
    )

    doc.save(OUTPUT)
    print(str(OUTPUT))


if __name__ == "__main__":
    build()
