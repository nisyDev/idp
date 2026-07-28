package com.corp.idp.controller;

import com.corp.idp.model.Equipe;
import com.corp.idp.repository.EquipeRepository;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/equipes")
public class EquipeController {

    private final EquipeRepository repo;

    public EquipeController(EquipeRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<Equipe> listar() {
        return repo.findAll();
    }
}