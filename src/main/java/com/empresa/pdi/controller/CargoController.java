package com.empresa.pdi.controller;

import com.empresa.pdi.dto.CargoResponse;
import com.empresa.pdi.service.CargoConsultaService;
import java.util.List;
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
@RequestMapping("/api/cargos")
public class CargoController {
    private final CargoConsultaService cargos;

    public CargoController(CargoConsultaService cargos) {
        this.cargos = cargos;
    }

    @GetMapping
    public ResponseEntity<List<CargoResponse>> listar() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cargos.listar());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, String>> autenticacaoInvalida() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore())
                .body(Map.of("message", "Autenticação inválida"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> falhaNaConsulta() {
        // Keep SQL, exception messages and persistence details out of the response.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).cacheControl(CacheControl.noStore())
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Não foi possível consultar os cargos"));
    }
}
