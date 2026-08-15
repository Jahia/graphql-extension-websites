# Jahia GraphQL Extension Websites

The purpose of this module is to allow the creation, deletion, import and export of a website thanks to GraphQL queries.

## Permissions

**Every mutation is gated by a permission**, checked by the GraphQL security layer before the
method body executes.  Callers that do not hold the relevant permission have their request
rejected.

Those gates are **not all distinct**.  Only `createSiteByKey` (`websitesCreate`) and
`exportAllSites` (`websitesExportAll`) have a dedicated permission of their own.
`deleteSiteByKey`, `exportWebsite` and `importWebsite` **share** the coarse `websitesAdmin`
gate and are separated from one another by an additional check in the method body — so granting
`websitesAdmin` to delegate one of the three opens the annotation gate on the other two as
well.  The in-body checks, not the annotation, are what keep them apart.

Permissions named in the annotations are evaluated against the **repository root**, so they
answer "may this caller perform this kind of operation" — not "on which site".  Mutations that
act on a specific site carry an additional target-scoped check in the method body.

### Reach of each mutation

| Mutation | Required to succeed | Reach |
|---|---|---|
| `createSiteByKey` | `websitesCreate` | Creates a new site. Runs under a system session — that escalation is the delegation mechanism (see below) |
| `deleteSiteByKey` | `websitesAdmin` **and** `websitesDelete` **on the target site** | **Only sites the caller is authorized on.** Server administrators retain full reach |
| `exportWebsite` | `websitesAdmin` **and** `websitesExport` **on the target site** | **Only sites the caller is authorized on.** Runs as the caller, so the archive is further bounded by that session's read rights |
| `exportAllSites` | `websitesExportAll` **and** full server administrator (`admin` at `/`) | Instance-wide, so restricted to server administrators |
| `importWebsite` | `websitesAdmin` **and** full server administrator (`admin` at `/`) | Instance-wide; imports users and roles |

`deleteSiteByKey` and `importWebsite` keep the coarse `websitesAdmin` annotation because their
real authorization lives in the method body.  For deletion the annotation **must not** be
`websitesDelete`: that permission is intentionally never granted at the repository root, so
annotating it there would deny every site-scoped holder before the body could run.

`exportWebsite` follows the same pattern for the same reason: its fine permission
(`websitesExport`) lives only on the site-scoped role, so naming it in the annotation — which is
evaluated at `/` — would deny every site administrator.  Adding it to the server role instead
would be worse: granted at `/` it inherits down to every site, satisfying the in-body per-site
check everywhere and making it meaningless.

### Site deletion is scoped to the caller (SEC-136)

Until 2.1.0, `deleteSiteByKey` was gated **only** by the root-evaluated `websitesAdmin`
annotation, so any holder of the delegated `graphql-extension-websites-administrator` role
could delete **any** site on the instance — including sites it never created and held no
rights on.

