package com.empresa.pdi.service;

import com.empresa.pdi.dto.CargoResponse;
import com.empresa.pdi.repository.CargoRepository;
import com.empresa.pdi.security.UsuarioAutenticadoProvider;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("auth-local")
public class CargoConsultaService {
    private final CargoRepository cargos;
    private final UsuarioAutenticadoProvider autenticacao;

    public CargoConsultaService(CargoRepository cargos, UsuarioAutenticadoProvider autenticacao) {
        this.cargos = cargos;
        this.autenticacao = autenticacao;
    }

    @Transactional(readOnly = true)
    public List<CargoResponse> listar() {
        // The provider rechecks the authenticated account before any catalog query.
        if (autenticacao.obterIdUsuarioAutenticado() == null) {
            throw new BadCredentialsException("Autenticação inválida");
        }
        // The same database predicate applies to every authorized role, including RH_ADMIN.
        return cargos.findByAtivoTrue().stream().map(CargoResponse::from).toList();
    }
}
