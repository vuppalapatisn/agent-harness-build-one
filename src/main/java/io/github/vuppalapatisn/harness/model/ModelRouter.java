package io.github.vuppalapatisn.harness.model;

import io.github.vuppalapatisn.harness.config.HarnessProperties;
import io.github.vuppalapatisn.harness.config.HarnessProperties.Route;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Resolves a use case to a configured model route ({@code harness.models.routes.*}). */
@Component
public class ModelRouter {

    public static final String AGENT = "agent";
    public static final String FALLBACK = "fallback";
    public static final String SUMMARIZE = "summarize";

    private final HarnessProperties properties;

    public ModelRouter(HarnessProperties properties) {
        this.properties = properties;
    }

    public Route route(String useCase) {
        Route route = properties.getModels().getRoutes().get(useCase);
        if (route == null) {
            throw new IllegalStateException("No model route configured for harness.models.routes." + useCase);
        }
        return route;
    }

    public Optional<Route> fallback() {
        return Optional.ofNullable(properties.getModels().getRoutes().get(FALLBACK));
    }
}
