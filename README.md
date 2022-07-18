# Jahia GraphQL Extension Websites

The purpose of this module is to allow the creation, deletion, import and export of a website thanks to GraphQL queries.

## Installation

- In Jahia, go to "Administration --> Server settings --> System components --> Modules"
- Upload the JAR **graphql-extension-websites-X.X.X.jar**
- Check that the module is started

## How to use
### In the tools

- Go to the page **"Jahia GraphQL Core Provider : graphql-playground"** (JAHIA_URL/modules/graphql-dxm-provider/tools/graphql-playground.jsp)

#### Creation
```
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
```
    mutation {
      admin {
        jahia {
          deleteSiteByKey(siteKey: "SITE_KEY")
        }
      }
    }
```
#### Import
```
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
```
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
