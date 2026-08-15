# graphql-extension-websites

Jahia OSGi module that extends the GraphQL admin API with site lifecycle operations: create, delete, export, import, and bulk export-to-S3 of Jahia websites. No admin UI — pure GraphQL extension.

## Key Facts

- **artifactId**: `graphql-extension-websites` | **version**: `1.1.2-SNAPSHOT`
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

All mutations live under the `websites` namespace container on `GqlJahiaAdminMutation`, i.e. the GraphQL path is `admin.jahia.websites.<operation>` (a flat `admin.jahia.<operation>` path does NOT resolve). Each mutation carries its **own** permission via `@GraphQLRequiresPermission` — see the permission model table below. `importWebsite` additionally requires full server-administrator rights (`admin` at the repository root) because it imports users and roles, and `deleteSiteByKey` additionally requires `websitesDelete` on the target site (both SEC-136).

| Mutation | Signature | Notes |
|----------|-----------|-------|
| `createSiteByKey` | `(siteKey, serverName, serverNameAliasesAsString, title, templateSet, modulesToDeploy[], locale)` → Boolean | Uses `SiteCreationInfo` |
| `deleteSiteByKey` | `(siteKey)` → Boolean | Returns `false` if site not found |
| `exportWebsite` | `(siteKey, exportPath, onlyStaging)` → Boolean | `@GraphQLAsync`; exports to `exportPath` on disk |
| `importWebsite` | `(importPath, siteKey)` → Boolean | Path relative to `jahiaImportsDiskPath`; reads `export.properties` |
| `exportAllSites` | `()` → `ExportAllSitesResults` | Exports to `jahiaVarDiskPath/exports/export-{timestamp}.zip`, then uploads to S3 |

`ExportAllSitesResults` enum: `SUCCESS`, `AWS_S3_BUCKET_NOT_CONFIGURED`, `NOT_SERVER_ADMINISTRATOR`. `exportAllSites` throws `DataFetchingException` on unexpected error rather than returning a failure value — this two-channel split is **deliberate**: the enum carries expected, operator-actionable outcomes; exceptions carry unexpected failures whose cause must survive for diagnosis. `ExportAllSitesResults` is an actionable-outcome enum, not a general error enum — only add a constant for something an operator can remedy.

## S3 Upload Configuration

`GraphQLWebsitesConfig` exposes: `awsS3Region`, `awsS3AccessKey`, `awsS3SecretAccessKey`, `awsS3BucketName`.  
If any field is blank, `exportAllSites` returns `AWS_S3_BUCKET_NOT_CONFIGURED` **before exporting anything** — the precondition is checked first, so an unconfigured instance does no export work at all. (It used to export every site and then delete the archive unread.)  
The exported ZIP is always deleted from disk after upload (or on failure), via `FileUtils.deleteQuietly`.

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

The three mutations use three different privilege scopes. This is by design — the rule is whether the operation *degrades safely* when denied rights:

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

Five permissions, all children of `admin`. Each mutation carries its own so operations are
independently delegable:

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
- `websitesDelete` is **absent from the root-granted server role**. JCR permissions inherit down
  the tree, so granting it at `/` would satisfy the per-site check everywhere and make it vacuous.
  It ships instead on the site-scoped `graphql-extension-websites-site-administrator` role.
- Both sit under `admin`, so server administrators aggregate them and keep full reach with no
  special case in the code. Do **not** add a `callerIsServerAdministrator()` fallback to
  `deleteSiteByKey`; it would be redundant and would mask a misconfigured permission tree.

`createSiteByKey`'s residual risk is bounded: the caller picks `templateSet` and `modulesToDeploy`, so they can enable any **already-installed** module on the new site — but cannot install modules (that needs the module manager) nor affect existing sites.

## Gotchas

- `exportWebsite` is `@GraphQLAsync` — the GraphQL response returns before the export completes; clients cannot poll for completion via this mutation
- `exportAllSites` runs the export under the **caller's own** JCR session (SEC-136) — it does NOT escalate to root. The archive is confined to content the caller is authorized to read, so a `websitesAdmin` holder cannot exfiltrate content beyond their own rights
- `deleteSiteByKey` returns `false` only for **domain** failures (`JahiaException`, site not found). Unchecked exceptions propagate to the GraphQL layer instead of being flattened into `false`, matching how `exportAllSites` raises `DataFetchingException`
- Bulk export archives are named `export-<yyyyMMddHHmmss>-<8 hex>.zip`. The random suffix is required, not cosmetic: the name doubles as the S3 object key, and the previous minute-granular name let two concurrent exports collide on the same path and key
- `ImportInfo.asMap()` guards its whole body on `siteProperties != null`, and `importWebsite` never sets that field — so in production it always returns an **empty** map. Traced against Jahia 8.2 sources: `importSiteZip` only reads that map under `if (legacyImport)` (Jahia 5.x/6.1 archives), so it is **inert for modern 8.x imports** and deliberately left alone. Pinned by `ImportInfoTest` — read its javadoc before changing it
- `importWebsite` expects a specific directory layout under `jahiaImportsDiskPath`: `{importPath}/export.properties`, `{importPath}/roles/`, `{importPath}/users/`, `{importPath}/{siteKey}/`
- Creating a site requires a valid template set; if the template set is not installed, `addSite` throws a `JahiaException` and the mutation returns `false`
