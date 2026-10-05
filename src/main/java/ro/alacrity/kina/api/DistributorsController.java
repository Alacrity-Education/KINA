package ro.alacrity.kina.api;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.alacrity.kina.domain.DistributorStatusResponse;
import ro.alacrity.kina.search.DistributorStatusService;

/** {@code GET /api/v1/distributors}: same payload as the {@code list_distributors} MCP tool. */
@RestController
@RequestMapping("/api/v1/distributors")
@RequiredArgsConstructor
public class DistributorsController {

    private final DistributorStatusService statusService;

    @GetMapping
    public DistributorStatusResponse list() {
        return statusService.status();
    }
}
