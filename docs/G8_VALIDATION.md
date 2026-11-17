# G8 — validação técnica consolidada

Fechamento em 17/11/2026, sobre a base G7 `3dc60a8`: **G8 implementada e validada
tecnicamente**. Compilação de produção e testes aprovada com Java 21.0.11;
229 cenários locais e nove cenários PostgreSQL aprovados cumulativamente nas
execuções direcionadas autorizadas. A V4 foi aplicada e validada somente na branch
Neon de testes `test-pdi-v2`.

Este estado é local: a G8 ainda não recebeu commit ou push neste trabalho e não
constitui publicação no GitHub ou deploy. Este documento registra evidências;
não autoriza novas execuções, conexões, migrations ou alterações de privilégios.

## Regras e contratos implementados

Uma aprovação promove a versão indicada para vigente, preservando o PDI em
`NAO_INICIADO` e ações em `NAO_INICIADA`. Uma rejeição exige justificativa não branca,
preserva a versão `REPROVADO` e cria a próxima versão em `RASCUNHO`, na mesma transação.
Os eventos `PDI_APROVADO`, `PDI_REPROVADO` e `REVISAO_CRIADA` registram decisão e cópia.
O último contém os IDs das versões e das identidades/conteúdos das ações de origem e destino.

A cópia gera novos `AcaoPDI` e `AcaoPdiVersao`, copiando somente campos planejados e
ordem. Execução, componentes, validações, evidências e entregas históricas não são
transportados para a nova versão. Remover uma ação marca `removidaEm` somente no
conteúdo do rascunho atual e registra `ACAO_REMOVIDA`; as FKs dos eventos são preservadas.
Ações removidas ficam fora das consultas efetivas, cópia e contagem para submissão.

| Método / rota | Resultado |
|---|---|
| `GET /api/pdis` e `GET /api/pdis/{id}` | Rascunho editável atual do titular, incluindo revisões. |
| `PUT /api/pdis/{id}?versaoId={v}` | Atualização do rascunho indicado. |
| `POST /api/pdis/{id}/submeter?versaoId={v}` | Submissão da versão indicada; exige ação efetiva e gestor ativo. |
| `GET /api/pdis/submetidos` | Versões submetidas pelo titular, incluindo decisões. |
| `GET /api/pdis/pendentes` | Pendências do gestor vigente ou RH explicitamente autorizado. |
| `GET /api/pdis/{id}/versoes` | Versões visíveis ao solicitante. |
| `GET /api/pdis/{id}/versoes/{v}` | Dados e metadados de decisão da versão autorizada. |
| `GET /api/pdis/{id}/versoes/{v}/acoes` | Ações efetivas da versão autorizada. |
| `POST /api/pdis/{id}/versoes/{v}/aprovar` | `200`, decisão; corpo `{"justificativa":"..."}` opcional. |
| `POST /api/pdis/{id}/versoes/{v}/reprovar` | `200`, decisão e identificação da nova revisão; justificativa obrigatória. |
| `POST /api/pdis/{id}/acoes?versaoId={v}` | `201`, nova ação no rascunho indicado. |
| `PUT /api/pdis/{id}/acoes/{acaoId}?versaoId={v}` | `200`, ação editada no rascunho indicado. |
| `DELETE /api/pdis/{id}/acoes/{acaoId}?versaoId={v}` | `204`, remoção lógica no rascunho indicado. |

O parâmetro `versaoId` pode ser omitido apenas nas escritas da versão inicial para
compatibilidade G7. Revisões exigem o ID atual; requisições antigas sem ID ou com ID
divergente recebem `409`. Numeração, titular, estados e vínculos são controlados pelo
servidor. Respostas usam DTOs e `Cache-Control: no-store`.
`/submetidos` mantém o DTO G7; justificativa, autor e data da decisão estão
disponíveis nas consultas novas de versões.
Erros distinguem dados inválidos (`400`), autenticação (`401`), autorização (`403`),
recurso inexistente ou fora do escopo (`404`) e estado/concorrência (`409`).

O gestor precisa estar ativo, com perfil `GESTOR` e vínculo atual ao titular.
RH precisa estar ativo, com perfil `RH_ADMIN` e UUID presente na propriedade externa
`talentsynk.pdi.rh-autorizados`, uma lista separada por vírgulas e vazia por padrão.
Identificadores reais não fazem parte do código. A configuração concede somente o
escopo específico de decisão/consulta de versões submetidas previsto pela G8.
Autoaprovação e autorrejeição são bloqueadas. Rascunhos novos permanecem privados.

