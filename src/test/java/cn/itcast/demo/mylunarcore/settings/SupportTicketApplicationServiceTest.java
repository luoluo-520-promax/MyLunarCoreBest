package cn.itcast.demo.mylunarcore.settings; // 客服工单应用服务单测所在包

import org.junit.jupiter.api.DisplayName; // 中文用例名
import org.junit.jupiter.api.Test; // 测试方法

import static org.junit.jupiter.api.Assertions.assertEquals; // 相等断言
import static org.junit.jupiter.api.Assertions.assertTrue; // 真值断言
import static org.mockito.ArgumentMatchers.anyInt; // 任意玩家 id
import static org.mockito.ArgumentMatchers.anyString; // 任意标题/正文
import static org.mockito.ArgumentMatchers.eq; // 精确匹配分类等
import static org.mockito.Mockito.mock; // 创建仓储 mock
import static org.mockito.Mockito.verify; // 校验 insert 参数
import static org.mockito.Mockito.when; // 桩返回值

/**
 * SupportTicketApplicationService 测试：空内容拒绝、分类归一、成功落库、超限拒绝。
 */
@DisplayName("SupportTicketApplicationService 客服工单测试")
class SupportTicketApplicationServiceTest {

    /**
     * 标题仅空白时应失败，retcode=2，避免入库空标题工单
     */
    @Test
    @DisplayName("空标题应失败 retcode=2")
    void blankSubjectShouldFail() {
        SupportTicketRepository repo = mock(SupportTicketRepository.class); // 不依赖真实库
        SupportTicketApplicationService service = new SupportTicketApplicationService(repo);
        SupportTicketApplicationService.SubmitResult result =
                service.submit(1, "sound", "  ", "内容"); // 标题为空白
        assertEquals(2, result.retcode()); // 内容不合法
    } // blankSubjectShouldFail 结束

    /**
     * 未知分类归 other；大小写无关的合法分类保留小写规范值
     */
    @Test
    @DisplayName("未知分类应归一为 other")
    void unknownCategoryShouldNormalize() {
        assertEquals("other", SupportTicketApplicationService.normalizeCategory("hack")); // 非法→other
        assertEquals("display", SupportTicketApplicationService.normalizeCategory("Display")); // 大小写归一
        assertEquals("keybind", SupportTicketApplicationService.normalizeCategory("keybind")); // 合法保留
    } // unknownCategoryShouldNormalize 结束

    /**
     * 提交成功应调用 insert，并把返回的自增 id 填进 SubmitResult.ticketId
     */
    @Test
    @DisplayName("提交成功应写入仓库")
    void submitShouldInsert() {
        SupportTicketRepository repo = mock(SupportTicketRepository.class);
        when(repo.countByPlayer(9)).thenReturn(0); // 未达上限
        when(repo.insert(eq(9), eq("gameplay"), anyString(), anyString())).thenReturn(42L); // 模拟自增 42
        SupportTicketApplicationService service = new SupportTicketApplicationService(repo);

        SupportTicketApplicationService.SubmitResult result =
                service.submit(9, "gameplay", "自动战斗无效", "勾选后仍需手动");

        assertTrue(result.success()); // 成功标志
        assertEquals(42L, result.ticketId()); // 工单号透传
        verify(repo).insert(9, "gameplay", "自动战斗无效", "勾选后仍需手动"); // 参数原样入库
    } // submitShouldInsert 结束

    /**
     * 玩家工单数已达 20 时应拒绝，retcode=3，防止刷单
     */
    @Test
    @DisplayName("工单过多应拒绝")
    void tooManyTicketsShouldFail() {
        SupportTicketRepository repo = mock(SupportTicketRepository.class);
        when(repo.countByPlayer(anyInt())).thenReturn(20); // 已达上限
        SupportTicketApplicationService service = new SupportTicketApplicationService(repo);
        assertEquals(3, service.submit(1, "other", "标题", "内容").retcode()); // 超限码
    } // tooManyTicketsShouldFail 结束
} // SupportTicketApplicationServiceTest 结束
