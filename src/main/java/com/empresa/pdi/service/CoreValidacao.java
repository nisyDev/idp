package com.empresa.pdi.service;

import com.empresa.pdi.model.Equipe;
import com.empresa.pdi.model.Usuario;

import java.util.Objects;

final class CoreValidacao {
    private CoreValidacao() { }

    static <T> T obrigatorio(T valor, String campo) {
        if (valor == null) {
            throw new IllegalArgumentException(campo + " é obrigatório");
        }
        return valor;
    }

    static void exigir(boolean condicao, String mensagem) {
        if (!condicao) {
            throw new IllegalArgumentException(mensagem);
        }
    }

    static void exigirNaoLider(Usuario usuario, Equipe equipe) {
        exigir(equipe.getLider() == null
                        || !Objects.equals(equipe.getLider().getId(), usuario.getId()),
                "Substitua ou remova a liderança antes desta operação");
    }
}
