package com.payrollforge.shared.security;

import com.payrollforge.shared.config.PayrollForgeProperties;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Turns the bearer token into a {@link Caller}. Roles come from configuration, not from token claims. */
@Component
public class CallerResolver implements HandlerMethodArgumentResolver {

    private final Map<Role, Set<String>> subjects;
    private final EmployeeIdentity identity;

    public CallerResolver(PayrollForgeProperties props, EmployeeIdentity identity) {
        var s = props.security();
        this.subjects = Map.of(Role.HR, set(s.hrSubjects()), Role.PAYROLL_MAKER, set(s.makerSubjects()),
                Role.PAYROLL_CHECKER, set(s.checkerSubjects()), Role.DEVICE, set(s.deviceSubjects()));
        this.identity = identity;
    }

    private static Set<String> set(List<String> l) {
        return l == null ? Set.of() : Set.copyOf(l);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Caller.class;
    }

    @Override
    public Caller resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                  WebDataBinderFactory binders) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt) || jwt.getSubject() == null) {
            throw new IllegalStateException("no authenticated subject on a /v1 request");
        }
        String subject = jwt.getSubject();
        var roles = EnumSet.noneOf(Role.class);
        subjects.forEach((role, subs) -> {
            if (subs.contains(subject)) {
                roles.add(role);
            }
        });
        UUID employee = identity.employeeFor(subject).orElse(null);
        if (employee != null) {
            roles.add(Role.EMPLOYEE);
        }
        return new Caller(subject, roles, employee);
    }
}
