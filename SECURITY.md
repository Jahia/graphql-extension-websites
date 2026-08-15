# Security Policy

## Supported Versions

Only the latest released version receives security fixes. Earlier releases are not patched —
upgrade rather than backport.

| Version | Supported |
|---|---|
| 2.2.x | Yes |
| 2.1.x and earlier | No |

Releases before 2.2.0 are affected by parts of advisory **GHSA-r6x2-vrm8-vjvr** (SEC-136),
whose remediation was delivered across 2.0.0, 2.1.0 and 2.2.0: site deletion became scoped to
the target site in 2.1.0, and in 2.2.0 single-site export became target-scoped too, the bulk
export was restricted to server administrators, and the server role stopped granting
repository-wide read.

Upgrading changes who can do what. Read
[README.md § Upgrading](README.md#upgrading) before deploying 2.2.0 over an
earlier version — existing delegated integrations need per-site roles granted.

## Permission model

The permission model is the security boundary of this module, and granting the wrong permission
at the wrong node re-opens the advisory. Before creating a custom role, read:

- [README.md § Permissions](README.md#permissions) — what each mutation actually requires
- [README.md § Delegating a narrower subset](README.md#delegating-a-narrower-subset) — which
  permissions are safe at `/` and which must only ever be granted per site
- [README.md § Export confidentiality](README.md#export-confidentiality) — what bounds an export

## Reporting a Vulnerability

Security information can be found in our [security.txt file](https://academy.jahia.com/.well-known/security.txt).
