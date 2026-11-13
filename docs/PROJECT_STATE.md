# TalentSynk — Estado técnico do projeto

Referência desta consolidação: Etapa 184G.112.
Atualização documental: Etapa 184G.126, com evidências das Etapas 184G.116–184G.125.
Atualização documental: Etapa 184G.148, com resultados pós-V3 confirmados em
11/11/2026 nas Etapas 184G.145 e 184G.147 e diagnóstico do ambiente SSL.
Atualização documental: Etapa 184G.155, com evidências das Etapas 184G.150,
184G.151, 184G.153 e 184G.154 sobre a persistência de credenciais locais da V3.

Este documento registra o estado técnico conhecido e o alcance das validações.
Não substitui as regras de negócio aprovadas, as migrations ou os contratos
específicos de segurança dos testes.

Nenhuma execução, alteração de banco ou publicação é autorizada por este documento.

## 1. Identificação e objetivo

O TalentSynk é um MVP de desenvolvimento profissional para portfólio GitHub,
com implementação incremental, arquitetura organizada e apresentação corporativa.

O recorte funcional atual do back-end concentra-se em:

- Autenticação e identidade interna.
- Criação, consulta e edição privada de rascunhos de PDI.
- Cadastro e consulta de ações desses rascunhos.
- Histórico das operações implementadas.
- Consulta autenticada ao catálogo de cargos ativos.

O ciclo completo de aprovação e execução do PDI ainda não está implementado.
A prontidão para um beta público não está comprovada.

### 1.1 Classificação de estado

| Estado | Significado |
|---|---|
| IMPLEMENTADO | Existe código para o comportamento descrito; isso não implica execução aprovada. |
| VALIDADO | Há resultado aprovado para o recorte e o ambiente explicitamente indicados. |
| PARCIAL | Existem partes implementadas, mas o módulo ou fluxo permanece incompleto. |
| PENDENTE | O fluxo ainda não foi implementado ou não possui a comprovação indicada. |

VALIDADO não significa aprovação integral do módulo, de todas as regras de
negócio ou de revisões posteriores do código.

### 1.2 Origem das evidências

- CÓDIGO: implementação e configuração inspecionadas.
- AUTOMATIZADA: resultado de execução anterior registrado na conversa.
- INTEGRADA: execução real informada pela usuária, com ambiente e alcance descritos.
- DOCUMENTAL: especificação ou contrato existente; não comprova implementação.
- DECISÃO: regra de negócio aprovada na conversa.

As execuções anteriores não foram repetidas na preparação deste documento.
Não há declaração de aprovação de uma bateria completa e atual do projeto.

## 2. Stack e ambientes

### 2.1 Stack declarada

| Camada | Tecnologia |
|---|---|
| Linguagem | Java 21 |
| Aplicação | Spring Boot 3.3.5 |
| HTTP | Spring MVC |
| Segurança | Spring Security |
| Persistência | Spring Data JPA e Hibernate |
| Banco | PostgreSQL, utilizando Neon nos ambientes informados |
| Migrations | Flyway 11.14.1 |
| Build | Maven, com Maven Wrapper disponível |
| Testes | JUnit, Mockito e MockMvc |

Fonte principal: `../pom.xml`.

A classificação funcional deste documento resulta exclusivamente da inspeção
do back-end Java/Spring Boot.

O front-end React/TypeScript não foi inspecionado nesta consolidação.
Portanto, suas telas, funcionalidades e integração não são classificadas como
implementadas, validadas ou pendentes com base nesta análise.

### 2.2 Perfis de aplicação

| Perfil | Comportamento implementado |
|---|---|
| Sem `demo-local` ou `auth-local` | Cadeia HTTP fechada; requisições negadas. |
| `demo-local` | HTTP Basic demonstrativo, configuração externa, validação de usuário ativo e CSRF preservado. |
| `auth-local` | Login por e-mail e senha, sessão HTTP, CSRF e consulta de identidade atual. HTTP Basic desabilitado. |

Os perfis `demo-local` e `auth-local` não podem ser ativados simultaneamente.
Sua combinação provoca falha explícita de inicialização.

Em `auth-local`:

- Endereço configurado: `127.0.0.1`.
- Porta configurada: `8081`.
- Flyway automático desabilitado.
- `spring.jpa.hibernate.ddl-auto=none`.
- Inicialização automática de scripts SQL desabilitada.
- Logs SQL e de parâmetros Hibernate desabilitados.
- Datasource obtido das variáveis externas `DB_URL`, `DB_USER` e `DB_PASSWORD`.
- O perfil não é ativado automaticamente pelas propriedades inspecionadas.

