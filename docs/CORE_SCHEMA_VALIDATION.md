# Validação isolada CORE × PostgreSQL

Estado: revisão atual não compilada (a versão anterior foi compilada). Teste não executado e
nenhuma conexão aberta nesta etapa. Não há resultado de compatibilidade real ainda.

## Escopo e isolamento

`CoreSchemaValidationIT` usa JUnit e o Hibernate gerenciado pelo projeto, sem
Spring Boot, Spring Data, Flyway, scanning de pacotes ou `persistence.xml`.
Registra explicitamente `Usuario`, `Cargo`, `Equipe` e `HistoricoEquipe`, conferindo
o conjunto exato no metamodelo. Usa schema `public` e `hibernate.hbm2ddl.auto=validate`.
Não lê nem altera `application.properties`.

Antes de conectar e criar os serviços Hibernate, rejeita propriedades externas de
geração de esquema na JVM e recursos `hibernate.properties`/`hibernate.cfg.xml` no
classpath. Inspeciona também as configurações herdadas pelo builder e descarta
todas antes de aplicar uma lista local de opções. Configura as ações Hibernate/JPA
concordantemente: `validate`, `Action.VALIDATE` no banco e `Action.NONE` para scripts.
São os valores de Action aceitos pelo Hibernate atual, não uma nova unidade JPA.
Verifica as opções efetivas antes de criar o registry e novamente antes de construir
metadados/SessionFactory. Uma opção conflitante reprova o teste antes da validação;
propriedades externas detectadas reprovam antes do bootstrap de serviços.

O teste só contém SELECTs de catálogo e consultas de metadados JDBC. Não abre
Session/EntityManager, não lê registros das tabelas CORE e não chama
persist/merge/remove/flush. Não executa INSERT, UPDATE, DELETE ou DDL. O driver pode
usar comandos de controle de sessão/transação; isso não equivale a escrever dados.

## Contrato de segurança específico CORE — Opção B

Este contrato autoriza somente o comportamento revisado de
`CoreSchemaValidationIT`. Não declara a role universalmente incapaz de escrita
persistente. A aprovação depende do código, do ambiente e das restrições aqui
descritas; não é uma certificação geral da conta.

O único ambiente autorizado é a branch `test-core-validation`, banco `pdi_db`,
schema `public`, com conexão direta. Produção não está autorizada para este teste.
Um administrador deve provisionar ou revisar a conta dedicada `core_schema_validator`, separada da conta
de aplicação/migração, com CONNECT em `pdi_db`, USAGE em `public`, SELECT nas quatro
tabelas CORE e leitura dos catálogos usados pelo teste. Não há fallback para as
credenciais da aplicação. O teste não cria roles nem executa GRANT/REVOKE/ALTER.

As funções de consulta de privilégios consideram grants efetivos, herdados e de
PUBLIC. Cada conexão é verificada antes de ser entregue ao Hibernate. Além do
usuário atual, o teste considera roles alcançáveis por SET ROLE, sem executar SET
ROLE. Associações legítimas a roles com privilégios de leitura são permitidas.
Nos objetos CORE, exige-se ausência de privilégios diretos de escrita. Essa ausência
não substitui a verificação de privilégios efetivos, incluindo os herdados ou
concedidos por PUBLIC. Os controles existentes também rejeitam escrita efetiva nos
objetos persistentes de usuário. Essas verificações não serão flexibilizadas.

São rejeitados:

- SUPERUSER, CREATEDB, CREATEROLE, REPLICATION e BYPASSRLS no usuário ou em roles
  alcançáveis; ADMIN OPTION nas associações desses principals; acesso relevante a
  roles administrativas de arquivos, programas, sinalização, checkpoint ou Neon.
- CREATE no banco atual e em schemas persistentes de usuário, bem como ownership
  efetivo de objetos no banco atual, inclusive herdado. Ownership em outro banco
  não reprova automaticamente esta verificação.
