package com.flowledger.forecast.controller;

import com.flowledger.forecast.dto.ForecastRunResponse;
import com.flowledger.forecast.dto.ForecastRunSummaryResponse;
import com.flowledger.forecast.dto.ForecastVsActualResponse;
import com.flowledger.forecast.service.ForecastService;
import com.flowledger.forecast.service.ForecastVsActualService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/forecast/companies/{companyId}/forecast-runs")
@RequiredArgsConstructor
public class ForecastRunController {

    private final ForecastService forecastService;
    private final ForecastVsActualService forecastVsActualService;

    // 201 for a new run; 200 with the existing run when nothing changed since it was made today.
    @PostMapping
    public ResponseEntity<ForecastRunResponse> createRun(@AuthenticationPrincipal Jwt user,
                                                         @PathVariable UUID companyId) {
        ForecastService.CreatedRun result = forecastService.createRun(user, companyId);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.forecast());
    }

    @GetMapping
    public List<ForecastRunSummaryResponse> listRuns(@AuthenticationPrincipal Jwt user, @PathVariable UUID companyId) {
        return forecastService.listRuns(user, companyId);
    }

    @GetMapping("/{runId}")
    public ForecastRunResponse getRun(@AuthenticationPrincipal Jwt user,
                                      @PathVariable UUID companyId,
                                      @PathVariable UUID runId) {
        return forecastService.getRun(user, companyId, runId);
    }

    @GetMapping("/{runId}/forecast-vs-actual")
    public ForecastVsActualResponse compare(@AuthenticationPrincipal Jwt user,
                                            @PathVariable UUID companyId,
                                            @PathVariable UUID runId) {
        return forecastVsActualService.compare(user, companyId, runId);
    }
}
