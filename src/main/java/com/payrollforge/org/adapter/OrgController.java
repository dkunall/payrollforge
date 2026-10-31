package com.payrollforge.org.adapter;

import com.payrollforge.org.application.Location;
import com.payrollforge.org.application.OrgService;
import com.payrollforge.shared.security.Caller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

@RestController
class OrgController {

    private final OrgService org;

    OrgController(OrgService org) {
        this.org = org;
    }

    record LocationBody(@NotBlank String code, @NotBlank String name, @NotBlank String timezone, double lat,
                        double lng, int radiusMetres, @NotEmpty List<DayOfWeek> weeklyOff) {
    }

    record HolidayBody(@NotNull LocalDate day, @NotBlank String name) {
    }

    @PostMapping("/v1/locations")
    @ResponseStatus(HttpStatus.CREATED)
    Location create(Caller caller, @Valid @RequestBody LocationBody b) {
        return org.createLocation(caller, new Location(b.code(), b.name(), OrgService.zone(b.timezone()), b.lat(), b.lng(),
                b.radiusMetres(), EnumSet.copyOf(b.weeklyOff())));
    }

    @GetMapping("/v1/locations")
    List<Location> list() {
        return org.locations();
    }

    @PostMapping("/v1/locations/{code}/holidays")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void holiday(Caller caller, @PathVariable String code, @Valid @RequestBody HolidayBody b) {
        org.addHoliday(caller, code, b.day(), b.name());
    }

    @GetMapping("/v1/locations/{code}/holidays")
    Map<LocalDate, String> holidays(@PathVariable String code) {
        return org.calendar(code).holidays();
    }
}
