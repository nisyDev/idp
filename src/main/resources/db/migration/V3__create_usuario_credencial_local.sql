-- TalentSynk: optional local credentials. No seeds or changes to CORE/PDI data.
-- Use the same public schema configured for V1 and V2.
-- A user without this row has no local password (for example, a future SSO account).
-- Hashes are raw BCrypt strings, without a {bcrypt} prefix. Hash generation and
-- complete BCrypt format validation belong to the application, not to this DDL.
-- The application maintains timestamps; defaults also support explicit SQL inserts.

CREATE TABLE usuario_credencial_local (
    usuario_id UUID NOT NULL,
    senha_hash VARCHAR(60) NOT NULL,
    criada_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    atualizada_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_usuario_credencial_local PRIMARY KEY (usuario_id),
    CONSTRAINT fk_usuario_credencial_local_usuario FOREIGN KEY (usuario_id)
        REFERENCES usuario (id) ON UPDATE NO ACTION ON DELETE NO ACTION,
    CONSTRAINT ck_usuario_credencial_local_hash_tamanho CHECK (CHAR_LENGTH(senha_hash) = 60)
);

-- The primary key index also covers the foreign key; no sequence is needed.
