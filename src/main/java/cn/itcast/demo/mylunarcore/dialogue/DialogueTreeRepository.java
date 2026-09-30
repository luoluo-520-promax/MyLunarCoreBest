package cn.itcast.demo.mylunarcore.dialogue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话树配置仓储：从 {@code DialogueTrees.json} 加载 treeId → 节点图。
 */
@Component
public class DialogueTreeRepository {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DialogueTree(String treeId, String entryNodeId, String title, List<DialogueNode> nodes) {
        public DialogueTree {
            treeId = treeId == null ? "" : treeId;
            entryNodeId = entryNodeId == null ? "" : entryNodeId;
            title = title == null ? "" : title;
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
        }
    }

    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final Map<String, DialogueTree> trees = new ConcurrentHashMap<>();
    private final Map<String, Map<String, DialogueNode>> nodeIndex = new ConcurrentHashMap<>();

    public DialogueTreeRepository(ObjectMapper objectMapper,
                                  @Value("${lunarcore.data-dir:data}") String dataDir) {
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
    }

    @PostConstruct
    public void load() {
        reload();
    }

    public boolean reload() {
        Path file = dataDir.resolve("DialogueTrees.json");
        if (!Files.isRegularFile(file)) {
            trees.clear();
            nodeIndex.clear();
            return false;
        }
        try {
            List<DialogueTree> list = objectMapper.readValue(file.toFile(), new TypeReference<>() {});
            Map<String, DialogueTree> nextTrees = new LinkedHashMap<>();
            Map<String, Map<String, DialogueNode>> nextIndex = new LinkedHashMap<>();
            for (DialogueTree tree : list) {
                if (tree.treeId().isBlank()) {
                    continue;
                }
                nextTrees.put(tree.treeId(), tree);
                Map<String, DialogueNode> nodes = new LinkedHashMap<>();
                for (DialogueNode node : tree.nodes()) {
                    if (node != null && node.nodeId() != null && !node.nodeId().isBlank()) {
                        nodes.put(node.nodeId(), node);
                    }
                }
                nextIndex.put(tree.treeId(), Collections.unmodifiableMap(nodes));
            }
            trees.clear();
            trees.putAll(nextTrees);
            nodeIndex.clear();
            nodeIndex.putAll(nextIndex);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public DialogueTree findTree(String treeId) {
        return treeId == null ? null : trees.get(treeId);
    }

    public DialogueNode findNode(String treeId, String nodeId) {
        Map<String, DialogueNode> nodes = nodeIndex.get(treeId);
        if (nodes == null || nodeId == null) {
            return null;
        }
        return nodes.get(nodeId);
    }

    public Map<String, DialogueTree> snapshot() {
        return Map.copyOf(trees);
    }
}
