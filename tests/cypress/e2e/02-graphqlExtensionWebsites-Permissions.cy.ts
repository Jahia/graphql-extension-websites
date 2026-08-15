import {DocumentNode} from 'graphql';
import {createUser, deleteUser, grantRoles} from '@jahia/cypress';

/**
 * Regression tests for the fine-grained `websitesAdmin` permission.
 *
 * These guard against the gates being silently removed or mismatched across the stack:
 *  - Backend: each site-lifecycle mutation carries its OWN `@GraphQLRequiresPermission`,
 *    enforced by the DXM provider as a `session.getNode("/").hasPermission(perm)` check
 *    (root-node ACL):
 *      • `createSiteByKey` → `websitesCreate`
 *      • `exportWebsite`   → `websitesExport`
 *      • `exportAllSites`  → `websitesExportAll`
 *      • `deleteSiteByKey` → `websitesAdmin` (coarse; the real gate is the target-scoped
 *        `websitesDelete` check inside the method — see the SEC-136 block below)
 *      • `importWebsite`   → `websitesAdmin` (coarse; the real gate is the server-administrator
 *        check inside the method)
 *  - RBAC content: the module ships the assignable `graphql-extension-websites-administrator`
 *    role (src/main/import/roles.xml). Because these mutations are nested under the DXM
 *    `admin { jahia { ... } }` wrapper, reaching a gated field also requires:
 *      • `jcr:read_default`     → satisfies the `admin` field's `@GraphQLRequiresPermission("jcr:read/jcr:system")`
 *      • `graphqlAdminMutation` → satisfies the `admin.jahia` field's `@GraphQLRequiresPermission("graphqlAdminMutation")`
 *    Omitting any one of them fails the gate on the corresponding field.
 *
 * This module is API-only (no admin UI), so only the GraphQL authorization is asserted.
 *
 * The "allowed" user is granted that single role and nothing else — never the `admin`
 * permission — so the tests prove fine-grained granularity, not merely that a full
 * administrator can pass.
 *
 * Safe gated op: `exportAllSites` is used for the annotation-gate allow path because it is
 * non-destructive — it never touches site content, and as of §4.3 it self-aborts with
 * `NOT_SERVER_ADMINISTRATOR` before doing any work when the caller is not a server admin.
 */
