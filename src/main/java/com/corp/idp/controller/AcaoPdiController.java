package com.corp.idp.controller;

import com.corp.idp.model.AcaoPDI;
import com.corp.idp.repository.AcaoPdiRepository;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/acoes-pdi")
public class AcaoPdiController {

    private final AcaoPdiRepository repo;

    public AcaoPdiController(AcaoPdiRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<AcaoPDI> listar() {
        return repo.findAll();
    }
}