- Escrita/manutenção efetiva em tabelas, colunas ou sequências persistentes de
  schemas de usuário no banco atual, inclusive através de roles alcançáveis.

TEMPORARY, por si só, não reprova o teste. Não há criação/uso de tabelas temporárias
pelo código. Não é necessário revogar esse privilégio de PUBLIC só para o teste.
A existência ou o EXECUTE de uma rotina SECURITY DEFINER também não reprova por si
só: o catálogo não demonstra, sozinho, que a rotina permite escrita ou elevação.

### Capacidades residuais e comportamento permitido

Rotinas nativas PostgreSQL, inclusive criadores de Large Objects, podem permitir
escrita persistente fora das tabelas CORE. Ausência de Large Objects existentes
ou de DML nas tabelas não elimina essa capacidade. EXECUTE isoladamente não
comprova autorização irrestrita para todas as rotinas administrativas.
A revisão deve classificar rotinas, extensões e demais caminhos indiretos,
registrando quais capacidades residuais existem e por que não são utilizadas
pelo fluxo autorizado.

O teste permite somente bootstrap Hibernate, consultas de catálogo/metadados e
validação de schema. Não pode chamar rotinas de Large Objects, funções de escrita
ou administração, rotinas da aplicação, persistência, migrações ou geração de DDL.
A aceitação das capacidades residuais não autoriza seu uso nem reduz controles.

### Aprovação administrativa explícita

Antes da execução, o administrador deve registrar a revisão do código e deste
contrato, o ambiente aprovado, os achados da auditoria, os riscos residuais aceitos
e a identidade do responsável pela aprovação. A aprovação vale para a revisão e
execução avaliadas; mudanças de código, dependências, endpoint, permissões ou
configuração exigem reavaliação.

A variável `CORE_SCHEMA_TEST_SECURITY_CONTRACT_APPROVED` fica ausente por padrão.
Somente após aprovação explícita deste contrato, e autorização da execução, o
operador poderá fornecer o valor literal `true` no processo local.
O teste nunca define essa variável. Ausência, vazio ou qualquer outro valor
bloqueiam antes da conexão, quando há opt-in.
`CORE_SCHEMA_TEST_READONLY_ACCOUNT_REVIEWED` foi substituída: seu valor não é
consultado, convertido ou aceito como aprovação do contrato novo.
A variável registra autorização administrativa; não verifica quem aprovou,
não comprova impossibilidade absoluta de escrita e não substitui controles técnicos.

### Estado transacional

As conexões recebem `default_transaction_read_only=on` no startup e
`readOnly=true`/`readOnlyMode=always`; o estado de leitura é conferido no servidor
antes de entregar cada conexão ao Hibernate. Não há uma transação explícita única
envolvendo todo o teste. `Connection.setReadOnly(true)` isoladamente não basta.
Esses controles protegem o fluxo, mas não tornam a role universalmente somente
leitura. Grants/roles não devem ser alterados durante o teste.
Não se tenta escrever para comprovar as restrições.

## Endpoint, TLS e variáveis locais

Definir somente no processo/configuração local da futura execução:

- `CORE_SCHEMA_TEST_DB_URL`: URL JDBC PostgreSQL do endpoint direto Neon, banco
  `pdi_db`, porta 5432, sem identidade/senha. Exige parâmetro literal
  `sslmode=verify-full`; aceita opcionalmente `channelBinding=require|prefer|disable`.
- `CORE_SCHEMA_TEST_DB_USER`: exatamente `core_schema_validator`, conta dedicada ao contrato CORE.
- `CORE_SCHEMA_TEST_DB_PASSWORD`: senha dessa conta, nunca passada em argumento `-D`.
- `CORE_SCHEMA_TEST_SECURITY_CONTRACT_APPROVED`: aprovação administrativa descrita acima; sem valor padrão.

