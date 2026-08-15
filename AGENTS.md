# graphql-extension-websites

Jahia OSGi module that extends the GraphQL admin API with site lifecycle operations: create, delete, export, import, and bulk export-to-S3 of Jahia websites. No admin UI — pure GraphQL extension.

## Key Facts

- **artifactId**: `graphql-extension-websites` | **version**: read it from `pom.xml` — the only
  source of truth. Do not pin it here; a hardcoded version rots silently and misleads every
  assistant that `CLAUDE.md` points at this file
- **Java package**: `org.jahia.community.graphql.provider.dxm.extensions.websites`
- **jahia-depends**: `default,graphql-dxm-provider`
- **No frontend**, no admin UI
- Depends on **AWS SDK v2** (`software.amazon.awssdk`) for S3 uploads

## Architecture

| Class | Role |
|-------|------|
| `DXGraphQLExtensionWebsitesProvider` | Registers mutations with the DXM GraphQL provider |
| `WebsitesMutation` | `@GraphQLTypeExtension(GqlJahiaAdminMutation.class)` — exposes the `websites` namespace container |
| `WebsitesAdminMutation` | Holds all site operations (create/delete/export/import/exportAllSites); returned by `WebsitesMutation.websites()` |
| `GraphQLWebsitesConfig` | `@Component` OSGi service; holds AWS S3 credentials/config |
| `ImportInfo` | Value object used during import orchestration |

## GraphQL API

All mutations live under the `websites` namespace container on `GqlJahiaAdminMutation`, i.e. the GraphQL path is `admin.jahia.websites.<operation>` (a flat `admin.jahia.<operation>` path does NOT resolve). Every mutation carries a `@GraphQLRequiresPermission` annotation, but **not a distinct one each**: only `createSiteByKey` and `exportAllSites` have a dedicated permission; `exportWebsite`, `deleteSiteByKey` and `importWebsite` all share `websitesAdmin` — see the permission model table below. `importWebsite` and `exportAllSites` additionally require full server-administrator rights (`admin` at the repository root), and `deleteSiteByKey` / `exportWebsite` additionally require `websitesDelete` / `websitesExport` on the target site (all SEC-136).

| Mutation | Signature | Notes |
|----------|-----------|-------|
| `createSiteByKey` | `(siteKey, serverName, serverNameAliasesAsString, title, templateSet, modulesToDeploy, locale)` → Boolean | Uses `SiteCreationInfo`. `modulesToDeploy` is `[String]` on the wire and **must stay a `List<String>` in Java** — see Gotchas |
| `deleteSiteByKey` | `(siteKey)` → Boolean | `false` = not found **or** not authorized **or** `JahiaException` |
| `exportWebsite` | `(siteKey, exportPath, onlyStaging)` → Boolean | `@GraphQLAsync`; `exportPath` is relative to `{jahiaVarDiskPath}/exports/` and must stay inside it (`PathSecurity.resolveContained`) |
| `importWebsite` | `(importPath, siteKey)` → Boolean | Path relative to `jahiaImportsDiskPath`; reads `export.properties` |
| `exportAllSites` | `()` → `ExportAllSitesResults` | Exports to `{jahiaVarDiskPath}/exports/export-<yyyyMMddHHmmss>-<8 hex>.zip`, then uploads to S3 under that same name as the object key |

**Every `Boolean` mutation collapses distinct failures onto `false`** — path rejection,
authorization refusal, target not found and domain failure are indistinguishable to the caller,
and none of them raise a GraphQL error. That is deliberate (distinguishing them would leak site
existence), and it is a real integration constraint: callers can branch on success only, and the
reason lives in the server log. Do not "improve" this by returning typed errors from the
target-scoped mutations. Unchecked exceptions still propagate rather than becoming `false`.