Essas opções não comprovam, isoladamente, a identidade da branch configurada
em uma futura execução.

### 2.3 Ambientes de banco conhecidos

| Ambiente | Evidência e limite |
|---|---|
| Neon `test-pdi-v2`, banco `pdi_db`, schema `public` | Ambiente informado para migrations, persistência controlada e consultas integradas de cargos. Não houve reconexão nesta consolidação. |
| Neon `test-core-validation` | Destino exclusivo previsto no contrato de `CoreSchemaValidationIT`; não confundir com o ambiente de PDI. |

A branch principal não é o ambiente autorizado para os testes descritos aqui.
Não registrar credenciais, endpoints sensíveis, cookies ou tokens neste documento.

### 2.4 Migrations existentes

| Migration | Estrutura |
|---|---|
| V1 — `V1__create_core_tables.sql` | `cargo`, `equipe`, `usuario`, `historico_equipe`. |
| V2 — `V2__create_pdi_initial_flow.sql` | `pdi`, `pdi_versao`, `acao_pdi`, `acao_pdi_versao`, `pdi_componente`, `pdi_componente_versao`, `entrega_submissao`, `pdi_evento`. |
| V3 — `V3__create_usuario_credencial_local.sql` | Credencial local opcional vinculada a `Usuario`. |

A aplicação e validação de V1, V2 e V3 em `test-pdi-v2` foram informadas no
histórico da conversa. O histórico remoto não foi consultado novamente.

A existência de uma tabela ou de seus estados não significa que o fluxo
funcional correspondente esteja implementado.

## 3. Estado atual dos módulos

| Módulo ou recorte | Estado | Evidência e alcance |
|---|---|---|
| CORE | PARCIAL | Entidades, repositories, serviços e testes de usuários, cargos, equipes e histórico. Gestão administrativa HTTP não está integralmente exposta ou autorizada. |
| Autenticação local | VALIDADO no recorte informado | Código, testes HTTP isolados e autenticações reais dos três perfis informadas pela usuária. |
| Autenticação demonstrativa | VALIDADO no recorte de regressão | HTTP Basic e fluxos existentes de PDI cobertos pelos testes anteriores. |
| Rascunhos privados de PDI | VALIDADO no recorte implementado | Criação, consulta e edição; contratos HTTP e cenários históricos de persistência aprovados. |
| Ações do PDI | PARCIAL | Cadastro e consulta em rascunho implementados e validados; demais operações ainda indisponíveis. |
| Histórico de PDI | PARCIAL | Registro transacional de criação, edição de rascunho e adição de ação; sem API de consulta de histórico. |
| Componentes e entregas | PARCIAL | Tabelas, enums e entidades JPA existem; fluxos de aplicação ainda não implementados. |
| Consulta de cargos ativos | VALIDADO com limitações | Código, 18 testes automatizados e consulta integrada informada. |
| Administração HTTP de cargos | PENDENTE | Não há endpoints de escrita liberados, inclusive para RH_ADMIN. |
| Submissão, aprovação e execução do PDI | PENDENTE | Estados e estrutura de persistência não equivalem a operações funcionais. |
| Reuniões | PARCIAL, legado bloqueado | Entidade, repository e consultas legadas; sem fluxo completo ou integração Teams. |
| OKRs, feedbacks, avaliações e notícias | PENDENTE como fluxos funcionais | Constam na especificação documental; não foram encontrados módulos funcionais correspondentes no inventário inspecionado. |
| Capacitações e competências | PENDENTE como fluxos funcionais | Requisitos documentados, sem implementação funcional correspondente no recorte atual. |
| Microsoft Entra ID / Active Directory | PENDENTE | Integração futura prevista; não há SSO implementado. |
| Matriz RBAC completa | PARCIAL | Authorities internas e autorizações pontuais existem; a matriz documental não está integralmente aplicada. |

A existência de métodos administrativos nos serviços CORE não representa
liberação HTTP nem comprova todas as permissões documentadas para RH ou gestores.

## 4. Funcionalidades implementadas e verificadas

### 4.1 Autenticação e identidade em auth-local

| Método | Endpoint | Contrato implementado |
|---|---|---|
| GET | `/api/csrf` | Token disponível antes do login, com `Cache-Control: no-store`. |
| POST | `/api/auth/login` | E-mail e senha; CSRF obrigatório; sucesso 204, falha genérica 401, sem redirecionamento HTML. |
| GET | `/api/auth/me` | Identidade autenticada atual: somente `id`, `nome`, `email` e `perfil`; resposta `no-store`. |
| POST | `/api/auth/logout` | CSRF obrigatório; invalidação de sessão; sucesso 204. |

