package com.flowledger.forecast.holiday;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Sri Lankan holidays for the calendar; the company in the path is the one the user works in.
@RestController
@RequestMapping("/api/v1/forecast/companies/{companyId}/holidays")
@RequiredArgsConstructor
public class HolidayController {

    private final HolidayService holidayService;

    @GetMapping
    public List<HolidayService.Holiday> list(@AuthenticationPrincipal Jwt user, @PathVariable UUID companyId,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return holidayService.list(user, companyId, from, to);
    }

    @GetMapping("/years")
    public List<HolidayService.Year> years(@AuthenticationPrincipal Jwt user, @PathVariable UUID companyId) {
        return holidayService.years(user, companyId);
    }

    // A platform SYSTEM_ADMIN replaces one year's list from a CSV (date,name,codes).
    @PutMapping(path = "/{year}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<HolidayService.Holiday> replaceYear(@AuthenticationPrincipal Jwt user, @PathVariable UUID companyId,
                                                    @PathVariable int year, @RequestParam("file") MultipartFile file) {
        return holidayService.replaceYear(user, companyId, year, file);
    }
}
