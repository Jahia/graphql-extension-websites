# Jahia GraphQL Extension Websites

The purpose of this module is to allow the creation, deletion, import and export of a website thanks to GraphQL queries.

## Permissions

**All mutations in this module require the `websitesAdmin` permission.**  Callers that do not
hold this permission will have their request rejected by the GraphQL security layer before
any operation is attempted.

Note that `exportAllSites` temporarily escalates the JCR session to the root user so that
the full instance content (all sites, roles, users) is exported, regardless of what the
calling user can normally read.  Grant `websitesAdmin` only to fully trusted administrators.

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
```
#### Deletion
```graphql
mutation {
    admin {
        jahia {
            deleteSiteByKey(siteKey: "SITE_KEY")
        }
    }
}
```
#### Import
```graphql
mutation {
    admin {
        jahia {
            importWebsite(
                importPath: "RELATIVE_IMPORT_PATH",
                siteKey: "SITE_KEY"
            )
        }
    }
}
```
#### Export
```graphql
mutation {
    admin {
        jahia {
            exportWebsite(
                siteKey: "SITE_KEY",
                exportPath: "RELATIVE_EXPORT_PATH",
                onlyStaging: true
            )
        }
    }
}
```

#### Export All Sites To AWS S3

Configure credentials via the `.cfg` file described in the **Configuration** section above,
then trigger the export:

```graphql
mutation {
    admin {
        jahia {
            exportAllSites
        }
    }
}
```

Possible return values:
- `SUCCESS` — export and S3 upload completed; the local ZIP was removed.
- `AWS_S3_BUCKET_NOT_CONFIGURED` — one or more S3 config values are blank; no upload attempted.
- `FAILURE` — unexpected error (check the Jahia server logs).

> **Security note:** do **not** set credentials inline via the `configuration(...)` mutation
> as shown in older documentation.  That approach leaks secrets into GraphQL access logs and
> audit trails.  Use the `.cfg` file or an OSGi-compatible secrets manager instead.

## Residual risks (documented, not fixed here)

- **Pre-import zip-slip / archive validation**: the ZIP content is validated by Jahia core's
  `ImportExportBaseService`.  This module delegates entirely to that layer; no additional
  ZIP-slip check is performed here.  The mutation is gated by `websitesAdmin`, so only
  trusted administrators can trigger imports.
- **`exportAllSites` privilege scope**: as noted above, the export runs as root and captures
  the whole instance.  Re-scoping to a stronger permission than `websitesAdmin` is deferred.