O login utiliza o cadastro de `Usuario` e sua credencial local, com BCrypt e
encoder configurado com custo 12.

O UUID e as authorities são construídos no servidor. O cliente não escolhe
um perfil para obter permissões.

A sessão possui proteção contra session fixation. Os cookies são configurados
como HttpOnly e SameSite=Lax. A emissão efetiva de cookies pelo container e seu
comportamento em HTTPS não são integralmente comprovados pelo MockMvc.

`/api/auth/me` consulta os dados atuais do cadastro. Isso não atualiza
automaticamente as authorities já armazenadas na sessão.

Fontes: `LocalAuthenticationConfig`, `LocalUserDetailsService`,
`LocalHttpSecurityConfig`, `UsuarioPrincipal`, `AuthController` e
`IdentidadeAutenticadaService`.

### 4.2 Rascunhos privados de PDI

| Método | Endpoint | Comportamento |
|---|---|---|
| POST | `/api/pdis` | Cria PDI, versão inicial e evento; retorna 201, DTO e Location. |
| GET | `/api/pdis` | Lista rascunhos do titular autenticado. |
| GET | `/api/pdis/{id}` | Consulta rascunho próprio; inexistente ou de outro titular retorna 404. |
| PUT | `/api/pdis/{id}` | Edita dados básicos somente no rascunho inicial nunca submetido. |

Regras implementadas:

- Titular obtido pelo provider de identidade autenticada.
- Usuário deve existir e estar ativo.
- PDI criado em `NAO_INICIADO`.
- Versão inicial número 1, em `RASCUNHO`.
- Ausência de versão aprovada vigente na criação.
- Datas opcionais; intervalo invertido é rejeitado.
- Identidade, titularidade, versão e estados controlados pelo servidor.
- Criação e edição acompanhadas de evento na mesma transação.
- Edição preserva conteúdo anterior em `snapshot` e novo conteúdo em `dados`.
- Respostas por DTO, sem exposição direta das entidades ou snapshots internos.

Fontes: `PdiService`, `PDIController`, DTOs e repositories de PDI e versão.

### 4.3 Cadastro e consulta de ações

| Método | Endpoint | Comportamento |
|---|---|---|
| POST | `/api/pdis/{pdiId}/acoes` | Cria identidade e conteúdo versionado; retorna 201, DTO e Location. |
| GET | `/api/pdis/{pdiId}/acoes` | Lista ações da versão autorizada do rascunho. |
| GET | `/api/pdis/{pdiId}/acoes/{acaoId}` | Consulta ação pertencente ao PDI e à versão autorizados. |

O acesso exige titular autenticado e ativo. O cadastro está limitado à versão
inicial em RASCUNHO, nunca submetida.

A ação inicia em `NAO_INICIADA`. Vínculos, estado e ordem são definidos pelo
servidor. A implementação atual atribui ordem 0; não há reordenação funcional.

A criação registra `ACAO_ADICIONADA` na mesma transação e não gera uma nova
versão do PDI.

Não estão implementados edição, remoção, progresso, início ou conclusão de ações.

Fontes: `AcaoPdiService`, `AcaoPDIController` e repositories relacionados.

### 4.4 Consulta de cargos ativos

`GET /api/cargos` existe exclusivamente em `auth-local`.

A autorização HTTP permite:

- `COLABORADOR` → `ROLE_COLABORADOR`.
- `GESTOR` → `ROLE_GESTOR`.
- `RH_ADMIN` → `ROLE_RH_ADMIN`.

O fluxo:

1. Exige uma authority permitida.
2. Revalida a identidade e a situação ativa do usuário.
3. Consulta exclusivamente `CargoRepository.findByAtivoTrue()`.
4. Converte para `CargoResponse`.
5. Retorna somente `id`, `nome`, `descricao`, `nivel` e `ativo`.

O filtro de ativos é o mesmo para todos os perfis, inclusive RH_ADMIN.
A coleção vazia retorna HTTP 200 com `[]`.

Não estão liberados:

- POST, PUT, PATCH ou DELETE de cargos.
- Consulta individual `/api/cargos/{id}`.
- Consulta específica de inativos.
- Acesso ao catálogo pelos perfis de aplicação `demo-local` ou padrão.