O destino é vinculado ao SHA-256 do hostname direto aprovado, fixado no código em
`ENDPOINT_APROVADO_SHA256`, sem substituição por variável de ambiente. O hostname
é normalizado com trim e minúsculas (Locale.ROOT), codificado em UTF-8 e resumido
como 64 caracteres hexadecimais minúsculos. A URL usa hostname DNS sem ponto final.
`CORE_SCHEMA_TEST_EXPECTED_HOST` e `CORE_SCHEMA_TEST_ENDPOINT_CONFIRMED` não são
mais consultadas e não podem autorizar outro endpoint.

O SHA-256 fornecido e aprovado pelo usuário já está fixado na constante.
O valor permanece inalterado nesta adequação contratual. A comparação ocorre
antes de qualquer conexão; não há fallback nem override por variável de ambiente.
O identificador aprovado pode ser registrado no código; credenciais e URLs de
conexão não devem ser registradas.

Exige PostgreSQL major 18 e confirma `current_user` e `session_user` como
`core_schema_validator` em cada conexão. O vínculo entre hostname e branch exige conferência
humana no console Neon; nome do banco e certificado não demonstram o nome da branch.
O pin impede redirecionamento por configuração local, mas não prova automaticamente
o nome da branch. Mudança de endpoint exige nova conferência, autorização e revisão
da constante; não contornar a verificação. Endpoints
`-pooler` são rejeitados: este teste depende de configurações de sessão persistentes.

Nomes e valores dos parâmetros de URL são aceitos apenas na forma literal prevista;
parâmetros duplicados, codificados, desconhecidos, vazios ou ambíguos são rejeitados.
A URL original nunca é entregue ao driver: o teste reconstrói uma URL sem query e
passa as propriedades aprovadas separadamente. Fixa `sslmode=verify-full` e
`sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory` nas propriedades efetivas.
Não aceita overrides de fábrica TLS, verificador de hostname ou opções de sessão.

No pgJDBC 42.7.4, DefaultJavaSSLFactory usa o SSLContext/truststore padrão da JVM;
verify-full também exige que o certificado corresponda ao hostname. Não há fallback
para modo não verificante. Isso evita depender implicitamente do arquivo root.crt
usado pela LibPQFactory padrão do pgJDBC. O JDK 21 deve confiar na cadeia pública
apresentada pelo Neon. Um truststore personalizado aprovado pode ser selecionado
pela configuração JSSE da JVM; contém CAs, não credenciais PostgreSQL. Sua preparação
é local/administrativa e não foi realizada. Não colocar senhas de truststore em
argumentos visíveis, não usar NonValidatingFactory nem desabilitar validação.

O teste NÃO usa pg_stat_ssl como prova do TLS cliente-endpoint: essa visão descreve
o trecho visto pelo backend, que pode estar atrás do proxy Neon. A garantia de TLS
cliente-endpoint decorre do handshake do driver com as opções fixadas acima.

Nunca armazenar os valores em arquivos do projeto, argumentos Maven, logs,
capturas de tela ou configurações compartilhadas. O teste suprime loggers de
Hibernate/pgJDBC, redige o DataSource e não publica mensagens/causas originais.
Apresenta etapa, categoria, SQLSTATE e diagnósticos de schema selecionados a partir
de rótulos fixos, sem nomes arbitrários, URL ou credenciais. Falhas no encerramento
não substituem a falha principal. Não usar Maven -X, captura de variáveis por
debuggers/agentes, logging JDBC externo ou execução paralela.

## Execução futura — autorização ainda necessária

Pré-requisitos obrigatórios, sem provisionamento automático:

- Reconfirmar o vínculo manual do endpoint fixado com a branch `test-core-validation`.
- Confirmar nessa branch a presença da V1 e das quatro tabelas CORE em `public`.
  A migração bem-sucedida em outra branch não comprova esse estado.
- Confirmar a existência, credenciais e privilégios da role `core_schema_validator`
  nessa branch, sem escrita direta nos objetos CORE e sem falha nos controles existentes.
