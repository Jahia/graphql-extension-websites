# graphql-extension-websites

Jahia OSGi module that extends the GraphQL admin API with site lifecycle operations: create, delete, export, import, and bulk export-to-S3 of Jahia websites. No admin UI — pure GraphQL extension.

## Key Facts

- **artifactId**: `graphql-extension-websites` | **version**: `1.1.1-SNAPSHOT`
- **Java package**: `org.jahia.community.graphql.provider.dxm.extensions.websites`
- **jahia-depends**: `default,graphql-dxm-provider`
- **No frontend**, no admin UI
- Depends on **AWS SDK v2** (`software.amazon.awssdk`) for S3 uploads

## Architecture

| Class | Role |
|-------|------|
| `DXGraphQLExtensionWebsitesProvider` | Registers mutations with the DXM GraphQL provider |
| `WebsitesMutation` | `@GraphQLTypeExtension(GqlJahiaAdminMutation.class)` — all site operations |
| `GraphQLWebsitesConfig` | `@Component` OSGi service; holds AWS S3 credentials/config |
| `ImportInfo` | Value object used during import orchestration |

## GraphQL API

All mutations extend `GqlJahiaAdminMutation` (`admin.jahia.*`). Permission: `admin`.

| Mutation | Signature | Notes |
|----------|-----------|-------|
| `createSiteByKey` | `(siteKey, serverName, serverNameAliasesAsString, title, templateSet, modulesToDeploy[], locale)` → Boolean | Uses `SiteCreationInfo` |
| `deleteSiteByKey` | `(siteKey)` → Boolean | Returns `false` if site not found |
| `exportWebsite` | `(siteKey, exportPath, onlyStaging)` → Boolean | `@GraphQLAsync`; exports to `exportPath` on disk |
| `importWebsite` | `(importPath, siteKey)` → Boolean | Path relative to `jahiaImportsDiskPath`; reads `export.properties` |
| `exportAllSites` | `()` → `ExportAllSitesResults` | Exports to `jahiaVarDiskPath/exports/export-{timestamp}.zip`, then uploads to S3 |

`ExportAllSitesResults` enum: `SUCCESS`, `FAILURE`, `AWS_S3_BUCKET_NOT_CONFIGURED`.

## S3 Upload Configuration

`GraphQLWebsitesConfig` exposes: `awsS3Region`, `awsS3AccessKey`, `awsS3SecretAccessKey`, `awsS3BucketName`.  
If any field is blank, `exportAllSites` returns `AWS_S3_BUCKET_NOT_CONFIGURED` without uploading.  
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

- Tests: `tests/cypress/e2e/01-graphqlExtensionWebsites.cy.ts`
- Covers: create site, delete site, export site, `exportAllSites` (expects `AWS_S3_BUCKET_NOT_CONFIGURED` in CI)
- Site `cypress-test-website` is created and deleted within tests

## Gotchas

- `exportWebsite` is `@GraphQLAsync` — the GraphQL response returns before the export completes; clients cannot poll for completion via this mutation
- `exportAllSites` temporarily switches the JCR session user to root for the export, then restores the original user in `finally`
- `importWebsite` expects a specific directory layout under `jahiaImportsDiskPath`: `{importPath}/export.properties`, `{importPath}/roles/`, `{importPath}/users/`, `{importPath}/{siteKey}/`
- Creating a site requires a valid template set; if the template set is not installed, `addSite` throws a `JahiaException` and the mutation returns `false`
