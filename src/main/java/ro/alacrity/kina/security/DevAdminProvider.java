package ro.alacrity.kina.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * Development mode: owns the fake admin ({@code users} row issuer {@code dev}, subject {@code admin}, display name
 * {@code Development Admin}). The row is created on startup and lazily again if it disappeared.
 */
@Component
@Conditional(DevModeCondition.class)
public class DevAdminProvider implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevAdminProvider.class);

    private final UserRepository users;
    private volatile KinaPrincipal principal;

    public DevAdminProvider(UserRepository users) {
        this.users = users;
    }

    @Override
    public void run(ApplicationArguments args) {
        KinaPrincipal admin = principal();
        log.warn("KINA runs in DEVELOPMENT mode: requests without credentials act as '{}' ({}). "
                + "Set KINA_MODE=prod for production.", admin.displayName(), admin.userId());
    }

    public KinaPrincipal principal() {
        KinaPrincipal current = principal;
        if (current == null) {
            current = users.devAdmin().toPrincipal();
            principal = current;
        }
        return current;
    }
}
