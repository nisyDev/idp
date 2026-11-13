package com.empresa.pdi.controller;

import com.empresa.pdi.dto.CriarPdiRequest;
import com.empresa.pdi.dto.AtualizarPdiRascunhoRequest;
import com.empresa.pdi.dto.PdiRascunhoResponse;
import com.empresa.pdi.service.PdiService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import java.net.URI;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/pdis")
public class PDIController {

    private final PdiService service;

    public PDIController(PdiService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<PdiRascunhoResponse>> listar() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.listarRascunhos());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PdiRascunhoResponse> buscar(@PathVariable Long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.buscarRascunho(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PdiRascunhoResponse> atualizar(@PathVariable Long id,
                                                       @RequestBody AtualizarPdiRascunhoRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.atualizarRascunho(id, request));
    }

    @PostMapping
    public ResponseEntity<PdiRascunhoResponse> criar(@RequestBody CriarPdiRequest request) {
        PdiRascunhoResponse response = service.criar(request);
        return ResponseEntity.created(URI.create("/api/pdis/" + response.id()))
                .cacheControl(CacheControl.noStore()).body(response);
    }
}