From 2.1.0 the mutation additionally requires the `websitesDelete` permission **on the site
being deleted**, checked in the caller's own JCR session.  Grant it per site with the
`graphql-extension-websites-site-administrator` role (see [Roles](#roles)).  Nothing below
this check enforces it: `JahiaSitesService.removeSite` performs the deletion under a system
session, so the caller's rights are never consulted by Jahia itself.

### Export confidentiality

Both export mutations run under the **calling user's own** JCR session (SEC-136) — neither
escalates to root — so an archive is bounded by what that session may read.

Until 2.2.0 that bound was worthless for the shipped role, which granted `jcr:read_default` at
the repository root: "confined to the caller's read rights" plus "the caller can read
everything" is still a full-instance dump.  The server role no longer carries that grant.  Read
is granted per site through the site-scoped role, so a site administrator's export contains
their site and nothing else.

That root grant was never needed to reach the API.  Verified on a live instance: a caller
holding only `graphqlAdminMutation` and `websitesCreate` — no read permission at all — can
invoke `createSiteByKey` successfully.  Jahia already satisfies the DXM `admin` field's
`jcr:read/jcr:system` requirement for authenticated users by other means.

`exportAllSites` is a separate case: a bulk export spans the whole instance, so it is restricted
to server administrators outright.  A read-bounded bulk export would hand a delegated holder an
archive silently containing only their own sites — a partial backup that looks complete, which
is worse than a refusal.  Non-administrators get `NOT_SERVER_ADMINISTRATOR`.

## Roles

The module ships two roles:

| Role | Granted at | Carries | Purpose |
|---|---|---|---|
| `graphql-extension-websites-administrator` | `/` (server role) | `graphqlAdminMutation`, `websitesAdmin`, `websitesCreate`, `websitesExportAll` | Reach the API and create sites. Does **not** grant site deletion or site export, and no longer grants repository-wide read |
| `graphql-extension-websites-site-administrator` | `/sites/<siteKey>` (site role) | `jcr:read_default`, `websitesDelete`, `websitesExport` | Export and delete **that** site. Grant per site, in addition to the server role. Includes read on the site, which `exportWebsite` needs to produce a non-empty archive |

### Delegating a narrower subset

You are not limited to the shipped roles: a custom role can carry any subset of the five
permissions.  **Read this before building one — *where* you grant a permission matters as much
as which one you grant.**

`websitesDelete` and `websitesExport` are checked in the method body **against the target site
node**, and JCR permissions inherit down the tree.  Granting either at the repository root
therefore satisfies that per-site check on **every** site and makes it vacuous: a root grant of
`websitesDelete` lets the holder **delete any site on the instance** — which is exactly the
SEC-136 vulnerability, reinstated.  Those two belong on a site role only.

For the same reason `websitesDelete` is declared as a **sibling** of `websitesAdmin` in
`permissions.xml`, never nested beneath it — Jahia registers nested permission nodes as
aggregated sub-privileges, so nesting would hand it back to every holder of the server role.

#### Safe to grant at `/` (server role)

| Permission | Grants |
|---|---|
| `websitesCreate` | `createSiteByKey` |
| `websitesExportAll` | Reaches `exportAllSites`, which then requires full server administrator |
| `websitesAdmin` | Reaches `deleteSiteByKey`, `exportWebsite` and `importWebsite` — the coarse annotation gate only; each one's real gate is in the method body |

#### Grant ONLY per site — never at `/`

| Permission | Grants | Consequence of a root grant |
|---|---|---|
| `websitesDelete` | `deleteSiteByKey`, **on the site it is granted on** | The holder can delete **any** site on the instance (SEC-136) |
| `websitesExport` | `exportWebsite`, **on the site it is granted on** | The holder can export **any** site on the instance |

Any custom role also needs `graphqlAdminMutation` to traverse the `admin { jahia { ... } }`
wrapper at all.  It does **not** need `jcr:read_default` at the root: that grant was removed
from the server role in 2.2.0 precisely because it leaked repository-wide read (see
[Export confidentiality](#export-confidentiality)), and a caller holding only
`graphqlAdminMutation` and `websitesCreate` reaches the API fine.  Read is needed **per site**
only, and only because `exportWebsite` must read the site's content to produce a non-empty
archive.

`websitesDelete` and `websitesExport` also sit under `admin`, so a full server administrator
aggregates both and keeps unrestricted site deletion and export without any special case in the
code.

## Upgrading

> Jahia imports a module's initial JCR content **once per module version**.  The roles and
> permissions described under [Roles](#roles) therefore land only on a version change —
> redeploying an unchanged version will not import them.

### 2.1.x → 2.2.0

Three behavioural changes are **breaking for existing delegated integrations**.  Full server
administrators are unaffected: all five permissions are children of `admin`, so an `admin`
holder aggregates them.

1. **`jcr:read_default` was removed from `graphql-extension-websites-administrator`.**  The
   server role no longer grants repository-wide read, so a caller that relied on that root grant
   for content access no longer has it anywhere.  Exports run under the caller's own session and
   are bounded by what it can read, and an export that reads nothing still **succeeds** — it just
   writes an **empty or near-empty archive** rather than failing loudly.  Grant
   `graphql-extension-websites-site-administrator` on each site the caller must export: it
   bundles `jcr:read_default` on that site, which is the read the exporter needs.  Reaching the
   API itself never required the root grant (see
   [Export confidentiality](#export-confidentiality)).
2. **Deletion and single-site export are target-scoped.**  Anyone who previously reached
   `deleteSiteByKey` or `exportWebsite` through the server role alone is now refused (returns
   `false`) until `graphql-extension-websites-site-administrator` is granted **per site**, on
   every site they must act on.  Deletion became target-scoped in 2.1.0, export in 2.2.0.
3. **`exportAllSites` is restricted to server administrators.**  A delegated caller holding
   `websitesExportAll` but not `admin` at `/` now gets `NOT_SERVER_ADMINISTRATOR` and nothing is
   exported — the gate runs before the S3 precondition.  Either grant that caller `admin` at
   `/`, or move the bulk export to a server-administrator account.

## Installation

- In Jahia, go to "Administration --> Server settings --> System components --> Modules"
- Upload the JAR **graphql-extension-websites-X.X.X.jar**
- Check that the module is started

## Configuration (AWS S3 for exportAllSites)

AWS S3 credentials and bucket details **must** be set via the OSGi ConfigurationAdmin
service — never pass them inline in a GraphQL mutation.

Edit (or drop) the file at:

```
<jahia-var>/karaf/etc/org.jahia.community.graphql.websites.cfg
```

```properties
aws.s3.region=us-east-1
aws.s3.bucketName=my-backup-bucket
aws.s3.accessKey=<your-access-key-id>
aws.s3.secretAccessKey=<your-secret-access-key>
```

The default shipped configuration (`src/main/resources/META-INF/configurations/org.jahia.community.graphql.websites.cfg`)
has all values blank so that `exportAllSites` returns `AWS_S3_BUCKET_NOT_CONFIGURED` until
the administrator provides real credentials.  Jahia's module extender will **not** overwrite
a deployed `karaf/etc` file that was already customised (the file starts with
`# default configuration - won't be overridden`).

## How to use
### In the tools

- Go to the page **"Jahia GraphQL Core Provider : graphql-playground"** (JAHIA_URL/modules/graphql-dxm-provider/tools/graphql-playground.jsp)

#### Creation
```graphql
mutation {
    admin {
        jahia {
            websites {
                createSiteByKey(
                    siteKey: "SITE_KEY"
                    serverName: "SERVER_NAME"
                    title: "SITE_TITLE"
                    templateSet: "TEMPLATE_SET"
                    locale: "LOCALE"
                )
            }
        }
    }
}
```
#### Deletion
```graphql
mutation {
    admin {
        jahia {
            websites {
                deleteSiteByKey(siteKey: "SITE_KEY")
            }
        }
    }
}
```
#### Import
```graphql
mutation {
    admin {
        jahia {
            websites {
                importWebsite(
                    importPath: "RELATIVE_IMPORT_PATH",
                    siteKey: "SITE_KEY"
                )
            }
        }
    }
}
```
#### Export
```graphql
mutation {
    admin {
        jahia {
            websites {
                exportWebsite(
                    siteKey: "SITE_KEY",
                    exportPath: "RELATIVE_EXPORT_PATH",
                    onlyStaging: true
                )
            }
        }
    }
}
```

`exportPath` is **relative to `<jahia-var>/exports/`** (`jahiaVarDiskPath` + `/exports`), the
same way `importWebsite`'s `importPath` is relative to `jahiaImportsDiskPath`.  A value that
escapes that directory — `..` segments, an absolute path, a null byte, or a symlink resolving
outside it — is rejected and the mutation returns `false` without exporting anything.

#### Export All Sites To AWS S3

Configure credentials via the `.cfg` file described in the **Configuration** section above,
then trigger the export:

```graphql
mutation {
    admin {
        jahia {
            websites {
                exportAllSites
            }
        }
    }
}
```

Possible return values (the full `ExportAllSitesResults` enum):
- `NOT_SERVER_ADMINISTRATOR` — the caller does not hold `admin` at `/`. This is the **first**
  branch, checked before the S3 precondition, so it is the value a delegated caller actually
  sees — and an unauthorized caller learns nothing about whether S3 is configured.
- `AWS_S3_BUCKET_NOT_CONFIGURED` — one or more S3 config values are blank. The S3 configuration
  is checked **before** exporting, so nothing is exported and no upload is attempted.
- `SUCCESS` — export and S3 upload completed; the local ZIP was removed.

On an unexpected error the mutation raises a GraphQL error (`DataFetchingException`) rather
than returning an enum value — check the Jahia server logs.

### Why two channels

The split is deliberate, and reflects whether you can act on the outcome:

| Outcome | Channel | Rationale |
|---------|---------|-----------|
| S3 not configured | enum value | An expected precondition you fix by supplying configuration. There is no diagnostic detail worth propagating. |
| Anything unexpected (JCR, I/O, XML, AWS transfer) | GraphQL error | The underlying cause must survive so the failure can be diagnosed. Flattening it into an enum constant would discard exactly that. |

So branch on the returned value for configuration problems, and handle GraphQL errors for
everything else. `ExportAllSitesResults` is an *actionable-outcome* enum, not a general error
enum — only add a constant for something an operator can remedy.

> **Security note:** do **not** set credentials inline via the `configuration(...)` mutation
> as shown in older documentation.  That approach leaks secrets into GraphQL access logs and
> audit trails.  Use the `.cfg` file or an OSGi-compatible secrets manager instead.

## Interpreting a `false` return

The four `Boolean` mutations collapse several distinct failures onto the same `false`.  That is
deliberate — telling an unauthorized caller *why* it was refused would leak whether a given site
exists — but it is a real integration constraint: **you can branch on success, not on the
reason.**  The Jahia server log carries the distinction.

| Mutation | `false` means any of |
|---|---|
| `exportWebsite` | the `exportPath` was rejected (escapes `<jahia-var>/exports/`, is blank, or the resolved target is a symlink) · the site does not exist · the caller lacks `websitesExport` on that site · the export itself failed (JCR, I/O, XML) |
| `deleteSiteByKey` | the site does not exist · the caller lacks `websitesDelete` on that site · deletion failed (`JahiaException`) |
| `importWebsite` | the caller is not a server administrator · `importPath` or `siteKey` was rejected as a traversal attempt · `export.properties` could not be read · the site import failed |
| `createSiteByKey` | creation failed — e.g. the template set is not installed, or a site with that key already exists |

None of these raise a GraphQL error; the authorization refusals in particular are ordinary
`false` values, not errors.  Unchecked exceptions are the one exception: they propagate to the
GraphQL layer instead of being flattened into `false`, so a bug stays distinguishable from a
genuine domain failure.

## Residual risks (documented, not fixed here)

- **Pre-import zip-slip / archive validation**: the ZIP content is validated by Jahia core's
  `ImportExportBaseService`.  This module delegates entirely to that layer; no additional
  ZIP-slip check is performed here.  The mutation is gated by `websitesAdmin`, so only
  trusted administrators can trigger imports.
- **`exportAllSites` privilege scope**: resolved in 2.2.0 — the bulk export now requires full
  server-administrator rights, and the server role no longer grants repository-wide read.  See
  [Export confidentiality](#export-confidentiality).
- **`createSiteByKey` privilege scope**: it runs under a system session, and that escalation
  is load-bearing (writing `/sites/<siteKey>` needs rights on `/sites` that a delegated holder
  lacks).  A holder can therefore create sites and enable any **already-installed** module on
  them, but cannot install modules nor affect existing sites.  Unlike deletion, creation is not
  target-scoped, because there is no pre-existing target node to scope against.
