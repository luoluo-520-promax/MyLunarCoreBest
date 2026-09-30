// 邮件业务服务：分页查询、领取附件发奖与状态更新
package cn.itcast.demo.mylunarcore.hall;

import cn.itcast.demo.mylunarcore.economy.RewardDistributor; // 通用奖励分发器：解析附件 JSON 并发放货币/道具
import cn.itcast.demo.mylunarcore.model.MailEntity; // 邮件实体：title、content、attachmentsJson、status
import cn.itcast.demo.mylunarcore.repo.MailRepository; // 邮件仓储：分页查询、按 mailId 定位、更新 status
import com.fasterxml.jackson.databind.ObjectMapper; // JSON 解析器：解析 attachmentsJson
import org.springframework.stereotype.Service; // Spring 服务 Bean
import org.springframework.transaction.annotation.Transactional; // 发奖与状态更新必须在同一事务

import java.util.List; // 邮件列表、附件列表
import java.util.Map; // 附件通用 Map 结构

/**
 * 邮件业务服务。
 * 聚焦于「邮件领取」核心流程：确认归属 → 解析附件 → 发奖 → 置已领取。
 * 状态约定：0=未读，1=已读未领，2=已领取。
 */
@Service
public class MailApplicationService {

    /**
     * 领取结果 record。
     * attachments 返回实际发放成功的附件快照，供协议层回显给客户端。
     */
    public record ClaimResult(boolean success, int retcode, List<Map<String, Object>> attachments) {}

    /** 邮件仓储，负责分页、定位和状态更新。 */
    private final MailRepository mailRepository;
    /** 通用奖励分发器，负责把附件转换为货币或道具发放动作。 */
    private final RewardDistributor rewardDistributor;
    /** 独立 ObjectMapper：保证 attachmentsJson 解析行为稳定，不依赖全局配置副作用。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 构造器注入邮件仓储与奖励分发器。 */
    public MailApplicationService(MailRepository mailRepository, RewardDistributor rewardDistributor) {
        this.mailRepository = mailRepository; // 保存邮件仓储引用
        this.rewardDistributor = rewardDistributor; // 保存奖励分发器引用
    }

    /** 统计玩家邮箱总数，用于分页接口返回 totalCount。 */
    public long countMails(int playerId) {
        return mailRepository.countMails(playerId); // 按 playerId 统计 mail 表记录数
    }

    /**
     * 分页拉取邮件列表。
     * 不额外做复杂加工，附件结构转换放在协议层 HallNettyService 完成。
     */
    public List<MailEntity> listMails(int playerId, int page, int pageSize) {
        return mailRepository.listMails(playerId, page, pageSize); // 委托仓储分页查询
    }

    /**
     * 只读摘要未领取/未读邮件，供 AI 助手解释；不自动领取附件。
     */
    public String summarizeUnread(int playerId) {
        List<MailEntity> mails = mailRepository.listMails(playerId, 1, 20);
        if (mails == null || mails.isEmpty()) {
            return "当前邮箱没有可展示的邮件。";
        }
        int unreadOrUnclaimed = 0;
        StringBuilder titles = new StringBuilder();
        int shown = 0;
        for (MailEntity mail : mails) {
            if (mail == null || mail.getStatus() >= 2) {
                continue;
            }
            unreadOrUnclaimed++;
            if (shown < 3) {
                if (shown > 0) {
                    titles.append('、');
                }
                String title = mail.getTitle() == null || mail.getTitle().isBlank() ? ("邮件#" + mail.getId()) : mail.getTitle();
                titles.append(title);
                shown++;
            }
        }
        if (unreadOrUnclaimed <= 0) {
            return "邮箱暂无未领取邮件。";
        }
        return "你有 " + unreadOrUnclaimed + " 封未领取邮件，例如：" + titles + "。可在邮箱界面领取附件。";
    }

    /**
     * 领取单封邮件附件。
     * 事务边界：发奖失败则不写回「已领取」状态，避免「已领但资源未到账」。
     * retcode：0 成功，2 邮件不存在，3 已领取，4 发奖失败。
     */
    @Transactional
    public ClaimResult claim(int playerId, long mailId) {
        MailEntity mail = mailRepository.findMail(playerId, mailId); // 按 playerId+mailId 定位，防止越权领取
        if (mail == null) {
            return new ClaimResult(false, 2, List.of()); // 邮件不存在或不属于该玩家
        }
        if (mail.getStatus() >= 2) {
            return new ClaimResult(false, 3, List.of()); // status≥2 表示已领取，幂等拒绝
        }
        List<Map<String, Object>> attachments =
                RewardDistributor.parseAttachmentsJson(mail.getAttachmentsJson(), objectMapper); // 解析附件 JSON
        if (!rewardDistributor.grantMailAttachments(playerId, attachments)) {
            return new ClaimResult(false, 4, List.of()); // 发奖写库失败，事务回滚
        }
        mailRepository.updateStatus(mailId, playerId, 2); // 标记为已领取 status=2
        return new ClaimResult(true, 0, attachments); // 领取成功，回传附件快照
    }
}
