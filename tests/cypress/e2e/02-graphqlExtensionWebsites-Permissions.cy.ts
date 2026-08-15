import {DocumentNode, print} from 'graphql';
import {createUser, deleteUser, grantRoles, revokeRoles} from '@jahia/cypress';
import {errorMessagesOf, errorsOf, websitesResult} from '../support/graphqlResult';
import {expectModuleStarted, expectWebsitesApiAvailable} from '../support/deploymentSmoke';
import {cleanupStagedDirs, expectExportArtifactAbsent, expectExportArtifactPresent} from '../support/exportArtifacts';
import {
    createSiteAsRoot,
    deleteSiteAndExpectItGone,
    deleteSiteQuietly,
    expectSiteToBeAbsent,
    expectSiteToExist
} from '../support/siteLifecycle';

/**
 * Regression tests for the SEC-136 authorization model.
 *
 * These guard against the gates being silently removed or mismatched across the stack.
 *
 * Backend — every mutation carries its OWN `@GraphQLRequiresPermission`, enforced by the DXM
 * provider as a `session.getNode("/").hasPermission(perm)` check on the JCR root node, and three
 * of them carry a second, finer gate inside the method body:
 *
 *   | Mutation          | Annotation (at `/`) | In-body gate                        |
 *   |-------------------|---------------------|-------------------------------------|
 *   | `createSiteByKey` | `websitesCreate`    | —                                   |
 *   | `exportWebsite`   | `websitesAdmin`     | `websitesExport` on the target site |
 *   | `exportAllSites`  | `websitesExportAll` | `admin` at `/`                      |
 *   | `deleteSiteByKey` | `websitesAdmin`     | `websitesDelete` on the target site |
 *   | `importWebsite`   | `websitesAdmin`     | `admin` at `/`                      |
 *
 * The annotation is NOT the same permission on every field — `createSiteByKey` and
 * `exportAllSites` have their own — and the two target-scoped mutations deliberately do not name
 * their fine permission in the annotation. It is evaluated at `/`, where `websitesExport` /
 * `websitesDelete` are never granted (they live on the site-scoped role), so naming them there
 * would deny every site administrator before the body ran.
 *
 * RBAC content — the module ships the assignable `graphql-extension-websites-administrator`
 * (server) and `graphql-extension-websites-site-administrator` (site) roles in
 * src/main/import/roles.xml. Because these mutations are nested under the DXM
 * `admin { jahia { ... } }` wrapper, reaching a gated field also requires `graphqlAdminMutation`.
 * The `admin` field's own `@GraphQLRequiresPermission("jcr:read/jcr:system")` does NOT require the
 * role to grant `jcr:read_default`: verified false on a live instance in §4.3 — a user holding
 * only `graphqlAdminMutation` + `websitesCreate`, with no read permission at all, reaches
 * `createSiteByKey` successfully.
 *
 * This module is API-only (no admin UI), so only the GraphQL authorization is asserted. The
 * "allowed" user is granted the single server role and nothing else — never the `admin`
 * permission — so the tests prove fine-grained granularity, not merely that a full administrator
 * can pass.
 *
 * Two invariants for anyone adding a block here:
 *
 *  1. **Run order is not top-to-bottom unless everything is in a `describe`.** Mocha runs a
 *     suite's own `it`s BEFORE its nested suites, so a bare `it` added at the bottom of this file
 *     would execute first. Every test therefore lives in a nested `describe`, and the root
 *     `beforeEach` resets the Apollo client to root — without it, a block written without an
 *     explicit reset silently inherits the impersonated client from whichever test ran last and
 *     passes (or fails) as the wrong user. Suite-level `before`/`after` hooks run outside that
 *     `beforeEach`, so they reset the client themselves.
 *  2. **Assert your setup.** An unasserted `createSiteByKey` makes a denial spec pass for the
 *     wrong reason: with no site, the target-scoped mutations return `false` because
 *     `site == null`, which is exactly the value the denial asserts. Use the helpers in
 *     cypress/support/siteLifecycle.ts, which assert.
 */
