package com.empresa.pdi.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"demo-local", "auth-local"})
public class CsrfController {
    @GetMapping("/api/csrf")
    public ResponseEntity<CsrfResponse> token(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CsrfResponse(token.getHeaderName(), token.getParameterName(), token.getToken()));
    }

    public record CsrfResponse(String headerName, String parameterName, String token) { }
}