O bloqueio dos dois últimos perfis de aplicação possui evidência estática,
e cobertura HTTP automatizada aprovada na Etapa 184G.125, com MockMvc e as
configurações reais de segurança. A validação integrada dedicada com servidor
real nesses perfis permanece pendente.

`CargoService.listar()` permanece distinto dessa consulta e utiliza `findAll()`.
O endpoint novo utiliza `CargoConsultaService`, não esse método CORE.

Fontes: `CargoController`, `CargoConsultaService`, `CargoRepository`,
`CargoResponse` e `LocalHttpSecurityConfig`.

## 5. Validações automatizadas e integradas

### 5.1 Resultados automatizados registrados

Os resultados abaixo correspondem a execuções anteriores, nos recortes e
revisões avaliados em suas respectivas etapas. Não representam uma nova
execução, uma execução conjunta de todas as classes ou aprovação de
modificações posteriores.

Os resultados históricos de PDI, autenticação, identidade e persistência
permanecem registrados com esse alcance. A existência atual dos testes não
comprova que revisões posteriores tenham sido executadas ou aprovadas.

Permanece confirmada especificamente a aprovação dos 18 testes de cargos
na Etapa 184G.95: seis unitários de CargoConsultaServiceTest e 12 HTTP de
CargoHttpTest, com mocks e sem conexão ao Neon.

| Testes | Resultado registrado | Alcance |
|---|---|---|
| `CargoConsultaServiceTest` e `CargoHttpTest` | 18 aprovados: 6 unitários e 12 HTTP | Consulta autenticada, três perfis, DTO, ativos, revalidação de usuário, erros e bloqueio de operações. Repositories mockados; sem Neon. |
| `CargoDemoProfileHttpTest` e `CargoDefaultProfileHttpTest` | Etapa 184G.125: 4 executados, 4 aprovados, 0 falhas, 0 erros e 0 ignorados; 2 aprovados por classe | Bloqueio de GET `/api/cargos` em `demo-local` e no perfil padrão, com MockMvc e configurações reais de segurança; sem servidor HTTP, Neon ou banco real. |
| `PdiHttpTest` | 38 aprovados | Regressão demo-local: Basic, CSRF, privacidade, criação/edição de rascunhos, cadastro/consulta de ações e rotas bloqueadas. |
| `LocalAuthenticationHttpTest` | 28 aprovados | Login, BCrypt, sessão, CSRF, logout, Basic desabilitado, identidade atual e isolamento de PDIs, com repositories mockados. |
| Testes do provider e de `UsuarioPrincipal` | 27 casos aprovados | Identidade interna, principals aceitos, authorities e proteção das credenciais. |
| `LocalAuthenticationConfigTest` | 5 aprovados | Isolamento dos componentes e rejeição de perfis incompatíveis. |
| `PdiServicePersistenceIT` | 6 cenários aprovados na Etapa 155 | Criação e edição de rascunhos, adição de ações e rollback nas falhas de evento. |

Na Etapa 184G.125, o Maven Wrapper offline utilizou Microsoft OpenJDK 21.0.11
e terminou com `BUILD SUCCESS`, executando exclusivamente:

```powershell
.\mvnw.cmd -o "-Dtest=CargoDemoProfileHttpTest,CargoDefaultProfileHttpTest" test
```

Os dois testes de `demo-local` e os dois do perfil padrão utilizaram contextos
MockMvc com as configurações reais de segurança, sem iniciar servidor HTTP
ou acessar o Neon ou qualquer banco real. Essa execução é independente dos
18 testes de cargos aprovados na Etapa 184G.95 e das demais execuções históricas;
os resultados não constituem uma execução conjunta da suíte.

Outros testes CORE e de contratos existem no repositório. Sua existência,
isoladamente, não permite registrar aprovação atual.

### 5.2 Bootstrap e persistência de PDI

A validação isolada dos mapeamentos V2 foi concluída em execução anterior
registrada no histórico da conversa. Essa aprovação corresponde ao recorte
e à revisão então avaliados, sem atribuição automática a alterações posteriores.

O código atual de `PdiMappingBootstrapTest` registra explicitamente onze
entidades: as oito da V2, `Usuario`, `Cargo` e `Equipe`.

Esse teste verifica metadados, associações, FKs compostas e construção da
SessionFactory, com provedor que rejeita conexões JDBC. Não comprova
persistência no PostgreSQL.

`PdiPersistenceIT` possui aprovação anterior informada. Tanto ele quanto
`PdiServicePersistenceIT` receberam posteriormente expectativas exatas para
V1, V2 e V3, incluindo scripts, checksums, sucesso e ausência de duplicidades.

