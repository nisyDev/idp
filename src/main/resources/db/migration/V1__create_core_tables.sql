-- TalentSynk Core. UUIDs are supplied by the application.
-- Text fields have no arbitrary length limit. Descriptions are optional.
-- Modification timestamps are maintained by the application.
-- Cross-row business rules and immutable history are enforced by services.

CREATE TABLE cargo (
    id UUID NOT NULL,
    nome TEXT NOT NULL,
    descricao TEXT,
    nivel VARCHAR(20) NOT NULL,
    ativo BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_cargo PRIMARY KEY (id),
    CONSTRAINT ck_cargo_nivel CHECK (
        nivel IN ('JUNIOR', 'PLENO', 'SENIOR', 'NAO_APLICAVEL')
    )
);

CREATE TABLE equipe (
    id UUID NOT NULL,
    nome TEXT NOT NULL,
    lider_id UUID,
    descricao TEXT,
    ativo BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_equipe PRIMARY KEY (id),
    CONSTRAINT uq_equipe_lider UNIQUE (lider_id)
);

CREATE TABLE usuario (
    id UUID NOT NULL,
    ad_object_id TEXT,
    nome TEXT NOT NULL,
    email TEXT NOT NULL,
    cargo_id UUID NOT NULL,
    gestor_id UUID,
    perfil_acesso VARCHAR(20) NOT NULL,
    ativo BOOLEAN NOT NULL DEFAULT TRUE,
    data_criacao TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    data_atualizacao TIMESTAMPTZ NOT NULL,
    equipe_atual_id UUID NOT NULL,
    CONSTRAINT pk_usuario PRIMARY KEY (id),
    CONSTRAINT uq_usuario_ad_object_id UNIQUE (ad_object_id),
    CONSTRAINT ck_usuario_perfil_acesso CHECK (
        perfil_acesso IN ('COLABORADOR', 'GESTOR', 'RH_ADMIN')
    ),
    CONSTRAINT ck_usuario_gestor_nao_proprio CHECK (
        gestor_id IS NULL OR gestor_id <> id
    ),
    CONSTRAINT fk_usuario_cargo FOREIGN KEY (cargo_id)
        REFERENCES cargo (id) ON UPDATE NO ACTION ON DELETE NO ACTION,
    CONSTRAINT fk_usuario_gestor FOREIGN KEY (gestor_id)
        REFERENCES usuario (id) ON UPDATE NO ACTION ON DELETE NO ACTION,
    CONSTRAINT fk_usuario_equipe_atual FOREIGN KEY (equipe_atual_id)
        REFERENCES equipe (id) ON UPDATE NO ACTION ON DELETE NO ACTION
);

-- Complete the circular reference only after both tables exist.
ALTER TABLE equipe
    ADD CONSTRAINT fk_equipe_lider FOREIGN KEY (lider_id)
        REFERENCES usuario (id) ON UPDATE NO ACTION ON DELETE NO ACTION;

CREATE TABLE historico_equipe (
    id UUID NOT NULL,
    usuario_id UUID NOT NULL,
    equipe_origem_id UUID,
    equipe_destino_id UUID NOT NULL,
    data_transferencia TIMESTAMPTZ NOT NULL,
    realizado_por_id UUID,
    observacao TEXT,
    CONSTRAINT pk_historico_equipe PRIMARY KEY (id),
    CONSTRAINT ck_historico_equipe_origem_destino CHECK (
        equipe_origem_id IS NULL OR equipe_origem_id <> equipe_destino_id
    ),
    CONSTRAINT fk_historico_equipe_usuario FOREIGN KEY (usuario_id)
        REFERENCES usuario (id) ON UPDATE NO ACTION ON DELETE NO ACTION,
    CONSTRAINT fk_historico_equipe_origem FOREIGN KEY (equipe_origem_id)
        REFERENCES equipe (id) ON UPDATE NO ACTION ON DELETE NO ACTION,
    CONSTRAINT fk_historico_equipe_destino FOREIGN KEY (equipe_destino_id)
        REFERENCES equipe (id) ON UPDATE NO ACTION ON DELETE NO ACTION,
    CONSTRAINT fk_historico_equipe_realizado_por FOREIGN KEY (realizado_por_id)
        REFERENCES usuario (id) ON UPDATE NO ACTION ON DELETE NO ACTION
);

-- No predicate on ativo: inactive users also reserve their email address.
CREATE UNIQUE INDEX uq_usuario_email_lower ON usuario (LOWER(email));

CREATE INDEX ix_usuario_cargo_id ON usuario (cargo_id);
CREATE INDEX ix_usuario_gestor_id ON usuario (gestor_id);
CREATE INDEX ix_usuario_equipe_atual_id ON usuario (equipe_atual_id);

-- Also covers lookups and FK checks by usuario_id alone.
CREATE INDEX ix_historico_equipe_usuario_data
    ON historico_equipe (usuario_id, data_transferencia);
CREATE INDEX ix_historico_equipe_origem_id
    ON historico_equipe (equipe_origem_id);
CREATE INDEX ix_historico_equipe_destino_id
    ON historico_equipe (equipe_destino_id);
CREATE INDEX ix_historico_equipe_realizado_por_id
    ON historico_equipe (realizado_por_id);

-- PK/UNIQUE constraints already index ids, lider_id and ad_object_id.