`ExportAllSitesResults` enum: `SUCCESS`, `AWS_S3_BUCKET_NOT_CONFIGURED`, `NOT_SERVER_ADMINISTRATOR`. `exportAllSites` throws `DataFetchingException` on unexpected error rather than returning a failure value — this two-channel split is **deliberate**: the enum carries expected, operator-actionable outcomes; exceptions carry unexpected failures whose cause must survive for diagnosis. `ExportAllSitesResults` is an actionable-outcome enum, not a general error enum — only add a constant for something an operator can remedy.

## S3 Upload Configuration

`GraphQLWebsitesConfig` is a `ManagedService` on **OSGi PID `org.jahia.community.graphql.websites`**, backed by `<jahia-var>/karaf/etc/org.jahia.community.graphql.websites.cfg`. Property keys → getters:

| Property key | Getter |
|---|---|
| `aws.s3.region` | `getAwsS3Region()` |
| `aws.s3.bucketName` | `getAwsS3BucketName()` |
| `aws.s3.accessKey` | `getAwsS3AccessKey()` |
| `aws.s3.secretAccessKey` | `getAwsS3SecretAccessKey()` |

The module ships `src/main/resources/META-INF/configurations/org.jahia.community.graphql.websites.cfg` with all four values blank; it starts with `# default configuration - won't be overridden`, so Jahia's extender will not clobber a customised deployed file. See README §Configuration for the operator-facing version.

**Credentials must NEVER be passed inline in a GraphQL mutation** — only via this OSGi configuration. Inline values leak into GraphQL access logs and audit trails. Do not add credential arguments to `exportAllSites`.

If any field is blank, `exportAllSites` returns `AWS_S3_BUCKET_NOT_CONFIGURED` **before exporting anything** — the precondition is checked first (and after the server-administrator gate), so an unconfigured instance does no export work at all. (It used to export every site and then delete the archive unread.)  
The exported ZIP is always deleted from disk after upload (or on failure), via the `deleteExportArtifact` wrapper: it skips a file that was never created and **logs a warning** when a file that does exist cannot be removed. It replaced a bare `FileUtils.deleteQuietly`, which discarded that failure silently and let stale archives accumulate — do not collapse it back.

## Build

```bash
mvn clean install
```

No frontend; no `yarn` commands needed.

## Tests (Cypress Docker)

```bash
cd tests
cp .env.example .env
yarn install
./ci.build.sh && ./ci.startup.sh
```

- Specs: `tests/cypress/e2e/01-…` (core mutations), `02-…Permissions` (RBAC + SEC-136 gates), `03-…Import` (import round trip)
- Covers: create site, delete site, export site, `exportAllSites` (expects `AWS_S3_BUCKET_NOT_CONFIGURED` in CI)
- Sites `cypress-test-website` and `cypress-roundtrip-site` are created and deleted within tests

### Import round trip

`exportWebsite` writes exactly the layout `importWebsite` reads (`export.properties`, `users/`, `roles/`, `<siteKey>/site.properties`), so spec 03 is a real round trip — create → export → stage → delete → import → assert the site is back — rather than a committed fixture that would rot against the exporter.

Cypress runs in its own container and cannot reach Jahia's filesystem, so moving the tree from `{jahiaVarDiskPath}/exports` to `{jahiaImportsDiskPath}` happens **server-side** via `cypress/fixtures/stageImportTree.groovy`, run through the provisioning API with `cy.executeGroovy`. That script also waits out the `@GraphQLAsync` export, which returns before the tree is on disk — do not assume the export exists the moment the mutation resolves.

## Privilege tiers (deliberate, do not "harmonise")

The five mutations use four different privilege scopes (`exportWebsite` and `deleteSiteByKey` share the target-scoped tier). This is by design — the rule is whether the operation *degrades safely* when denied rights:

