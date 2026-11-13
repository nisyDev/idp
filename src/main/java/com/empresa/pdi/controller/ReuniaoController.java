package com.empresa.pdi.controller;

import com.empresa.pdi.model.Reuniao;
import com.empresa.pdi.repository.ReuniaoRepository;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reunioes")
@CrossOrigin(origins = "*")
public class ReuniaoController {

    private final ReuniaoRepository repository;

    public ReuniaoController(ReuniaoRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Reuniao> listar() {
        return repository.findAll();
    }

    @GetMapping("/colaborador/{id}")
    public List<Reuniao> listarPorColaborador(@PathVariable UUID id) {
        return repository.findByColaboradorId(id);
    }
}
