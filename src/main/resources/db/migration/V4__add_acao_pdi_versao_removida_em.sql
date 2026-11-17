-- G8: preserve action audit FKs while removing content from an editable proposal.
-- Additive only: existing versions and actions remain effective (NULL).
ALTER TABLE acao_pdi_versao ADD COLUMN removida_em TIMESTAMPTZ;
