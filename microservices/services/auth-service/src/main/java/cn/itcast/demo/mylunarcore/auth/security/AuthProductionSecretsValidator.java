package cn.itcast.demo.mylunarcore.auth.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * auth-service prod：拒绝默认 JWT 密钥与明文演示口令。
 */
@Component
@Profile("prod")
public class AuthProductionSecretsValidator {

    private static final Logger log = LoggerFactory.getLogger(AuthProductionSecretsValidator.class);

    private static final Set<String> FORBIDDEN_SECRETS = Set.of(
            "change-me-to-a-secure-32-byte-secret!",
            "change-me-to-a-secure-32-byte-secret",
            "change-me",
            "changeme",
            "secret"
    );

    private static final Set<String> FORBIDDEN_PASSWORDS = Set.of(
            "123456", "admin123", "password", "admin", "root", "changeit"
    );

    public AuthProductionSecretsValidator(Environment environment, AuthJwtProperties jwtProperties) {
        List<String> errors = validate(environment, jwtProperties);
        if (!errors.isEmpty()) {
            String message = "auth-service production secrets validation failed: " + String.join("; ", errors);
            log.error(message);
            throw new IllegalStateException(message);
        }
        log.info("auth-service production secrets validation passed");
    }

    static List<String> validate(Environment environment, AuthJwtProperties jwtProperties) {
        List<String> errors = new ArrayList<>();
        String secret = firstNonBlank(
                environment.getProperty("mylunarcore.auth.jwt.secret"),
                environment.getProperty("AUTH_JWT_SECRET"),
                jwtProperties == null ? null : jwtProperties.getSecret());
        if (isBlank(environment.getProperty("AUTH_JWT_SECRET")) && isBlank(System.getenv("AUTH_JWT_SECRET"))) {
            errors.add("AUTH_JWT_SECRET env var is required in prod (do not rely on application.yml alone)");
        }
        if (secret == null || secret.isBlank()) {
            errors.add("mylunarcore.auth.jwt.secret / AUTH_JWT_SECRET is required");
        } else if (FORBIDDEN_SECRETS.contains(secret.trim().toLowerCase(Locale.ROOT))
                || secret.toLowerCase(Locale.ROOT).contains("change-me")) {
            errors.add("jwt secret must not use a default/weak value");
        } else if (secret.getBytes().length < 32) {
            errors.add("jwt secret must be at least 32 bytes");
        } else {
            boolean hasLetter = secret.chars().anyMatch(Character::isLetter);
            boolean hasDigit = secret.chars().anyMatch(Character::isDigit);
            boolean hasSpecial = secret.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch));
            if (!hasLetter || !hasDigit || !hasSpecial) {
                errors.add("jwt secret must contain letters, digits and a special character");
            }
        }

        Map<String, AuthJwtProperties.UserCredential> users =
                jwtProperties == null ? Map.of() : jwtProperties.getUsers();
        if (users != null) {
            for (Map.Entry<String, AuthJwtProperties.UserCredential> e : users.entrySet()) {
                String password = e.getValue() == null ? null : e.getValue().getPassword();
                if (password != null && FORBIDDEN_PASSWORDS.contains(password.trim().toLowerCase(Locale.ROOT))) {
                    errors.add("user '" + e.getKey() + "' must not use a demo/weak password in prod");
                }
            }
        }
        return errors;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
