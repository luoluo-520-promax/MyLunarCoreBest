package cn.itcast.demo.mylunarcore.admin;

import cn.itcast.demo.mylunarcore.assist.RagKnowledgeService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公告发布即灌库：策划发布 VersionActivity / 公告 Markdown 时写入 RAG。
 */
@RestController
@RequestMapping("/api/admin/assist/rag")
public class AssistRagIngestController {

    private final RagKnowledgeService ragKnowledgeService;

    public AssistRagIngestController(RagKnowledgeService ragKnowledgeService) {
        this.ragKnowledgeService = ragKnowledgeService;
    }

    public static class IngestRequest {
        public String announcementId;
        public String title;
        public String markdown;
        public long publishedAtMs;
    }

    @PostMapping("/ingest")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> ingest(@RequestBody IngestRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (request == null || request.announcementId == null || request.announcementId.isBlank()) {
            body.put("ok", false);
            body.put("message", "announcementId required");
            return body;
        }
        int n = ragKnowledgeService.ingestAnnouncement(
                request.announcementId,
                request.title,
                request.markdown,
                request.publishedAtMs);
        body.put("ok", true);
        body.put("chunks", n);
        body.put("corpusSize", ragKnowledgeService.chunkCount());
        return body;
    }

    @PostMapping("/reload")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> reload() {
        ragKnowledgeService.reloadPreferIncremental();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("corpusSize", ragKnowledgeService.chunkCount());
        return body;
    }
}