describe('GraphQL Extension Websites — permission enforcement', () => {
    const ROLE_NAME = 'graphql-extension-websites-administrator';
    const SITE_ROLE = 'graphql-extension-websites-site-administrator';
    const DENIED_USER = 'gewDeniedUser';
    const ALLOWED_USER = 'gewAllowedUser';
    // Holds a custom role carrying websitesCreate but NOT websitesExportAll — the user that
    // makes the per-operation permission split falsifiable.
    const CREATE_ONLY_USER = 'gewCreateOnlyUser';
    const CREATE_ONLY_ROLE = 'gew-test-create-only';
    const PASSWORD = 'GewPerm9PwdTest';
    const TEMPLATE_SET = 'templates-system';
    // The import round trip reuses the template set spec 03 proves the exporter/importer pair
    // against, so a failure there is about authorization and not about the template set.
    const ROUNDTRIP_TEMPLATE_SET = 'default';

    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const exportAllSites: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/exportAllSites.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const createSiteByKey: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createSiteByKey.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deleteSiteByKey: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteSiteByKey.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const exportWebsite: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/exportWebsite.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const importWebsite: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/importWebsite.graphql');

    const asRoot = () => {
        cy.apolloClient(); // Reset the current Apollo client back to root
        cy.login();
    };

    const runAs = (username: string, mutation: DocumentNode, variables?: Record<string, unknown>) => {
        cy.apolloClient({username, password: PASSWORD});
        return cy.apollo({mutation, variables});
    };

    const exportAllSitesAs = (username: string) => runAs(username, exportAllSites);

    const expectPermissionDenied = (result: unknown, message: string) => {
        expect(errorsOf(result), message).to.have.length.greaterThan(0);
        expect(errorMessagesOf(result), message).to.contain('Permission denied');
    };

    const expectAnnotationGatePassed = (result: unknown) => {
        expect(
            errorsOf(result),
            `annotation gate is passed — no Permission denied, got: ${errorMessagesOf(result)}`
        ).to.have.length(0);
    };

    before(() => {
        cy.login();
        expectWebsitesApiAvailable();
        // Every site fixture below is built on this template set; if it is not deployed, addSite
        // throws and creation returns false, which would look like an authorization failure.
        expectModuleStarted(TEMPLATE_SET);

        createUser(DENIED_USER, PASSWORD);
        createUser(ALLOWED_USER, PASSWORD);
        createUser(CREATE_ONLY_USER, PASSWORD);
        // The annotation resolves the permission on the JCR root node, so grant the
        // module-shipped role on `/`.
        grantRoles('/', [ROLE_NAME], ALLOWED_USER, 'USER');

        // The shipped role carries every permission, so it cannot demonstrate that the
        // per-operation split does anything. Build a deliberately narrow role instead.
        cy.executeGroovy('createNarrowRole.groovy', {
            __ROLE_NAME__: CREATE_ONLY_ROLE,
            __PERMISSIONS__: 'websitesCreate'
        });
        grantRoles('/', [CREATE_ONLY_ROLE], CREATE_ONLY_USER, 'USER');
    });

    after(() => {
        asRoot();
        // Deleting the user does not remove the ACE that grantRoles wrote on `/`; revoke first so
        // the repository root is left exactly as the run found it.
        revokeRoles('/', [ROLE_NAME], ALLOWED_USER, 'USER');
        revokeRoles('/', [CREATE_ONLY_ROLE], CREATE_ONLY_USER, 'USER');
        deleteUser(DENIED_USER);
        deleteUser(ALLOWED_USER);
        deleteUser(CREATE_ONLY_USER);
        // The narrow role is a node under /roles; without this it outlives the run.
        cy.executeGroovy('deleteRole.groovy', {__ROLE_NAME__: CREATE_ONLY_ROLE});
    });

    beforeEach(() => {
        // Invariant 1: no test inherits the previous test's impersonated Apollo client.
        asRoot();
    });

    describe('GraphQL API authorization', () => {
        it('denies the gated mutation for a user without the permission', () => {
            exportAllSitesAs(DENIED_USER).then((result: unknown) => {
                expectPermissionDenied(result, 'denial errors');
            });
        });

        // The holder passes the `websitesExportAll` annotation gate — no "Permission denied" —
        // and is then refused by the in-body administrator gate added in §4.3. Asserting the
        // enum value rather than an error is what distinguishes "reached the resolver and was
        // refused there" from "never got past the annotation", which is the whole point of
        // having two layers.
        it('allows the gated mutation through the annotation, then self-aborts for a non-administrator', () => {
            exportAllSitesAs(ALLOWED_USER).then((result: unknown) => {
                expectAnnotationGatePassed(result);
                expect(websitesResult(result, 'exportAllSites')).to.eq('NOT_SERVER_ADMINISTRATOR');
            });
        });

        // Nothing below the GraphQL layer authenticates the caller, so the guest case has to be
        // asserted explicitly. cy.apolloClient always sends a Basic header (root by default), so
        // an anonymous call cannot be made through it — go to the endpoint directly.
        it('denies an unauthenticated caller outright', () => {
            // A JSESSIONID left by cy.login() would otherwise be attached by cy.request and the
            // "anonymous" call would run as root.
            cy.clearAllCookies();
            cy.request({
                method: 'POST',
                url: '/modules/graphql',
                failOnStatusCode: false,
                body: {query: print(exportAllSites)}
            }).then(response => {
                // The load-bearing assertion is that the resolver never produced a value. Any of
                // SUCCESS / AWS_S3_BUCKET_NOT_CONFIGURED / NOT_SERVER_ADMINISTRATOR would mean a
                // guest got past the annotation gate. The transport shape (status code, error
                // wording) is deliberately not asserted — it is not the security property.
                expect(
                    websitesResult(response.body, 'exportAllSites'),
                    `an anonymous caller must not reach the resolver (HTTP ${response.status})`
                ).to.eq(undefined);
            });
            cy.login();
        });
    });

    // The annotation gate fires on every mutation field, each with its own permission (see the
    // table at the top of this file) — it is NOT one shared `websitesAdmin` check. A user holding
    // no role at all is refused before any method body runs: `exportAllSites` is covered by the
    // block above, the other four here.
    describe('every mutation is refused for a user with no role', () => {
        const DENIED_SITE = 'gewDeniedSite';
        const DENIED_EXPORT_DIR = 'gew-denied-export';

        const deniedCases: Array<{ name: string; mutation: DocumentNode; variables: Record<string, unknown> }> = [
            {
                name: 'createSiteByKey',
                mutation: createSiteByKey,
                variables: {
                    siteKey: DENIED_SITE,
                    serverName: `${DENIED_SITE}.local`,
                    title: 'Denied site',
                    templateSet: TEMPLATE_SET,
                    locale: 'en'
                }
            },
            {name: 'deleteSiteByKey', mutation: deleteSiteByKey, variables: {siteKey: DENIED_SITE}},
            {
                name: 'exportWebsite',
                mutation: exportWebsite,
                variables: {siteKey: 'systemsite', exportPath: DENIED_EXPORT_DIR, onlyStaging: false}
            },
            {
                name: 'importWebsite',
                mutation: importWebsite,
                variables: {importPath: 'denied', siteKey: DENIED_SITE}
            }
        ];

        // These payloads are destructive by design. If a gate regresses — the very thing this
        // block exists to catch — the mutation succeeds and leaves a real site or a real archive
        // behind, which then collides with every later run. Clean up unconditionally so a
        // regression fails once instead of wedging the environment.
        after(() => {
            asRoot();
            deleteSiteQuietly(DENIED_SITE);
            cleanupStagedDirs({exportDir: DENIED_EXPORT_DIR});
        });

        deniedCases.forEach(({name, mutation, variables}) => {
            it(`denies ${name} for a user without the permission`, () => {
                runAs(DENIED_USER, mutation, variables).then((result: unknown) => {
                    expectPermissionDenied(result, `${name} denial errors`);
                });
            });
        });

        // The denials above only prove what the API answered. This proves what it did: a
        // "Permission denied" that still created the site would satisfy every assertion above.
        it('leaves no site and no export archive behind', () => {
            expectSiteToBeAbsent(DENIED_SITE, 'must not exist — createSiteByKey was refused');
            expectExportArtifactAbsent(DENIED_EXPORT_DIR);
        });
    });

    // Per-operation permission split (advisory §4.2). Each mutation is gated by its own
    // permission, so an operator can delegate one operation without delegating the others.
    //
    // The shipped `graphql-extension-websites-administrator` role carries all of them, so it
    // proves nothing about the split — it would pass these gates whether or not they existed.
    // These tests use a custom role carrying `websitesCreate` only. Both halves are needed:
    // the allow half shows the narrow role is genuinely usable, and the deny half shows the
    // other gates are not silently satisfied by it.
    describe('operations are independently delegable', () => {
        const CREATE_ONLY_SITE = 'gewCreateOnlySite';
        const CREATE_ONLY_EXPORT_DIR = 'gew-create-only-export';

        afterEach(() => {
            asRoot();
            deleteSiteQuietly(CREATE_ONLY_SITE);
        });

        after(() => {
            asRoot();
            cleanupStagedDirs({exportDir: CREATE_ONLY_EXPORT_DIR});
        });

        it('allows createSiteByKey for a holder of websitesCreate alone', () => {
            runAs(CREATE_ONLY_USER, createSiteByKey, {
                siteKey: CREATE_ONLY_SITE,
                serverName: `${CREATE_ONLY_SITE}.local`,
                title: 'Create only',
                templateSet: TEMPLATE_SET,
                locale: 'en'
            }).then((result: unknown) => {
                expect(errorsOf(result), 'websitesCreate must be sufficient to create').to.have.length(0);
                expect(websitesResult(result, 'createSiteByKey')).to.eq(true);
            });

            // The narrow role grants no read anywhere, so the read-back runs as root.
            asRoot();
            expectSiteToExist(CREATE_ONLY_SITE, 'must exist — websitesCreate was sufficient');
        });

        it('denies exportAllSites for that same holder — creation does not imply bulk export', () => {
            exportAllSitesAs(CREATE_ONLY_USER).then((result: unknown) => {
                expectPermissionDenied(result, 'websitesCreate must NOT satisfy websitesExportAll');
            });
        });

        it('denies exportWebsite for that same holder — creation does not imply site export', () => {
            runAs(CREATE_ONLY_USER, exportWebsite, {
                siteKey: 'systemsite',
                exportPath: CREATE_ONLY_EXPORT_DIR,
                onlyStaging: false
            }).then((result: unknown) => {
                expectPermissionDenied(result, 'websitesCreate must NOT satisfy websitesExport');
            });

            expectExportArtifactAbsent(CREATE_ONLY_EXPORT_DIR);
        });
    });

    // SEC-136 §4.3 — exportWebsite is TARGET-scoped, so a site administrator can export the site
    // they administer and nothing else, while a server administrator can export anything.
    //
    // This is the pair of properties the §4.3 change exists to deliver. The "own site" case must
    // pass or the delegation is useless; the "other site" case must fail or the scoping is
    // decorative. Testing only one of them would let a broken implementation look correct.
    //
    // Every case also asserts the FILESYSTEM, not just the boolean. `callerMayActOnSite` sits
    // before the export in exportWebsite; move it after and an unauthorized caller's archive is
    // written to disk while the mutation still returns false — indistinguishable from a working
    // gate if only the return value is read.
    describe('site export is scoped to the caller authority', () => {
        const OWNED_SITE = 'gewOwnedSite';
        const OTHER_SITE = 'gewOtherSite';
        const SITE_ADMIN_USER = 'gewSiteAdminUser';
        const OWNED_EXPORT_DIR = 'gew-owned-export';
        const OTHER_EXPORT_DIR = 'gew-other-export';
        const ROOT_EXPORT_DIR = 'gew-root-export';

        before(() => {
            asRoot();
            createUser(SITE_ADMIN_USER, PASSWORD);
            // Needs the server role to reach the API at all, plus the site role on ONE site.
            grantRoles('/', [ROLE_NAME], SITE_ADMIN_USER, 'USER');
            createSiteAsRoot(OWNED_SITE, {templateSet: TEMPLATE_SET});
            createSiteAsRoot(OTHER_SITE, {templateSet: TEMPLATE_SET});
            grantRoles(`/sites/${OWNED_SITE}`, [SITE_ROLE], SITE_ADMIN_USER, 'USER');
            // A leftover archive from an earlier run would make "the archive is present" pass on
            // its own, and "the archive is absent" fail for the wrong reason.
            cleanupStagedDirs({exportDir: OWNED_EXPORT_DIR});
            cleanupStagedDirs({exportDir: OTHER_EXPORT_DIR});
            cleanupStagedDirs({exportDir: ROOT_EXPORT_DIR});
        });

        after(() => {
            asRoot();
            revokeRoles('/', [ROLE_NAME], SITE_ADMIN_USER, 'USER');
            deleteSiteQuietly(OWNED_SITE);
            deleteSiteQuietly(OTHER_SITE);
            deleteUser(SITE_ADMIN_USER);
            cleanupStagedDirs({exportDir: OWNED_EXPORT_DIR});
            cleanupStagedDirs({exportDir: OTHER_EXPORT_DIR});
            cleanupStagedDirs({exportDir: ROOT_EXPORT_DIR});
        });

        it('allows a site administrator to export the site they administer', () => {
            runAs(SITE_ADMIN_USER, exportWebsite, {
                siteKey: OWNED_SITE,
                exportPath: OWNED_EXPORT_DIR,
                onlyStaging: true
            }).then((result: unknown) => {
                expectAnnotationGatePassed(result);
                expect(
                    websitesResult(result, 'exportWebsite'),
                    'the site role must make exportWebsite usable on the granted site'
                ).to.eq(true);
            });

            expectExportArtifactPresent(OWNED_EXPORT_DIR, OWNED_SITE);
        });

        it('refuses that same administrator on a site they do not administer', () => {
            runAs(SITE_ADMIN_USER, exportWebsite, {
                siteKey: OTHER_SITE,
                exportPath: OTHER_EXPORT_DIR,
                onlyStaging: true
            }).then((result: unknown) => {
                // The annotation still passes — the refusal must come from the target-scoped
                // check, not from the coarse gate.
                expectAnnotationGatePassed(result);
                expect(
                    websitesResult(result, 'exportWebsite'),
                    'a site administrator must not export a site they hold no rights on'
                ).to.eq(false);
            });

            // The assertion that actually proves the refusal: no archive of the other site was
            // written anywhere under the exports directory.
            expectExportArtifactAbsent(OTHER_EXPORT_DIR);
        });

        it('still allows an administrator to export either site', () => {
            cy.apollo({
                mutation: exportWebsite,
                variables: {siteKey: OTHER_SITE, exportPath: ROOT_EXPORT_DIR, onlyStaging: true}
            }).then((result: unknown) => {
                expect(errorsOf(result), 'root must not be denied').to.have.length(0);
                expect(websitesResult(result, 'exportWebsite')).to.eq(true);
            });

            // The positive control for the block above: the same site the delegated holder could
            // not export really is exportable, so the refusal was authorization and not a broken
            // export.
            expectExportArtifactPresent(ROOT_EXPORT_DIR, OTHER_SITE);
        });
    });

    // SEC-136 — importWebsite has a SECOND gate beyond the `websitesAdmin` annotation:
    // callerIsServerAdministrator() requires `admin` at the JCR root, because an import creates
    // arbitrary users AND roles. The shipped server role grants `websitesAdmin` but not `admin`,
    // so its holder passes the annotation and is then refused in the body.
    //
    // The tree below is REAL: created, exported and staged as root, exactly like the spec 03
    // round trip. Pointing this at a nonexistent path — as an earlier version did — makes the
    // test unfalsifiable: with the gate deleted the mutation still returns false, because
    // readExportProperties hits FileNotFoundException and returns null. Spec 03 demonstrates that
    // itself, by getting the same "no error, false" out of root for a missing path.
    describe('importWebsite requires full server-administrator rights', () => {
        const IMPORT_SITE = 'gewImportGateSite';
        const IMPORT_EXPORT_DIR = 'gew-import-gate-export';
        const IMPORT_STAGED_DIR = 'gew-import-gate-import';

        before(() => {
            asRoot();
            cleanupStagedDirs({exportDir: IMPORT_EXPORT_DIR, importDir: IMPORT_STAGED_DIR});
            createSiteAsRoot(IMPORT_SITE, {templateSet: ROUNDTRIP_TEMPLATE_SET});

            cy.apollo({
                mutation: exportWebsite,
                variables: {siteKey: IMPORT_SITE, exportPath: IMPORT_EXPORT_DIR, onlyStaging: false}
            })
                .its('data.admin.jahia.websites.exportWebsite')
                .should('eq', true);

            // Moves the tree from {jahiaVarDiskPath}/exports to {jahiaImportsDiskPath} and waits
            // out the @GraphQLAsync export. Throws — and so fails this hook — if the export never
            // materialised, so the tests below can rely on the tree being importable.
            cy.executeGroovy('stageImportTree.groovy', {
                __EXPORT_DIR__: IMPORT_EXPORT_DIR,
                __IMPORT_DIR__: IMPORT_STAGED_DIR,
                __SITE_KEY__: IMPORT_SITE
            });

            // Remove the source so an import genuinely re-creates the site; the read-back is what
            // makes "the site is absent" below mean something.
            deleteSiteAndExpectItGone(IMPORT_SITE, 'the exported source site must be removed before the import');
        });

        after(() => {
            asRoot();
            deleteSiteQuietly(IMPORT_SITE);
            cleanupStagedDirs({exportDir: IMPORT_EXPORT_DIR, importDir: IMPORT_STAGED_DIR});
        });

        it('refuses a websitesAdmin-only holder, and imports nothing', () => {
            runAs(ALLOWED_USER, importWebsite, {importPath: IMPORT_STAGED_DIR, siteKey: IMPORT_SITE}).then(
                (result: unknown) => {
                    expectAnnotationGatePassed(result);
                    expect(
                        websitesResult(result, 'importWebsite'),
                        'a websitesAdmin holder who is not a server administrator must not import'
                    ).to.eq(false);
                }
            );

            asRoot();
            expectSiteToBeAbsent(IMPORT_SITE, 'must not have been imported — the caller is not a server administrator');
        });

        it('imports that same staged tree for root — the administrator gate is the only difference', () => {
            cy.apollo({
                mutation: importWebsite,
                variables: {importPath: IMPORT_STAGED_DIR, siteKey: IMPORT_SITE}
            }).then((result: unknown) => {
                expect(errorsOf(result), 'root must not be denied').to.have.length(0);
                expect(
                    websitesResult(result, 'importWebsite'),
                    'root clears both gates, so the staged tree must import'
                ).to.eq(true);
            });

            // Without this the refusal above could be explained by broken staging rather than by
            // the gate.
            expectSiteToExist(IMPORT_SITE, 'must exist after root imported the staged tree');
        });
    });

    // SEC-136 — deleteSiteByKey is TARGET-scoped, not merely permission-gated.
    //
    // Until 2.1.0 the only gate on deleteSiteByKey was the root-evaluated `websitesAdmin`
    // annotation, so any holder of the shipped role could destroy ANY site on the instance —
    // including sites it never created and holds no rights on. The gap survived a first
    // remediation pass precisely because no spec asserted it, so this block is the regression
    // lock for the headline primitive.
    //
    // Two assertions are required and neither is sufficient alone:
    //   • the mutation must not report success, AND
    //   • the site must still be there when read back as root.
    // A `false` return on its own cannot distinguish "denied" from "not found", because
    // deleteSiteByKey already returns false for a site that does not exist. And the mutation's
    // return value is not evidence of the persisted state — only the read-back is.
    describe('site deletion is scoped to the caller authority', () => {
        const VICTIM_SITE = 'gewVictimSite';

        // Created as root, so the site is demonstrably not the delegated holder's own.
        beforeEach(() => {
            createSiteAsRoot(VICTIM_SITE, {templateSet: TEMPLATE_SET, title: 'Victim site'});
        });

        afterEach(() => {
            asRoot();
            deleteSiteQuietly(VICTIM_SITE);
        });

        it('refuses deletion of a site the delegated role holder has no authority over', () => {
            runAs(ALLOWED_USER, deleteSiteByKey, {siteKey: VICTIM_SITE}).then((result: unknown) => {
                // The annotation gate is passed — the holder legitimately reaches the resolver.
                // The refusal must come from the target-scoped check inside it.
                expectAnnotationGatePassed(result);
                expect(
                    websitesResult(result, 'deleteSiteByKey'),
                    'a role-only holder must not delete a site it has no authority over'
                ).to.eq(false);
            });

            // Read the repository back as root. This is the assertion that actually proves the
            // site survived; the mutation's return value proves nothing about persisted state.
            asRoot();
            expectSiteToExist(VICTIM_SITE, 'must still exist after the refused deletion');
        });

        // The positive control. Without it, a change that breaks deleteSiteByKey for EVERYONE
        // would leave the test above passing and read as "still secure".
        it('still allows an administrator to delete the same site', () => {
            deleteSiteAndExpectItGone(VICTIM_SITE, 'an administrator must retain the ability to delete a site');
        });
    });

    // SEC-136 — the other half of the delete primitive: a site administrator MUST be able to
    // delete the site they administer.
    //
    // Denial and root-success alone cannot detect a `websitesDelete` that does not work at all.
    // The permission exists in exactly two places (src/main/import/permissions.xml and the site
    // role in src/main/import/roles.xml); a typo in either, or nesting it under `websitesAdmin`
    // (which would turn it into an aggregated sub-privilege), leaves the delegation entirely
    // non-functional with every other spec in this file still green.
    describe('site deletion is delegable to a site administrator', () => {
        const DELETABLE_SITE = 'gewDeletableSite';
        const PROTECTED_SITE = 'gewProtectedSite';
        const DELETE_ADMIN_USER = 'gewDeleteAdminUser';

        before(() => {
            asRoot();
            createUser(DELETE_ADMIN_USER, PASSWORD);
            grantRoles('/', [ROLE_NAME], DELETE_ADMIN_USER, 'USER');
            createSiteAsRoot(DELETABLE_SITE, {templateSet: TEMPLATE_SET});
            createSiteAsRoot(PROTECTED_SITE, {templateSet: TEMPLATE_SET});
            // The site role is granted on ONE of the two sites. The pair is what makes the
            // scoping falsifiable: a `websitesDelete` that is really being satisfied at `/`
            // would let this user delete both.
            grantRoles(`/sites/${DELETABLE_SITE}`, [SITE_ROLE], DELETE_ADMIN_USER, 'USER');
        });

        after(() => {
            asRoot();
            revokeRoles('/', [ROLE_NAME], DELETE_ADMIN_USER, 'USER');
            deleteSiteQuietly(DELETABLE_SITE);
            deleteSiteQuietly(PROTECTED_SITE);
            deleteUser(DELETE_ADMIN_USER);
        });

        // Runs first, while the holder demonstrably DOES hold the site role somewhere: this
        // proves the refusal comes from the target scoping, not from the user having no role.
        it('refuses that administrator on a site they do not administer', () => {
            runAs(DELETE_ADMIN_USER, deleteSiteByKey, {siteKey: PROTECTED_SITE}).then((result: unknown) => {
                expectAnnotationGatePassed(result);
                expect(
                    websitesResult(result, 'deleteSiteByKey'),
                    'websitesDelete on one site must not authorize deleting another'
                ).to.eq(false);
            });

            asRoot();
            expectSiteToExist(PROTECTED_SITE, 'must survive — the caller administers a different site');
        });

        it('allows a site administrator to delete the site they administer', () => {
            runAs(DELETE_ADMIN_USER, deleteSiteByKey, {siteKey: DELETABLE_SITE}).then((result: unknown) => {
                expectAnnotationGatePassed(result);
                expect(
                    websitesResult(result, 'deleteSiteByKey'),
                    'the site role must make deleteSiteByKey usable on the granted site'
                ).to.eq(true);
            });

            asRoot();
            expectSiteToBeAbsent(DELETABLE_SITE, 'must be gone — its site administrator deleted it');
        });
    });
});
