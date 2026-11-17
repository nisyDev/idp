package com.empresa.pdi.controller;

import com.empresa.pdi.dto.AcaoPdiResponse;
import com.empresa.pdi.dto.AtualizarAcaoPdiRequest;
import com.empresa.pdi.dto.CriarAcaoPdiRequest;
import com.empresa.pdi.service.AcaoPdiService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/pdis/{pdiId}/acoes")
public class AcaoPDIController {

    private final AcaoPdiService service;

    public AcaoPDIController(AcaoPdiService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<AcaoPdiResponse>> listar(@PathVariable Long pdiId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.listar(pdiId));
    }

    @GetMapping("/{acaoId}")
    public ResponseEntity<AcaoPdiResponse> buscar(@PathVariable Long pdiId, @PathVariable Long acaoId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.buscar(pdiId, acaoId));
    }

    @PostMapping
    public ResponseEntity<AcaoPdiResponse> criar(@PathVariable Long pdiId,
                                               @RequestParam(required = false) Long versaoId,
                                               @RequestBody CriarAcaoPdiRequest request) {
        AcaoPdiResponse response = service.criar(pdiId, request, versaoId);
        return ResponseEntity.created(URI.create("/api/pdis/" + pdiId + "/acoes/" + response.id()))
                .cacheControl(CacheControl.noStore()).body(response);
    }

    @PutMapping("/{acaoId}")
    public ResponseEntity<AcaoPdiResponse> atualizar(@PathVariable Long pdiId, @PathVariable Long acaoId,
                                                   @RequestParam(required = false) Long versaoId,
                                                   @RequestBody AtualizarAcaoPdiRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.atualizarRascunho(pdiId, acaoId, request, versaoId));
    }

    @DeleteMapping("/{acaoId}")
    public ResponseEntity<Void> remover(@PathVariable Long pdiId, @PathVariable Long acaoId,
                                        @RequestParam(required = false) Long versaoId) {
        service.remover(pdiId, acaoId, versaoId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
