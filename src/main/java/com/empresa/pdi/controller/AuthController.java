package com.empresa.pdi.controller;

import com.empresa.pdi.dto.UsuarioResponse;
import com.empresa.pdi.service.IdentidadeAutenticadaService;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("auth-local")
@RequestMapping("/api/auth")
public class AuthController {
    private final IdentidadeAutenticadaService identidade;

    public AuthController(IdentidadeAutenticadaService identidade) {
        this.identidade = identidade;
    }

    @GetMapping("/me")
    public ResponseEntity<UsuarioResponse> me() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(identidade.consultar());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, String>> autenticacaoInvalida() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore())
                .body(Map.of("message", "Autenticação inválida"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> falhaNaConsulta() {
        // Keep persistence errors and internal exception messages out of the HTTP response.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).cacheControl(CacheControl.noStore())
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Não foi possível consultar a identidade"));
    }
}