| Mutation | Runs as | Why |
|---|---|---|
| `exportWebsite` | caller, **target-scoped** | Requires `websitesExport` **on the site node**, checked in the caller's own session (§4.3). Relying on the session read-bound alone would make security depend on ACLs lining up and would still write a misleading near-empty archive |
| `exportAllSites` | caller, **server admin only** | Instance-wide, so gated on `admin` at `/` (§4.3). A read-bounded bulk export yields a partial backup that looks complete — worse than a refusal. Returns `NOT_SERVER_ADMINISTRATOR` |
| `createSiteByKey` | **system session** | Load-bearing, **do not remove**. Writing `/sites/<siteKey>` needs rights on `/sites` that a delegated `websitesAdmin` holder lacks; a caller-scoped session would fail for exactly the non-admin users the permission exists to serve. The escalation *is* the delegation mechanism |
| `deleteSiteByKey` | caller, **target-scoped** | Requires `websitesDelete` **on the site node itself**, checked in the caller's own session (SEC-136). Deletion cannot degrade gracefully, so neither de-escalation nor a global second gate fits |
| `importWebsite` | system session **+ second gate** | Imports users and roles, which no de-escalation can bound, so it additionally requires full `admin` at the repository root (SEC-136) |

**The `@GraphQLRequiresPermission` annotation cannot express a per-target rule.** The provider
evaluates it as `session.getNode(path).hasPermission(perm)` with `path` defaulting to `/`
(`GqlJcrPermissionChecker`); the path is static while the target arrives as a runtime argument.
Any mutation acting on a *specific* site must therefore carry its own check in the method body.
The absence of one on `deleteSiteByKey` was SEC-136 — a delegated role holder could delete any
site on the instance.

### Permission model (do not "simplify")

Five permissions, all children of `admin` — but **not one per mutation**, and the difference
matters when delegating. `createSiteByKey` and `exportAllSites` each own their annotation
permission; `exportWebsite`, `deleteSiteByKey` and `importWebsite` **share** `websitesAdmin` and
are told apart only by their in-body gates. So granting `websitesAdmin` to delegate, say,
`importWebsite` also opens the annotation gate on the other two — at the annotation level those
three are **not** independently delegable, and only the in-body checks separate them:

| Mutation | Annotation (checked at `/`) | In-body gate |
|---|---|---|
| `createSiteByKey` | `websitesCreate` | — |
| `exportWebsite` | `websitesAdmin` | `websitesExport` **on the target site** |
| `exportAllSites` | `websitesExportAll` | `admin` at `/` |
| `deleteSiteByKey` | `websitesAdmin` | `websitesDelete` **on the target site** |
| `importWebsite` | `websitesAdmin` | `admin` at `/` |

**Never point a target-scoped mutation's annotation at its fine permission** — not
`deleteSiteByKey` → `websitesDelete`, not `exportWebsite` → `websitesExport`. Both read like
obvious consistency fixes and are the most dangerous edits in the file: the annotation is
evaluated at the repository root, where those permissions are deliberately never granted, so the
change denies every site-scoped holder before the body runs — while *looking* stricter.

Nor can you "fix" that by adding them to the root-granted server role: JCR permissions inherit
downward, so a root grant satisfies the in-body per-site check on every site and makes it
vacuous. Coarse annotation + fine in-body check is the only combination that works. Both are
pinned by `WebsitesAdminMutationPermissionAnnotationTest`.

The shape of the permission tree is equally load-bearing:

- `websitesDelete` is a **sibling** of `websitesAdmin`, never nested under it. Jahia registers
  nested permission nodes as **aggregated sub-privileges** (`JahiaPrivilegeRegistry` →
  `new PrivilegeImpl(..., subPrivileges, ...)`), so nesting would grant it to every holder of
  `websitesAdmin` — a permission split that changes nothing.
- `websitesDelete` **and `websitesExport`** are **absent from the root-granted server role**. JCR
  permissions inherit down the tree, so granting either at `/` would satisfy the per-site check
  everywhere and make it vacuous — a root `websitesDelete` means the holder can delete any site
  on the instance. Both ship instead on the site-scoped
  `graphql-extension-websites-site-administrator` role, which is granted per site.