Na consolidação anterior, ainda não havia confirmação de nova execução dessas
revisões após a adequação para V3. Em 11/11/2026, foram confirmados os seguintes
resultados pós-V3, em duas execuções distintas:

| Etapa | Teste | Resultado confirmado | Ambiente e alcance |
|---|---|---|---|
| 184G.145 | `PdiPersistenceIT` | Um cenário aprovado, zero falhas e zero erros | Neon, branch `test-pdi-v2`, banco `pdi_db`; verificação do histórico Flyway pós-V3 e rollback verificado conforme as asserções do teste. |
| 184G.147 | `PdiServicePersistenceIT` | Seis cenários aprovados, zero falhas e zero erros | Execução controlada no Neon; criação, edição e cadastro de ações, incluindo cenários negativos, com rollback previsto. |

Na Etapa 184G.147, foi confirmada a classe
`com.empresa.pdi.integration.PdiServicePersistenceIT`, executada pela configuração
IntelliJ `TalentSynk - PDI Service Persistence Controlado`.

As flags `TALENTSYNK_PDI_PERSISTENCE_ENABLED` e
`TALENTSYNK_PDI_SERVICE_PERSISTENCE_ENABLED` foram desativadas após as respectivas
execuções.

São sete cenários pós-V3 em duas execuções distintas. Os seis cenários do serviço
correspondem à reexecução dos mesmos casos registrados historicamente na Etapa
155, sem contabilizá-los como casos adicionais ou como execução conjunta de toda
a suíte. As aprovações não se estendem automaticamente a revisões posteriores.

A verificação do histórico V1–V3 por essas classes não valida especificamente a
estrutura física nem a persistência de `usuario_credencial_local`.
A validação dedicada dessa persistência foi posteriormente confirmada na
Etapa 184G.153, com a nova classe descrita na seção 5.6.

Os testes de persistência controlada possuem habilitação explícita,
verificações de destino e transações revertidas. Não executam migrations
como parte de seus cenários.

O rollback dos testes não equivale a comprovar commit durável de uma operação
real realizada pela API.

### 5.3 Consulta integrada de cargos no Neon

As verificações integradas de autenticação e consulta de cargos ocorreram
nas Etapas 184G.97–184G.110, conforme os resultados confirmados pela usuária.

As Etapas 184G.111–184G.113 correspondem à preparação e revisão documental,
sem novas execuções integradas.

Ambiente das verificações informadas:

- Branch: `test-pdi-v2`.
- Banco: `pdi_db`.
- Três perfis reais de teste autenticados.
- Consulta real ao catálogo.

| Perfil | Login | GET `/api/cargos` | Resultado informado |
|---|---|---|---|
| COLABORADOR | 204 | 200 | Três cargos, todos com `ativo=true`. |
| GESTOR | 204 | 200 | Três cargos, todos com `ativo=true`. |
| RH_ADMIN | 204 | 200 | Três cargos, todos com `ativo=true`. |

Verificações adicionais informadas:

| Cenário | Resultado |
|---|---|
| Consulta anônima ao catálogo | HTTP 401. |
| Consulta individual com Colaborador autenticado | HTTP 403. |

Na validação relatada, o banco continha três cargos ativos e nenhum inativo.
Portanto, foi comprovada a consulta de ativos, mas não a exclusão de registros
inativos em um conjunto real misto.

### 5.4 Auditoria estática de cargos

A Etapa 184G.109 não identificou inconformidades no recorte auditado.

Foram inspecionados:

- Autenticação e autorização por authority.
- Ordem dos matchers e fechamento com `denyAll()`.
- Ausência de handlers HTTP de escrita.
- Ausência de exposição alternativa identificada.
- Revalidação do usuário antes da consulta.
- Filtro de ativos, DTO e isolamento por perfil.
- Correspondência entre implementação e testes.

Essa conclusão é estática e não substitui execução integrada.

### 5.5 Validação CORE

O estado de CoreSchemaValidationIT deve ser identificado por revisão.
Execuções anteriores e seus resultados pertencem ao histórico das respectivas
revisões e não comprovam a aprovação de alterações posteriores.

CORE_SCHEMA_VALIDATION.md descreve uma revisão posterior ainda não compilada
nem executada, informando também que a versão anterior foi compilada.
Esse estado refere-se à revisão documentada; não significa que o teste
CORE nunca tenha sido executado.

Não se atribui aprovação à revisão documentada sem evidência de sua execução.

Seu contrato específico utiliza a branch test-core-validation e uma conta
dedicada, com aprovação administrativa e controles próprios.

