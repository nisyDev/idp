package com.empresa.pdi.controller;

import com.empresa.pdi.dto.CriarPdiRequest;
import com.empresa.pdi.dto.AtualizarPdiRascunhoRequest;
import com.empresa.pdi.dto.PdiRascunhoResponse;
import com.empresa.pdi.dto.PdiSubmetidoResponse;
import com.empresa.pdi.dto.PdiVersaoResponse;
import com.empresa.pdi.dto.PdiDecisaoRequest;
import com.empresa.pdi.dto.PdiDecisaoResponse;
import com.empresa.pdi.dto.AcaoPdiResponse;
import com.empresa.pdi.service.PdiService;
import com.empresa.pdi.service.AcaoPdiService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import java.net.URI;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/pdis")
public class PDIController {

    private final PdiService service;
    private final AcaoPdiService acoes;

    public PDIController(PdiService service, AcaoPdiService acoes) {
        this.service = service;
        this.acoes = acoes;
    }

    @GetMapping
    public ResponseEntity<List<PdiRascunhoResponse>> listar() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.listarRascunhos());
    }

    // Submitted proposals remain visible after leaving the draft-only GET /api/pdis.
    @GetMapping("/submetidos")
    public ResponseEntity<List<PdiSubmetidoResponse>> listarSubmetidos() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.listarSubmetidos());
    }

    @PostMapping("/{id}/submeter")
    public ResponseEntity<PdiSubmetidoResponse> submeter(@PathVariable Long id,
                                                       @RequestParam(required = false) Long versaoId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.submeter(id, versaoId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PdiRascunhoResponse> buscar(@PathVariable Long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.buscarRascunho(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PdiRascunhoResponse> atualizar(@PathVariable Long id,
                                                       @RequestBody AtualizarPdiRascunhoRequest request,
                                                       @RequestParam(required = false) Long versaoId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.atualizarRascunho(id, request, versaoId));
    }

    @PostMapping
    public ResponseEntity<PdiRascunhoResponse> criar(@RequestBody CriarPdiRequest request) {
        PdiRascunhoResponse response = service.criar(request);
        return ResponseEntity.created(URI.create("/api/pdis/" + response.id()))
                .cacheControl(CacheControl.noStore()).body(response);
    }

    @GetMapping("/pendentes")
    public ResponseEntity<List<PdiVersaoResponse>> listarPendentes() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.listarPendentes());
    }

    @GetMapping("/{id}/versoes")
    public ResponseEntity<List<PdiVersaoResponse>> listarVersoes(@PathVariable Long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.listarVersoes(id));
    }

    @GetMapping("/{id}/versoes/{versaoId}")
    public ResponseEntity<PdiVersaoResponse> buscarVersao(@PathVariable Long id, @PathVariable Long versaoId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.buscarVersao(id, versaoId));
    }

    @GetMapping("/{id}/versoes/{versaoId}/acoes")
    public ResponseEntity<List<AcaoPdiResponse>> listarAcoes(@PathVariable Long id, @PathVariable Long versaoId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(acoes.listarNaVersao(id, versaoId));
    }

    @PostMapping("/{id}/versoes/{versaoId}/aprovar")
    public ResponseEntity<PdiDecisaoResponse> aprovar(@PathVariable Long id, @PathVariable Long versaoId,
                                                   @RequestBody(required = false) PdiDecisaoRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.aprovar(id, versaoId, request));
    }

    @PostMapping("/{id}/versoes/{versaoId}/reprovar")
    public ResponseEntity<PdiDecisaoResponse> reprovar(@PathVariable Long id, @PathVariable Long versaoId,
                                                    @RequestBody PdiDecisaoRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.reprovar(id, versaoId, request));
    }
}