describe('GraphQL Extension Websites — permission enforcement', () => {
    const ROLE_NAME = 'graphql-extension-websites-administrator';
    const DENIED_USER = 'gewDeniedUser';
    const ALLOWED_USER = 'gewAllowedUser';
    // Holds a custom role carrying websitesCreate but NOT websitesExportAll — the user that
    // makes the per-operation permission split falsifiable.
    const CREATE_ONLY_USER = 'gewCreateOnlyUser';
    const CREATE_ONLY_ROLE = 'gew-test-create-only';
    const PASSWORD = 'GewPerm9PwdTest';

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
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const siteExists: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/siteExists.graphql');

    const errorsOf = (result: {graphQLErrors?: Array<{message: string}>; errors?: Array<{message: string}>}) =>
        result.graphQLErrors ?? result.errors ?? [];

    const runAs = (username: string, mutation: DocumentNode, variables?: Record<string, unknown>) => {
        cy.apolloClient({username, password: PASSWORD});
        return cy.apollo({mutation, variables});
    };

    const exportAllSitesAs = (username: string) => runAs(username, exportAllSites);

    before(() => {
        cy.login();
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
        cy.apolloClient(); // Reset the current Apollo client back to root
        cy.login();
        deleteUser(DENIED_USER);
        deleteUser(ALLOWED_USER);
        deleteUser(CREATE_ONLY_USER);
    });

    describe('GraphQL API authorization', () => {
        it('denies the gated mutation for a user without the permission', () => {
            exportAllSitesAs(DENIED_USER).then((result: never) => {
                const errs = errorsOf(result);
                expect(errs, 'denial errors').to.have.length.greaterThan(0);
                expect(errs.map((e: {message: string}) => e.message).join(' ')).to.contain('Permission denied');
            });
        });

        // The holder passes the `websitesExportAll` annotation gate — no "Permission denied" —
        // and is then refused by the in-body administrator gate added in §4.3. Asserting the
        // enum value rather than an error is what distinguishes "reached the resolver and was
        // refused there" from "never got past the annotation", which is the whole point of
        // having two layers.
        it('allows the gated mutation through the annotation, then self-aborts for a non-administrator', () => {
            exportAllSitesAs(ALLOWED_USER).then((result: never) => {
                expect(errorsOf(result), 'annotation gate is passed — no Permission denied').to.have.length(0);
                expect((result as {data: {admin: {jahia: {websites: {exportAllSites: string}}}}}).data.admin.jahia.websites.exportAllSites)
                    .to.eq('NOT_SERVER_ADMINISTRATOR');
            });
        });
    });

    // F7-NEW-a — the `websitesAdmin` annotation gate fires on EVERY mutation field, not just
    // exportAllSites. A user with no role is denied on each of the four other mutations
    // (WebsitesAdminMutation.java:92/137/187/264) before the body runs.
    const deniedCases: Array<{name: string; mutation: DocumentNode; variables: Record<string, unknown>}> = [
        {name: 'createSiteByKey', mutation: createSiteByKey, variables: {siteKey: 'denied-x', serverName: 'localhost', title: 'x', templateSet: 'default', locale: 'en'}},
        {name: 'deleteSiteByKey', mutation: deleteSiteByKey, variables: {siteKey: 'denied-x'}},
        {name: 'exportWebsite', mutation: exportWebsite, variables: {siteKey: 'systemsite', exportPath: 'denied-export', onlyStaging: false}},
        {name: 'importWebsite', mutation: importWebsite, variables: {importPath: 'denied', siteKey: 'denied-x'}}
    ];

    deniedCases.forEach(({name, mutation, variables}) => {
        it(`denies ${name} for a user without the permission`, () => {
            runAs(DENIED_USER, mutation, variables).then((result: never) => {
                const errs = errorsOf(result);
                expect(errs, `${name} denial errors`).to.have.length.greaterThan(0);
                expect(errs.map((e: {message: string}) => e.message).join(' ')).to.contain('Permission denied');
            });
        });
    });

    // U2 — importWebsite has a SECOND gate beyond the websitesAdmin annotation:
    // callerIsServerAdministrator() (WebsitesAdminMutation.java:271-274) requires `admin` at
    // the JCR root. The shipped role grants websitesAdmin but NOT admin, so the ALLOWED_USER
    // passes the annotation gate (no "Permission denied") yet the field self-aborts and
    // returns false — nothing is imported. This is the key security regression guard: if the
    // second gate is removed, a websitesAdmin-only holder would proceed past false.
    it('returns false (not Permission denied) for importWebsite by a websitesAdmin-only holder', () => {
        runAs(ALLOWED_USER, importWebsite, {importPath: 'anything', siteKey: 'x'}).then((result: never) => {
            expect(errorsOf(result), 'annotation gate is passed — no Permission denied').to.have.length(0);
            expect((result as {data: {admin: {jahia: {websites: {importWebsite: boolean}}}}}).data.admin.jahia.websites.importWebsite)
                .to.eq(false);
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

        afterEach(() => {
            cy.apolloClient();
            cy.login();
            cy.apollo({mutation: deleteSiteByKey, variables: {siteKey: CREATE_ONLY_SITE}});
        });

        it('allows createSiteByKey for a holder of websitesCreate alone', () => {
            runAs(CREATE_ONLY_USER, createSiteByKey, {
                siteKey: CREATE_ONLY_SITE,
                serverName: `${CREATE_ONLY_SITE}.local`,
                title: 'Create only',
                templateSet: 'templates-system',
                locale: 'en'
            }).then((result: never) => {
                expect(errorsOf(result), 'websitesCreate must be sufficient to create')
                    .to.have.length(0);
                expect((result as {data: {admin: {jahia: {websites: {createSiteByKey: boolean}}}}})
                    .data.admin.jahia.websites.createSiteByKey).to.eq(true);
            });
        });

        it('denies exportAllSites for that same holder — creation does not imply bulk export', () => {
            exportAllSitesAs(CREATE_ONLY_USER).then((result: never) => {
                const errs = errorsOf(result);
                expect(errs, 'websitesCreate must NOT satisfy websitesExportAll')
                    .to.have.length.greaterThan(0);
                expect(errs.map((e: {message: string}) => e.message).join(' '))
                    .to.contain('Permission denied');
            });
        });

        it('denies exportWebsite for that same holder — creation does not imply site export', () => {
            runAs(CREATE_ONLY_USER, exportWebsite, {
                siteKey: 'systemsite', exportPath: 'create-only-export', onlyStaging: false
            }).then((result: never) => {
                const errs = errorsOf(result);
                expect(errs, 'websitesCreate must NOT satisfy websitesExport')
                    .to.have.length.greaterThan(0);
                expect(errs.map((e: {message: string}) => e.message).join(' '))
                    .to.contain('Permission denied');
            });
        });
    });

    // SEC-136 §4.3 — exportWebsite is TARGET-scoped, so a site administrator can export the site
    // they administer and nothing else, while a server administrator can export anything.
    //
    // This is the pair of properties the §4.3 change exists to deliver. The "own site" case must
    // pass or the delegation is useless; the "other site" case must fail or the scoping is
    // decorative. Testing only one of them would let a broken implementation look correct.
    describe('site export is scoped to the caller authority', () => {
        const OWNED_SITE = 'gewOwnedSite';
        const OTHER_SITE = 'gewOtherSite';
        const SITE_ROLE = 'graphql-extension-websites-site-administrator';
        const SITE_ADMIN_USER = 'gewSiteAdminUser';

        before(() => {
            cy.apolloClient();
            cy.login();
            createUser(SITE_ADMIN_USER, PASSWORD);
            // Needs the server role to reach the API at all, plus the site role on ONE site.
            grantRoles('/', [ROLE_NAME], SITE_ADMIN_USER, 'USER');
            [OWNED_SITE, OTHER_SITE].forEach(key => {
                cy.apollo({
                    mutation: createSiteByKey,
                    variables: {
                        siteKey: key,
                        serverName: `${key}.local`,
                        title: key,
                        templateSet: 'templates-system',
                        locale: 'en'
                    }
                });
            });
            grantRoles(`/sites/${OWNED_SITE}`, [SITE_ROLE], SITE_ADMIN_USER, 'USER');
        });

        after(() => {
            cy.apolloClient();
            cy.login();
            [OWNED_SITE, OTHER_SITE].forEach(key => {
                cy.apollo({mutation: deleteSiteByKey, variables: {siteKey: key}});
            });
            deleteUser(SITE_ADMIN_USER);
        });

        it('allows a site administrator to export the site they administer', () => {
            runAs(SITE_ADMIN_USER, exportWebsite, {
                siteKey: OWNED_SITE, exportPath: 'gew-owned-export', onlyStaging: true
            }).then((result: never) => {
                expect(errorsOf(result), 'annotation gate is passed — no Permission denied')
                    .to.have.length(0);
                expect((result as {data: {admin: {jahia: {websites: {exportWebsite: boolean}}}}})
                    .data.admin.jahia.websites.exportWebsite,
                'the site role must make exportWebsite usable on the granted site').to.eq(true);
            });
        });

        it('refuses that same administrator on a site they do not administer', () => {
            runAs(SITE_ADMIN_USER, exportWebsite, {
                siteKey: OTHER_SITE, exportPath: 'gew-other-export', onlyStaging: true
            }).then((result: never) => {
                // The annotation still passes — the refusal must come from the target-scoped
                // check, not from the coarse gate.
                expect(errorsOf(result), 'annotation gate is passed — no Permission denied')
                    .to.have.length(0);
                expect((result as {data: {admin: {jahia: {websites: {exportWebsite: boolean}}}}})
                    .data.admin.jahia.websites.exportWebsite,
                'a site administrator must not export a site they hold no rights on').to.eq(false);
            });
        });

        it('still allows an administrator to export either site', () => {
            cy.apolloClient();
            cy.login();
            cy.apollo({
                mutation: exportWebsite,
                variables: {siteKey: OTHER_SITE, exportPath: 'gew-root-export', onlyStaging: true}
            }).then((result: never) => {
                expect(errorsOf(result), 'root must not be denied').to.have.length(0);
                expect((result as {data: {admin: {jahia: {websites: {exportWebsite: boolean}}}}})
                    .data.admin.jahia.websites.exportWebsite).to.eq(true);
            });
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
        const VICTIM_PATH = `/sites/${VICTIM_SITE}`;

        // Created as root, so the site is demonstrably not the delegated holder's own.
        beforeEach(() => {
            cy.apolloClient();
            cy.login();
            cy.apollo({
                mutation: createSiteByKey,
                variables: {
                    siteKey: VICTIM_SITE,
                    serverName: `${VICTIM_SITE}.local`,
                    title: 'Victim site',
                    templateSet: 'templates-system',
                    locale: 'en'
                }
            });
        });

        afterEach(() => {
            cy.apolloClient();
            cy.login();
            cy.apollo({mutation: deleteSiteByKey, variables: {siteKey: VICTIM_SITE}});
        });

        it('refuses deletion of a site the delegated role holder has no authority over', () => {
            runAs(ALLOWED_USER, deleteSiteByKey, {siteKey: VICTIM_SITE}).then((result: never) => {
                // The annotation gate is passed — the holder legitimately reaches the resolver.
                // The refusal must come from the target-scoped check inside it.
                expect(errorsOf(result), 'annotation gate is passed — no Permission denied')
                    .to.have.length(0);
                expect(
                    (result as {data: {admin: {jahia: {websites: {deleteSiteByKey: boolean}}}}})
                        .data.admin.jahia.websites.deleteSiteByKey,
                    'a role-only holder must not delete a site it has no authority over'
                ).to.eq(false);
            });

            // Read the repository back as root. This is the assertion that actually proves the
            // site survived; the mutation's return value proves nothing about persisted state.
            cy.apolloClient();
            cy.login();
            cy.apollo({query: siteExists, variables: {path: VICTIM_PATH}, errorPolicy: 'all'})
                .then((result: never) => {
                    const node = (result as {data?: {jcr?: {nodeByPath?: {uuid: string} | null}}})
                        .data?.jcr?.nodeByPath;
                    expect(node, `${VICTIM_PATH} must still exist after the refused deletion`)
                        .to.not.eq(null);
                    expect(node, `${VICTIM_PATH} must still exist after the refused deletion`)
                        .to.not.eq(undefined);
                });
        });

        // The positive control. Without it, a change that breaks deleteSiteByKey for EVERYONE
        // would leave the test above passing and read as "still secure".
        it('still allows an administrator to delete the same site', () => {
            cy.apolloClient();
            cy.login();
            cy.apollo({mutation: deleteSiteByKey, variables: {siteKey: VICTIM_SITE}})
                .then((result: never) => {
                    expect(errorsOf(result), 'root must not be denied').to.have.length(0);
                    expect(
                        (result as {data: {admin: {jahia: {websites: {deleteSiteByKey: boolean}}}}})
                            .data.admin.jahia.websites.deleteSiteByKey,
                        'an administrator must retain the ability to delete a site'
                    ).to.eq(true);
                });
        });
    });
});
