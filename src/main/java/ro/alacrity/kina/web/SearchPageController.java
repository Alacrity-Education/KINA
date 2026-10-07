package ro.alacrity.kina.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.DistributorRegistry;
import ro.alacrity.kina.domain.Distributor;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** The Search tab of the web UI (DESIGN.md section 6, "Web UI"), the default page: {@code GET /} and {@code /search}. */
@Controller
public class SearchPageController {

    @Autowired private DistributorRegistry registry;
    @Autowired private PublicUrlResolver urls;
    @Autowired private KinaProperties properties;

    /** One distributor checkbox of the form. */
    public record DistributorOption(String name, boolean configured, boolean checked) {
    }

    @GetMapping({"/", "/search"})
    public ModelAndView search(Authentication authentication) {
        ModelAndView view = WebTabs.view("search", WebTabs.SEARCH, WebTabs.user(authentication));
        Set<Distributor> configured = registry.configured();
        List<DistributorOption> options = Arrays.stream(Distributor.values())
                .map(d -> new DistributorOption(d.name(), configured.contains(d), configured.contains(d)))
                .toList();
        view.addObject("distributorOptions", options);
        view.addObject("mcpUrl", urls.mcpUrl());
        view.addObject("tokensEnabled", properties.tokens().uiEnabled());
        return view;
    }
}
