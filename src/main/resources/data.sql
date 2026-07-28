INSERT INTO cargo (id, nome, nivel, ativo) VALUES
    (random_uuid(), 'Desenvolvedor', 'Senior', true);

INSERT INTO usuario (id, nome, email, cargo_id, perfil_acesso, ativo) VALUES
                                                                          (random_uuid(), 'Denise Silva', 'denise@bgp.com',
                                                                           (SELECT id FROM cargo LIMIT 1), 'COLABORADOR', true);

INSERT INTO pdi (id, usuario_id, titulo, objetivo, prazo, status_pdi) VALUES
                                                                          (random_uuid(), (SELECT id FROM usuario LIMIT 1),
    'Evoluir para Tech Lead', 'Desenvolver habilidades de liderança técnica',
    '2026-12-31', 'APROVADO');

INSERT INTO equipe (id, nome, lider_id, descricao, ativo) VALUES
                                                              (random_uuid(), 'Squad Plataforma', (SELECT id FROM usuario LIMIT 1), 'Time responsável pela plataforma de PDI', true);

INSERT INTO usuario_equipe (id, usuario_id, equipe_id, data_entrada) VALUES
                                                                         (random_uuid(), (SELECT id FROM usuario LIMIT 1), (SELECT id FROM equipe LIMIT 1), '2025-01-15');

INSERT INTO acaopdi (id, pdi_id, titulo, descricao, tipo_acao, prazo, status_acao, recurso, metrica, ordem) VALUES
                                                                                                                 (random_uuid(), (SELECT id FROM pdi LIMIT 1), 'Curso de Liderança', 'Concluir curso de liderança técnica',
    'CURSO', '2026-10-01', 'PENDENTE', 'https://exemplo.com/curso', 'Certificado emitido', 1);