Os resultados de PDI ou de cargos obtidos em test-pdi-v2 não comprovam a
aprovação desse contrato. Fixtures CORE utilizadas por outros testes também
não equivalem à validação integral de CoreSchemaValidationIT.

### 5.6 Persistência de credenciais locais da V3

A classe `UsuarioCredencialLocalPersistenceIT` foi criada na Etapa 184G.150.
Sua compilação local com Java 21 foi confirmada na Etapa 184G.151; essa
compilação não equivale à execução dos cenários.

Na Etapa 184G.153, foram confirmados cinco testes de integração aprovados no
PostgreSQL Neon, exclusivamente na branch `test-pdi-v2`:

- Persistência JPA com `@MapsId`, UUID compartilhado com `Usuario` e integridade
  do relacionamento, incluindo usuário inicialmente sem credencial.
- Preservação e atualização do hash fictício, preenchimento dos timestamps,
  preservação de `criada_em` e atualização de `atualizada_em`.
- Rejeição de PK duplicada, FK para usuário inexistente, `senha_hash` nulo e
  comprimento inválido do hash, cobrindo PK, FK, NOT NULL e CHECK da V3.

Os cenários utilizam transações com rollback e verificações de ausência das
fixtures conforme implementadas no teste. Essa execução é distinta dos testes
de PDI das Etapas 184G.145 e 184G.147 e dos testes HTTP de autenticação; não
constitui execução conjunta da suíte nem comprova commit durável pela API.

Não houve alteração nas migrations V1–V3 ou no código de produção nesse
recorte de criação, compilação e validação do teste dedicado. Esses resultados
não significam implementação de autenticação corporativa ou SSO.

## 6. Decisões técnicas e de negócio relevantes

### 6.1 Persistência e versionamento

- CORE preservado com identificadores UUID.
- Identificadores do módulo PDI mapeados como Long conforme a V2.
- Separação entre identidade, conteúdo versionado e execução.
- Rascunho não é promovido a versão aprovada vigente.
- FKs compostas preservam pertencimento entre identidades e versões.
- Colunas compartilhadas possuem responsáveis definidos pela escrita no ORM.
- `PDI.lock_version` utiliza `@Version`.
- Edição e cadastro de ações coordenam bloqueios na ordem
  `Usuario → PDI → PdiVersao`.

Esses mecanismos não constituem comprovação de todos os cenários concorrentes.

### 6.2 Histórico

Os fluxos implementados registram:

- `CRIACAO`.
- `EDICAO_RASCUNHO`.
- `ACAO_ADICIONADA`.

`PdiEvento` utiliza JSONB, `@Immutable`, campos não atualizáveis pelo ORM e
cópias defensivas dos JSONs.

Essas proteções não impedem, por si sós, exclusão física ou alteração por SQL
direto. Não existe API de consulta de eventos ou snapshots liberada.

### 6.3 Identidade e credenciais

A credencial local é uma associação opcional 1:1 com `Usuario`, por UUID e
`@MapsId`. A ausência do registro permite existir usuário sem senha local.

Os perfis vêm do cadastro interno. Credenciais da aplicação não são
credenciais de conexão PostgreSQL.

A existência de `adObjectId` e a preparação do principal interno não significam
integração Microsoft Entra ID, sincronização de grupos ou SSO implementados.

### 6.4 Privacidade e autorização

Permanecem vigentes as decisões da Etapa 21B:

- Rascunhos nunca submetidos são privados do titular.
- Conteúdo novo de revisões ainda não submetidas também permanece privado.
- RH_ADMIN não concede acesso automático a esse conteúdo.
- A condição de gestor direto depende do vínculo hierárquico vigente.
- Authorities técnicas não concedem todas as permissões previstas na matriz.
- Progresso e conclusão formal são conceitos distintos.
- A conclusão formal não deve ser presumida como automática.

Os fluxos ainda pendentes devem preservar essas decisões. Este documento
não reabre nem redefine permissões de negócio.

## 7. Limitações, pendências e riscos

### 7.1 Catálogo de cargos

- A exclusão efetiva de cargos inativos ainda não foi demonstrada com dados reais.
- Os testes automatizados usam mocks e não comprovam o SQL produzido pelo Hibernate.
- O bloqueio em demo-local e no perfil padrão possui cobertura HTTP automatizada
  aprovada na Etapa 184G.125; a validação integrada dedicada com servidor real
  nesses perfis permanece pendente.
- Não há teste específico do catálogo com authority ausente ou não permitida.
- A liberação de escrita administrativa não faz parte do recorte implementado.

