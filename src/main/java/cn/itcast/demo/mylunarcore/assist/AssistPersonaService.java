package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 按 UID 绑定已拥有/高好感角色人设，包装回答语气。
 */
@Service
public class AssistPersonaService {

    private final LunarCoreProperties properties;
    private final AssistPersonaRepository repository;
    private final GameSessionManager sessionManager;

    public AssistPersonaService(LunarCoreProperties properties,
                                AssistPersonaRepository repository,
                                GameSessionManager sessionManager) {
        this.properties = properties;
        this.repository = repository;
        this.sessionManager = sessionManager;
    }

    public Optional<AssistPersonaConfig.PersonaEntry> resolve(long uid) {
        if (!properties.getAiAssist().isPersonaEnabled() || uid <= 0) {
            return Optional.empty();
        }
        AssistPersonaConfig cfg = repository.current();
        if (cfg == null || cfg.personas() == null || cfg.personas().isEmpty()) {
            return Optional.empty();
        }
        if (cfg.uidBindings() != null) {
            String bound = cfg.uidBindings().get(String.valueOf(uid));
            if (bound != null && !bound.isBlank()) {
                Optional<AssistPersonaConfig.PersonaEntry> hit = findById(cfg.personas(), bound);
                if (hit.isPresent()) {
                    return hit;
                }
            }
        }
        Optional<AssistPersonaConfig.PersonaEntry> owned = pickOwnedHighAffinity(uid, cfg.personas());
        if (owned.isPresent()) {
            return owned;
        }
        return findById(cfg.personas(), cfg.defaultPersonaId())
                .or(() -> Optional.of(cfg.personas().get(0)));
    }

    public String wrapAnswer(long uid, String answer) {
        if (answer == null || answer.isBlank()) {
            return answer == null ? "" : answer;
        }
        Optional<AssistPersonaConfig.PersonaEntry> persona = resolve(uid);
        if (persona.isEmpty()) {
            return answer;
        }
        AssistPersonaConfig.PersonaEntry p = persona.get();
        String prefix = p.greetingPrefix() == null || p.greetingPrefix().isBlank()
                ? ""
                : p.greetingPrefix().trim() + " ";
        // 弱化工业味免责：人设开启时把免责声明后置为更口语化尾巴
        String body = answer;
        String disclaimer = properties.getAiAssist().getComplianceDisclaimer();
        if (disclaimer != null && !disclaimer.isBlank() && body.contains(disclaimer)) {
            body = body.replace(disclaimer, "").trim();
        }
        return prefix + body + " ——「" + nullToEmpty(p.displayName()) + "」";
    }

    public String voiceId(long uid) {
        return resolve(uid).map(AssistPersonaConfig.PersonaEntry::voiceId).orElse("");
    }

    public String personaId(long uid) {
        return resolve(uid).map(AssistPersonaConfig.PersonaEntry::personaId).orElse("");
    }

    private Optional<AssistPersonaConfig.PersonaEntry> pickOwnedHighAffinity(
            long uid, List<AssistPersonaConfig.PersonaEntry> personas) {
        GameSession session = sessionManager.getOrNull(uid);
        if (session == null || session.getPlayerData() == null
                || session.getPlayerData().getAvatars() == null) {
            return Optional.empty();
        }
        var owned = session.getPlayerData().getAvatars();
        AssistPersonaConfig.PersonaEntry best = null;
        int bestScore = -1;
        for (AssistPersonaConfig.PersonaEntry p : personas) {
            if (p == null || p.avatarId() <= 0) {
                continue;
            }
            boolean has = owned.stream().anyMatch(a -> a != null && a.getAvatarId() == p.avatarId());
            if (!has) {
                continue;
            }
            // 无独立好感字段时，用角色等级近似「高好感」
            int level = owned.stream()
                    .filter(a -> a != null && a.getAvatarId() == p.avatarId())
                    .mapToInt(a -> Math.max(0, a.getLevel()))
                    .findFirst()
                    .orElse(0);
            if (level < Math.max(0, p.minAffinity())) {
                continue;
            }
            if (level > bestScore) {
                bestScore = level;
                best = p;
            }
        }
        return Optional.ofNullable(best);
    }

    private static Optional<AssistPersonaConfig.PersonaEntry> findById(
            List<AssistPersonaConfig.PersonaEntry> personas, String id) {
        if (id == null || id.isBlank() || personas == null) {
            return Optional.empty();
        }
        String key = id.trim().toLowerCase(Locale.ROOT);
        return personas.stream()
                .filter(p -> p != null && p.personaId() != null
                        && p.personaId().trim().toLowerCase(Locale.ROOT).equals(key))
                .findFirst();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