`gestorNaSubmissao` permanece histórico. Pendências e permissões acompanham
`Usuario.gestor` vigente. Alterar somente a equipe não altera esse vínculo.
Os serviços revalidam permissões e mantêm a ordem de bloqueios: usuários envolvidos
em ordem UUID, PDI, versão. O perfil `auth-local` preserva sessão, CSRF e liberação
explícita de rotas; o perfil padrão segue fechado.

## Validação local consolidada

A compilação de produção e testes foi aprovada com JDK 21.0.11. Os resultados
abaixo consolidam 229 cenários distintos em 11 classes, usando as execuções
direcionadas aprovadas, inclusive a correção e reexecução final de `PdiHttpTest`.
Não representam uma nova bateria única nem a soma das tentativas anteriores.

| Classe | Aprovados |
|---|---:|
| `PdiSubmissaoServiceTest` | 12 |
| `PdiDecisaoServiceTest` | 19 |
| `PdiAcessoServiceTest` | 12 |
| `AcaoPdiServiceTest` | 56 |
| `PdiDecisaoHttpTest` | 14 |
| `PdiHttpTest` | 39 |
| `AcaoPdiEdicaoHttpTest` | 41 |
| `LocalAuthenticationHttpTest` | 31 |
| `PdiMappingBootstrapTest` | 1 |
| `CargoDefaultProfileHttpTest` | 2 |
| `CargoDemoProfileHttpTest` | 2 |
| **Total cumulativo** | **229** |

Nos resultados consolidados aprovados: zero falhas, zero erros e zero ignorados.
O alcance das classes é:

- `PdiSubmissaoServiceTest`: submissão inicial, revisão após rejeição, versão esperada,
  titularidade e conflitos.
- `PdiDecisaoServiceTest` e `PdiAcessoServiceTest`: decisões, cópia independente,
  autorização do gestor vigente, RH explicitamente autorizado, privacidade e locks.
- `AcaoPdiServiceTest`: edição, inclusão e remoção da versão editável atual.
- `PdiDecisaoHttpTest`, `PdiHttpTest`, `AcaoPdiEdicaoHttpTest` e
  `LocalAuthenticationHttpTest`: contratos, sessão, CSRF, isolamento e regressões
  diretamente afetadas.
- `PdiMappingBootstrapTest`: mapeamentos Hibernate sem JDBC, incluindo `removidaEm`.
- `CargoDefaultProfileHttpTest` e `CargoDemoProfileHttpTest`: regressão dos contextos
  isolados ajustados para o novo helper, preservando os bloqueios dos perfis padrão
  e demonstrativo.

As regressões HTTP incluem URLs reais com query string, parâmetros de versão
inválidos/ausentes, autenticação e CSRF. A cobertura unitária inclui autorização
negativa, autodecisão, justificativa não branca, decisões duplicadas, versão
esperada e preservação histórica. Essas regras não precisam ser repetidas em
todas as camadas. Reexecuções futuras exigem autorização e seleção explícita;
testes ignorados por falta de opt-in nunca contam como aprovados.

## Pré-requisito de banco: V4

`V4__add_acao_pdi_versao_removida_em.sql` adiciona somente
`public.acao_pdi_versao.removida_em TIMESTAMPTZ NULL`, sem valor padrão. A V4 foi
aplicada e validada em operação separadamente autorizada na branch `test-pdi-v2`,
database `pdi_db`, schema `public`, com sucesso e checksum **`-405865714`**.
V1–V4 foram conferidas; V1–V3 e seus scripts/checksums foram preservados.
Esse resultado não declara aplicação em outros ambientes. Os testes não aplicam
Flyway, DDL, migrations nem permissões SQL.

Os preflights de `PdiPersistenceIT`, `PdiServicePersistenceIT`,
`PdiSubmissaoPersistenceIT` e `UsuarioCredencialLocalPersistenceIT` agora exigem o
conjunto exato V1–V4, verificando scripts, checksums, ausência de duplicidade e sucesso.
Isso atualiza a compatibilidade dos testes, sem solicitar que todos sejam reexecutados.
V1–V3 permanecem inalteradas.