### 7.2 PDI e demais módulos

- O ciclo completo de submissão, aprovação, execução e conclusão está pendente.
- Componentes e entregas possuem base de persistência, mas não fluxo funcional.
- Revisões, cancelamento e demais regras avançadas aprovadas permanecem
  preservados como requisitos, sem implementação presumida.
- Não há API de consulta de histórico.
- A matriz RBAC completa não está integralmente implementada.
- Reuniões possuem código legado bloqueado; V1–V3 não criam a tabela correspondente.
- Não há evidência suficiente para classificar os demais módulos documentados
  como concluídos.

### 7.3 Validação e ambiente

- A execução anterior dos testes de isolamento falhou em `testCompile`, antes
  dos testes, porque o Maven utilizava Java 8, incompatível com os recursos
  Java 21 dos fontes de teste. A configuração temporária de `JAVA_HOME` e `PATH`
  no PowerShell selecionou Microsoft OpenJDK 21.0.11; a compilação e os quatro
  testes passaram na Etapa 184G.125. Nenhum arquivo Java precisou ser corrigido
  e a configuração de Java 21 não foi aplicada permanentemente ao Windows.
- A primeira execução de `PdiPersistenceIT`, diagnosticada na Etapa 184G.135,
  falhou por SSL na comunicação HTTPS com a API Neon, antes da abertura do JDBC.
  Na rede corporativa sem VPN, curl apresentou `0x80092012`. Após conectar à VPN,
  curl e Java 21 estabeleceram conexão HTTPS com validação TLS, e os testes de
  persistência das Etapas 184G.145 e 184G.147 foram aprovados com a VPN conectada.
  A causa exata da diferença de comportamento da rede não foi comprovada.
  Não houve necessidade de desabilitar SSL ou modificar certificados.
- Resultados históricos não certificam toda revisão posterior do código.
- Não há comprovação apresentada de execução completa e atual de toda a suíte.
- Testes com rollback não comprovam commit durável pela API.
- Sequências PostgreSQL podem avançar apesar do rollback; os valores consumidos
  não são restaurados, podendo deixar lacunas nos identificadores.
- A configuração externa efetiva de cada execução não foi inspecionada nesta etapa.
- TLS, permissões e identidade do destino precisam continuar sendo verificados
  nos procedimentos autorizados; não devem ser inferidos apenas pelo nome do perfil.
- Na Etapa 184G.154, foi confirmada a revogação das seis permissões temporárias
  de INSERT/UPDATE em `usuario_credencial_local`, preservando SELECT. Uma futura
  reexecução de `UsuarioCredencialLocalPersistenceIT` depende de nova autorização
  e dos privilégios específicos exigidos pelo preflight; SELECT isolado não
  permite executar os cenários de escrita.
- Publicação pública, configuração HTTPS, integração atual do front-end e
  operação com usuários convidados não estão certificadas por esta consolidação.

## 8. Próximas etapas propostas

As atividades abaixo dependem de autorização específica:

1. Manter este documento atualizado após cada incremento funcional validado, registrando evidências, limitações e decisões relevantes.
2. Completar as evidências delimitadas do catálogo:
   exclusão de inativos em dados reais e validação integrada com servidor real
   do bloqueio em demo-local e no perfil padrão.
3. Registrar futuras reexecuções dos testes de persistência após novas revisões,
   mantendo distintos os resultados pós-V3 das Etapas 184G.145 e 184G.147 e as
   aprovações históricas, além da validação dedicada de credenciais locais da
   Etapa 184G.153.
4. Preparar o próximo incremento funcional pequeno do PDI, reutilizando a
   estrutura existente. Componentes e entregas são uma base ainda sem fluxo.
5. Tratar a prontidão para beta público separadamente da demonstração local.

Não iniciar implementação simultânea de todos os módulos documentados.
Não reabrir decisões de negócio encerradas nem ampliar permissões implicitamente.

## 9. Histórico de atualizações e evidências

As referências abaixo identificam marcos conhecidos, sem atribuir datas de
execução não fornecidas.