- All five sit under `admin`, so server administrators aggregate them and keep full reach with no
  special case in the code. Do **not** add a `callerIsServerAdministrator()` fallback to
  `deleteSiteByKey`; it would be redundant and would mask a misconfigured permission tree.

`createSiteByKey`'s residual risk is bounded: the caller picks `templateSet` and `modulesToDeploy`, so they can enable any **already-installed** module on the new site — but cannot install modules (that needs the module manager) nor affect existing sites.

## Gotchas

- `exportWebsite` is `@GraphQLAsync` — the GraphQL response returns before the export completes; clients cannot poll for completion via this mutation
- `exportAllSites` requires **full server administrator** (`admin` at `/`) since 2.2.0. A `websitesAdmin` / `websitesExportAll` holder cannot invoke it **at all** — it returns `NOT_SERVER_ADMINISTRATOR`, checked before the S3 precondition so an unauthorized caller learns nothing about the configuration. **That gate is the security control**, not session de-escalation; do not describe the caller-session bound as what protects this mutation (it was, in 2.1.x, and the bound was vacuous because the server role then granted root-wide read). The export does still run under the caller's own session rather than escalating to root — a deliberate belt-and-braces, but for a server administrator it confines nothing
- `deleteSiteByKey` returns `false` for **domain** failures (`JahiaException`, site not found) and for an authorization refusal — the three are indistinguishable to the caller by design. Unchecked exceptions propagate to the GraphQL layer instead of being flattened into `false`, matching how `exportAllSites` raises `DataFetchingException`
- `callerMayActOnSite` fails closed on `PathNotFoundException`: a caller who cannot even see the site node is refused exactly like an unauthorized one, so "invisible" and "not authorized" return the same `false` and site existence does not leak
- Bulk export archives are named `export-<yyyyMMddHHmmss>-<8 hex>.zip`. The random suffix is required, not cosmetic: the name doubles as the S3 object key, and the previous minute-granular name let two concurrent exports collide on the same path and key
- `ImportInfo.asMap()` guards its whole body on `siteProperties != null`, and `importWebsite` never sets that field — so in production it always returns an **empty** map. Traced against Jahia 8.2 sources: `importSiteZip` only reads that map under `if (legacyImport)` (Jahia 5.x/6.1 archives), so it is **inert for modern 8.x imports** and deliberately left alone. Pinned by `ImportInfoTest` — read its javadoc before changing it
- **A GraphQL list argument must be a `List<T>` parameter in Java, never an array.** graphql-java-annotations only converts a list argument to the declared parameter type when that parameter is a parameterized `List<T>` (`MethodDataFetcher.buildArg` guards on `instanceof ParameterizedType && instanceof GraphQLList`); an array parameter is a plain `Class`, misses that branch, receives the raw `ArrayList` and fails reflective `Method.invoke`. `createSiteByKey`'s `modulesToDeploy` was `String[]` until 2.2.1, so supplying it — even as `[]` — failed with `IllegalArgumentException: argument type mismatch` for **every** caller including root, while omitting it worked. That argument-dependence is why it shipped broken in three releases: no test or example ever passed the argument. It is now `List<String>`, adapted by `toModulesArray()` which maps `null → null` so omitting it behaves as before; the wire type `[String]` is unchanged either way. `WebsitesAdminMutationArgumentTypeTest` fails if any `@GraphQLField` method declares an array parameter — do not "simplify" one back
- `importWebsite` expects a specific directory layout under `jahiaImportsDiskPath`: `{importPath}/export.properties`, `{importPath}/roles/`, `{importPath}/users/`, `{importPath}/{siteKey}/`
- Creating a site requires a valid template set; if the template set is not installed, `addSite` throws a `JahiaException` and the mutation returns `false`
