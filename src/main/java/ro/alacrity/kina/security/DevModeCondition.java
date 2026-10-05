package ro.alacrity.kina.security;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import ro.alacrity.kina.config.KinaProperties;

/** Matches when {@code kina.security.mode} binds to {@code DEV} (the default). */
public class DevModeCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return mode(context) == KinaProperties.Mode.DEV;
    }

    static KinaProperties.Mode mode(ConditionContext context) {
        return Binder.get(context.getEnvironment())
                .bind("kina.security.mode", KinaProperties.Mode.class)
                .orElse(KinaProperties.Mode.DEV);
    }

    /** Matches when {@code kina.security.mode} binds to {@code PROD}. */
    public static class Prod implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return mode(context) == KinaProperties.Mode.PROD;
        }
    }
}