| Referência | Registro |
|---|---|
| Etapa 21B | Decisões de negócio aprovadas para privacidade, versionamento, execução, conclusão e histórico de PDI. |
| Etapas 151–155 | Cadastro/consulta de ações; registro de 38 testes HTTP e seis cenários de persistência aprovados. |
| Etapas 184F.4–184F.5 | Registro de 28 testes HTTP de autenticação/identidade e 38 testes de regressão demo-local aprovados. |
| Etapa 184G.95 | Registro de 18 testes de cargos aprovados: seis unitários e 12 HTTP. |
| Etapa 184G.109 | Auditoria estática de segurança do catálogo sem inconformidades identificadas. |
| Etapas 184G.97–184G.110 | Verificações integradas de autenticação e consulta de cargos nos três perfis; resultados confirmados pela usuária e limitação do conjunto sem cargos inativos. |
| Etapas 184G.111–184G.114 | Preparação e revisão da proposta documental e criação controlada de PROJECT_STATE.md; sem novas execuções de testes, builds, aplicação ou banco. |
| Etapa 184G.125 | Maven Wrapper offline com Microsoft OpenJDK 21.0.11: quatro testes de isolamento aprovados, dois em demo-local e dois no perfil padrão; zero falhas, erros ou ignorados; BUILD SUCCESS. Correção temporária de JAVA_HOME e PATH após falha anterior em testCompile com Java 8, sem alterações nos fontes Java ou na configuração permanente do Windows. Contextos MockMvc, sem servidor HTTP, Neon ou banco real. |
| Etapa 184G.126 | Atualização documental das evidências das Etapas 184G.116–184G.125, preservando o histórico e as limitações; sem novas execuções de testes, builds, aplicação ou banco. |
| Etapa 184G.145 | 11/11/2026: execução real pós-V3 de PdiPersistenceIT no Neon, branch test-pdi-v2, banco pdi_db; um cenário aprovado, zero falhas e zero erros; histórico Flyway conferido e rollback verificado pelas asserções; flag desativada após o teste. |
| Etapa 184G.147 | 11/11/2026: execução controlada pós-V3 de PdiServicePersistenceIT no Neon; seis cenários aprovados, zero falhas e zero erros, com rollback previsto; criação, edição e cadastro de ações, incluindo cenários negativos; flag desativada após o teste. Execução distinta da Etapa 184G.145 e reexecução dos casos históricos do serviço. |
| Etapa 184G.148 | Atualização documental dos resultados pós-V3 e do diagnóstico SSL/rede: HTTPS validado e persistência aprovada com VPN, sem causa exata comprovada para a diferença de rede e sem desabilitar SSL ou modificar certificados. Nenhuma nova execução de testes, builds, aplicação ou banco nesta atualização. |
| Etapa 184G.150 | Criação de UsuarioCredencialLocalPersistenceIT para validação dedicada da persistência da V3, sem alterações nas migrations V1–V3 ou no código de produção. |
| Etapa 184G.151 | Compilação local do novo teste confirmada com Java 21, distinta da execução dos cenários de integração. |
| Etapa 184G.153 | Cinco testes de integração de UsuarioCredencialLocalPersistenceIT aprovados no Neon, branch test-pdi-v2; cobertura de @MapsId, UUID compartilhado, hash fictício, timestamps, PK, FK, NOT NULL e CHECK, com rollback e verificações de ausência conforme implementadas. Execução distinta dos resultados históricos de PDI e autenticação. |
| Etapa 184G.154 | Revogação confirmada das seis permissões temporárias de INSERT/UPDATE em usuario_credencial_local, com SELECT preservado. |
| Etapa 184G.155 | Encerramento documental da validação dedicada da V3, preservando o histórico e as limitações; sem novas execuções de testes, builds, aplicação ou banco. |

### Referências locais

- [Especificação documental](DATABASE.md).
- [Contrato de validação CORE](CORE_SCHEMA_VALIDATION.md).
- [Dependências e build](../pom.xml).
- [Migrations](../src/main/resources/db/migration/).
- [Segurança HTTP local](../src/main/java/com/empresa/pdi/security/LocalHttpSecurityConfig.java).
- [Serviço de PDI](../src/main/java/com/empresa/pdi/service/PdiService.java).
- [Serviço de ações](../src/main/java/com/empresa/pdi/service/AcaoPdiService.java).
- [Consulta de cargos](../src/main/java/com/empresa/pdi/service/CargoConsultaService.java).
- [Testes HTTP de cargos](../src/test/java/com/empresa/pdi/controller/CargoHttpTest.java).
- [Testes unitários de consulta](../src/test/java/com/empresa/pdi/service/CargoConsultaServiceTest.java).
- [Persistência controlada do serviço](../src/test/java/com/empresa/pdi/integration/PdiServicePersistenceIT.java).
- [Persistência de credenciais locais da V3](../src/test/java/com/empresa/pdi/integration/UsuarioCredencialLocalPersistenceIT.java).
