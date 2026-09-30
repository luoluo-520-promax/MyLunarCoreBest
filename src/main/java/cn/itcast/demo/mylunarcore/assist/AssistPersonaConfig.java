package cn.itcast.demo.mylunarcore.assist;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AssistPersonaConfig(
        int version,
        String defaultPersonaId,
        List<PersonaEntry> personas,
        Map<String, String> uidBindings
) {
    public static AssistPersonaConfig empty() {
        return new AssistPersonaConfig(0, "", List.of(), Map.of());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PersonaEntry(
            String personaId,
            String displayName,
            int avatarId,
            String voiceId,
            int minAffinity,
            String stylePrompt,
            String greetingPrefix
    ) {
    }
}
