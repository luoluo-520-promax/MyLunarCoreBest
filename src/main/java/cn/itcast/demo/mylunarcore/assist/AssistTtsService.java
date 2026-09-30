package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 文本转语音旁路：优先远程 TTS，否则生成本地占位 16kbps OPUS 帧头（客户端可识别后播角色声线）。
 */
@Service
public class AssistTtsService {

    private final LunarCoreProperties properties;

    public AssistTtsService(LunarCoreProperties properties) {
        this.properties = properties;
    }

    public record TtsClip(byte[] audioChunk, String format, String voiceId) {
        public static TtsClip empty() {
            return new TtsClip(new byte[0], "", "");
        }

        public boolean hasAudio() {
            return audioChunk != null && audioChunk.length > 0;
        }
    }

    public TtsClip synthesize(long uid, String text, String voiceId) {
        if (!properties.getAiAssist().isTtsEnabled() || text == null || text.isBlank()) {
            return TtsClip.empty();
        }
        String voice = voiceId == null || voiceId.isBlank() ? "default" : voiceId.trim();
        String endpoint = properties.getAiAssist().getTtsEndpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            // 远程 TTS 接入点预留：生产注入 endpoint/apiKey 后由运维对接；失败降级本地占位
            try {
                return synthesizeRemote(text, voice);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return synthesizeLocalPlaceholder(text, voice);
    }

    private TtsClip synthesizeRemote(String text, String voice) {
        // 无 HTTP 客户端硬依赖：占位实现返回本地帧，避免引入额外依赖；endpoint 配置就绪后可替换为真实调用
        return synthesizeLocalPlaceholder(text, voice);
    }

    /**
     * 生成本地 OPUS 占位块：含 magic + voiceId + 文本哈希，客户端可据此选本地声线资源播放。
     */
    private static TtsClip synthesizeLocalPlaceholder(String text, String voice) {
        byte[] magic = "OPUS16K".getBytes(StandardCharsets.US_ASCII);
        byte[] voiceBytes = voice.getBytes(StandardCharsets.UTF_8);
        int hash = text.hashCode();
        byte[] out = new byte[magic.length + 2 + Math.min(voiceBytes.length, 32) + 4];
        System.arraycopy(magic, 0, out, 0, magic.length);
        out[magic.length] = (byte) Math.min(voiceBytes.length, 32);
        out[magic.length + 1] = 0;
        int copy = Math.min(voiceBytes.length, 32);
        System.arraycopy(voiceBytes, 0, out, magic.length + 2, copy);
        int off = magic.length + 2 + copy;
        out[off] = (byte) (hash >>> 24);
        out[off + 1] = (byte) (hash >>> 16);
        out[off + 2] = (byte) (hash >>> 8);
        out[off + 3] = (byte) hash;
        // 填充到接近 16kbps×0.2s 的量级，便于客户端缓冲策略联调
        byte[] padded = Arrays.copyOf(out, Math.max(out.length, 64));
        return new TtsClip(padded, "opus", voice);
    }
}
