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
- Configure your S3 endpoint in the Jahia configuration file
```graphql
mutation {
    admin {
        jahia {
            configuration( pid: "org.jahia.community.graphql.websites" ) {
                awsRegion: value(name:"aws.s3.region", value:"us-east-2"),
                awsBucketName: value(name:"aws.s3.bucketName", value:"graphqltestbucketonaws"),
                awsAccessKey: value(name:"aws.s3.accessKey", value:"test")
                awsSecretAccessKey: value(name:"aws.s3.secretAccessKey", value:"test")
            }
        }
    }
}
```
- Then launch the export of all sites
```graphql
mutation {
    admin {
        jahia {
            exportAllSites
        }
    }
}
```