## Integração PostgreSQL consolidada

Foram aprovados nove cenários distintos: cinco de `PdiDecisaoPersistenceIT` e quatro
de `PdiSubmissaoPersistenceIT`. A primeira execução teve sete aprovados e duas
falhas. Após a correção do privilégio de leitura e da observabilidade, somente os
dois cenários afetados foram reexecutados e aprovados, sem ignorados. Não houve
repetição da bateria completa; as tentativas falhas não são contadas como novos
cenários aprovados.

Os cinco cenários de `PdiDecisaoPersistenceIT` são:

1. Aprovação da versão exata, promoção da vigente e execução não iniciada.
2. Rejeição, cópia para identidades independentes, edição, ressubmissão e aprovação
   da versão 2, preservando a rejeitada.
3. Remoção da última ação copiada, preservação da ação original e dos eventos,
   bloqueando submissão sem ações efetivas.
4. Falha real de CHECK ao persistir o evento de rejeição, marcando a transação para rollback.
5. Falha controlada ao copiar o conteúdo da ação, marcando a transação para rollback.

Os quatro cenários G7 verificam submissão e auditoria com rollback, bloqueio sem
ação, sem gestor e com gestor inativo. Os nove resultados cumulativos se apoiam
nos relatórios disponíveis e na confirmação da responsável pela execução inicial.
O XML atual da G8 contém somente os dois métodos reexecutados, pois a seleção
direcionada substituiu o relatório anterior dessa classe.

O teste reutiliza o runner protegido de `PdiSubmissaoPersistenceIT`, com opt-in
próprio: `TALENTSYNK_PDI_DECISAO_IT_ENABLED=true`, ausente por padrão. As configurações
externas usam o prefixo já existente `TALENTSYNK_PDI_SERVICE_IT_`:

- `HOST`, `DATABASE`, `USER`, `PASSWORD`, `SCHEMA`.
- `PROJECT_ID`, `BRANCH_ID`, `ENDPOINT_ID`, `NEON_API_KEY`.

Não registrar valores em arquivos versionados, argumentos, relatórios ou logs.
Antes do JDBC, a API Neon deve confirmar projeto, branch `test-pdi-v2`, endpoint e
host direto, branch não default e não protegida. O JDBC confirma `pdi_db`, `public`,
`current_user` e `session_user` iguais a `talentsynk_test_runner`, além do estado de
leitura do preflight. TLS permanece em `verify-full` com validação da JVM.
Existe nova checagem de identidade imediatamente antes da operação transacional.
Produção não é destino permitido.

O novo cenário de remoção exige o privilégio de edição já necessário em
`acao_pdi_versao`; o teste apenas verifica a permissão e falha se estiver ausente.
Não executa GRANT nem amplia privilégios.

Também é necessário SELECT efetivo em `public.pdi_componente` para
`talentsynk_test_runner`. O diagnóstico confirmou que a tabela existia e esse
privilégio estava ausente. Foi concedido exclusivamente SELECT nessa tabela na
branch `test-pdi-v2`, com verificação posterior pela conexão do usuário de testes
e preservação de V1–V4. Esse GRANT não integra a V4 e não deve ser transportado
automaticamente para outros ambientes. Os testes usam somente o usuário de
testes, nunca o papel de migração.

O harness preserva a etapa da primeira falha antes do cleanup, as classes da
cadeia de exceções, SQLState quando disponível e categoria técnica, como permissão,
SQL inválido, mapeamento ou integridade. O encapsulamento usa um resumo limitado
e sanitizado: não inclui mensagens SQL brutas, parâmetros, credenciais ou dados
de negócio. A exceção SQL original das duas falhas iniciais não foi preservada
pelos relatórios antigos; nenhum SQLState original é inferido retroativamente.

Todas as fixtures fictícias ficam em uma transação externa. O provider impede
`commit`, `setAutoCommit(true)` e acesso direto à conexão pelo Hibernate. O caminho
de encerramento sempre executa rollback; em seguida, consultas conferem a ausência
dos IDs capturados e dos registros pertencentes ao PDI de teste.
Sequências PostgreSQL podem avançar mesmo com rollback, como nos testes existentes.

