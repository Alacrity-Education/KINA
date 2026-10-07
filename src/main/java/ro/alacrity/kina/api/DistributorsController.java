package ro.alacrity.kina.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.alacrity.kina.domain.DistributorStatusResponse;
import ro.alacrity.kina.metrics.KinaMetrics;
import ro.alacrity.kina.search.DistributorStatusService;

/** {@code GET /api/v1/distributors}: same payload as the {@code list_distributors} MCP tool. */
@RestController
@RequestMapping("/api/v1/distributors")
public class DistributorsController {

    @Autowired private DistributorStatusService statusService;
    @Autowired private KinaMetrics metrics;

    @GetMapping
    public DistributorStatusResponse list() {
        return statusService.status().withMetrics(metrics.summary());
    }
}
