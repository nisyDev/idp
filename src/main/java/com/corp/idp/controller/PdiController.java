package com.corp.idp.controller;

import com.corp.idp.model.PDI;
import com.corp.idp.repository.PdiRepository;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/pdis")
public class PdiController {

    private final PdiRepository repo;

    public PdiController(PdiRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<PDI> listar() {
        return repo.findAll();
    }
}