Na reexecução dos dois métodos afetados, as verificações de ausência das novas
fixtures por IDs capturados e PDI passaram após cada rollback. Os marcadores
`G8_FIXTURE_ABSENCE_VERIFIED` para `REVISION` e `REMOVAL` estão no relatório atual.
Os IDs das fixtures das falhas anteriores não foram registrados; sua ausência
individual histórica não foi comprovada nem é inferida desses resultados.
Nenhum registro preexistente foi apagado para realizar essa validação.

Os cenários de falha verificam propagação ao interceptor transacional e rollback
integral das fixtures. Não simulam uma falha após baseline previamente commitado.
A revisão desta integração não comprova concorrência real entre conexões: esse
cenário exige fixtures compartilhadas previamente autorizadas. A ordem dos locks,
a revalidação do gestor e as transições inválidas foram cobertas por testes locais
e revisão técnica. As FKs e a unicidade da proposta aberta permanecem definidas na
V2 e são exercitadas por persistência válida; não há testes negativos específicos
que provoquem `23503` e `23505`. São limites documentados, sem defeito crítico
identificado ou exigência de nova bateria para o fechamento da G8.

Qualquer integração futura depende de autorização própria e seleção explícita;
o goal Maven `test` também compila os fontes. Não repetir automaticamente as
execuções já aprovadas.

## Regressão G7 e limites

Os quatro cenários de submissão da G7 foram aprovados na execução PostgreSQL
autorizada, preservando os preflights e o bloqueio a branches protegidas no runner
compartilhado. A validação na branch de testes não equivale a um teste negativo
contra produção ou outra branch protegida; esses ambientes não foram acessados.

`PdiServicePersistenceIT` contém um cenário com baseline commitado, condicionado à
flag adicional `TALENTSYNK_PDI_ACTION_EDIT_BASELINE_COMMIT_ENABLED`. Não selecionar
a classe inteira por conveniência e não habilitar essa flag nesta validação.

Estado de fechamento: código implementado, compilação aprovada, testes locais e
integração PostgreSQL aprovados no alcance descrito. A revisão técnica não
identificou defeitos críticos ou alterações fora do escopo. Commit, push e deploy
permanecem pendentes de autorização específica. A próxima entrega é a integração
essencial do front-end (G9); a validação ponta a ponta do produto e o beta seguem
nas entregas posteriores. O escopo do MVP permanece congelado.

## Inventário local dos arquivos da G8

Inventário conferido contra o Git: 36 arquivos, sendo 26 alterados (`M`) e dez
novos (`N`). Os caminhos abaixo são relativos à raiz do repositório; os prefixos
informados completam cada nome. As evidências de validação estão registradas
separadamente nas seções anteriores.

```text
main — prefixo src/main/java/com/empresa/pdi/
M controller/AcaoPDIController.java
M controller/PDIController.java
M controller/PdiExceptionHandler.java
N dto/PdiDecisaoRequest.java
N dto/PdiDecisaoResponse.java
N dto/PdiVersaoResponse.java
M model/AcaoPdiVersao.java
M repository/AcaoPdiVersaoRepository.java
M repository/PDIRepository.java
M repository/PdiVersaoRepository.java
M repository/UsuarioRepository.java
M security/HttpSecurityConfig.java
M security/LocalHttpSecurityConfig.java
M service/AcaoPdiService.java
N service/PdiAcessoService.java
M service/PdiService.java

tests — prefixo src/test/java/com/empresa/pdi/
M controller/AcaoPdiEdicaoHttpTest.java
M controller/CargoDefaultProfileHttpTest.java
M controller/CargoDemoProfileHttpTest.java
M controller/LocalAuthenticationHttpTest.java
N controller/PdiDecisaoHttpTest.java
M controller/PdiHttpTest.java
N integration/PdiDecisaoPersistenceIT.java
M integration/PdiMappingBootstrapTest.java
M integration/PdiPersistenceIT.java
M integration/PdiServicePersistenceIT.java
M integration/PdiSubmissaoPersistenceIT.java
M integration/UsuarioCredencialLocalPersistenceIT.java
M service/AcaoPdiServiceTest.java
N service/PdiAcessoServiceTest.java
N service/PdiDecisaoServiceTest.java
M service/PdiSubmissaoServiceTest.java

docs — prefixo docs/
M DATABASE.md
N G8_VALIDATION.md
M PROJECT_STATE.md

migration — prefixo src/main/resources/db/migration/
N V4__add_acao_pdi_versao_removida_em.sql
```