- Registrar a aprovação administrativa da Opção B e dos riscos residuais, conforme
  a seção de aprovação; não reutilizar a confirmação administrativa antiga.
- Permitir leitura dos catálogos `pg_roles`, `pg_auth_members`, `pg_shdepend`,
  `pg_database`, `pg_namespace` e `pg_class` e execução das funções de consulta de
  privilégios usadas pelo teste. Não conceder poderes administrativos para isso;
  se o acesso estiver restrito, interromper e revisar o diagnóstico.
- Disponibilizar JDK 21, dependências do projeto, acesso de rede ao endpoint direto
  na porta 5432 e cadeia TLS confiável na JVM. Não usar o pooler.
- Obter autorização explícita antes de compilar esta revisão ou executar o teste.

Não executar agora. Usar Java 21, variáveis no processo e a raiz do projeto.
Exemplo PowerShell corrigido, com os argumentos -D entre aspas:

```powershell
.\mvnw.cmd "-Dtest=com.empresa.pdi.integration.CoreSchemaValidationIT" "-Dtalentsynk.coreSchemaValidation=true" test
```

O sufixo IT fica fora dos padrões convencionais do Surefire. A seleção explícita
executa somente essa classe e a propriedade adicional habilita o teste. Sem opt-in,
o teste é desabilitado: isso não é aprovação. Com opt-in, variável ausente, falha de
conexão ou falta de pré-requisitos reprova o teste, sem assumption/skip.
O goal test compila fontes/testes antes de executar; não seleciona PdiApplicationTests.

Não é necessário mudar pom.xml: JUnit, Hibernate, PostgreSQL JDBC, SLF4J e Logback
já pertencem às dependências atuais. Não adicionar H2, Failsafe ou Testcontainers.

## Critérios e limitações

Aprovação administrativa: contrato e riscos residuais explicitamente revisados e
aceitos para a execução proposta. Isso não equivale à aprovação técnica do schema.
Aprovação técnica: exatamente um teste executado sem falhas/erros/skips,
pré-requisitos confirmados, somente quatro entidades e validação Hibernate concluída.

Bloquear a execução se faltar aprovação administrativa, se houver divergência de
identidade, banco, schema, endpoint ou TLS, estado transacional diferente do
esperado, configuração externa proibida, falta de acesso aos catálogos ou falha
nas verificações de privilégios. Incompatibilidade de metadados reprova o teste.
A aprovação administrativa nunca sobrepõe uma falha técnica.
Sem opt-in, o teste é desabilitado; skip não comprova aprovação.
Falha de permissão/TLS não demonstra incompatibilidade JPA.

Não valida integralmente índices, UNIQUE, FKs, CHECKs, defaults ou nulabilidade.
Não comprova persistência, callbacks, precisão temporal, transações, concorrência,
regras de negócio ou inicialização Spring/Spring Data. Não é sandbox contra agentes
maliciosos, dependências substituídas ou alterações concorrentes de privilégios.
A política de permissões ainda pode reprovar contas com poderes não necessários
à leitura: revisar o diagnóstico, sem ampliar grants nem corrigir automaticamente.

Referências técnicas:

- [pgJDBC: SSL](https://jdbc.postgresql.org/documentation/ssl/)
- [DefaultJavaSSLFactory 42.7.4](https://github.com/pgjdbc/pgjdbc/blob/REL42.7.4/pgjdbc/src/main/java/org/postgresql/ssl/DefaultJavaSSLFactory.java)
- [Hibernate 6.5.3: ações de schema](https://github.com/hibernate/hibernate-orm/blob/6.5.3/hibernate-core/src/main/java/org/hibernate/tool/schema/spi/SchemaManagementToolCoordinator.java)
- [PostgreSQL 18: consultas de privilégios](https://www.postgresql.org/docs/18/functions-info.html)

Nenhum resultado de compilação/execução existe para esta revisão. Qualquer alteração
fora destes dois arquivos ou operação no banco exige nova autorização.
