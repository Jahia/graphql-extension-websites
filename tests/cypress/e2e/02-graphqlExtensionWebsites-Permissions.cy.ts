import {DocumentNode} from 'graphql';
import {createUser, deleteUser, grantRoles} from '@jahia/cypress';

/**
 * Regression tests for the fine-grained `websitesAdmin` permission.
 *
 * These guard against the gate being silently removed or mismatched across the stack:
 *  - Backend: every site-lifecycle mutation in `WebsitesMutation` is annotated with
 *    `@GraphQLRequiresPermission("websitesAdmin")`, enforced by the DXM provider as a
 *    `session.getNode("/").hasPermission("websitesAdmin")` (root-node ACL) check.
 *  - RBAC content: the module ships the assignable `graphql-extension-websites-administrator`
 *    role (src/main/import/roles.xml). Because these mutations are nested under the DXM
 *    `admin { jahia { ... } }` wrapper, traversing to the gated field requires three
 *    fine-grained permissions in total (none of which is the `admin` role):
 *      • `jcr:read_default`     → satisfies the `admin` field's `@GraphQLRequiresPermission("jcr:read/jcr:system")`
 *      • `graphqlAdminMutation` → satisfies the `admin.jahia` field's `@GraphQLRequiresPermission("graphqlAdminMutation")`
 *      • `websitesAdmin`        → satisfies the mutation's own `@GraphQLRequiresPermission("websitesAdmin")`
 *    Omitting any one of them fails the gate on the corresponding field.
 *
 * This module is API-only (no admin UI), so only the GraphQL authorization is asserted.
 *
 * The "allowed" user is granted that single role and nothing else — never the `admin`
 * permission — so the tests prove fine-grained granularity, not merely that a full
 * administrator can pass.
 *
 * Safe gated op: `exportAllSites` is used for the allow path because in CI (no AWS S3
 * configured) it is non-destructive — it builds an export to a temp file that is always
 * removed in a `finally`, never touches site content, and returns the benign
 * `AWS_S3_BUCKET_NOT_CONFIGURED` result instead of uploading anything.
 */
describe('GraphQL Extension Websites — permission enforcement', () => {
    const ROLE_NAME = 'graphql-extension-websites-administrator';
    const DENIED_USER = 'gewDeniedUser';
    const ALLOWED_USER = 'gewAllowedUser';
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
        // The annotation resolves the permission on the JCR root node, so grant the
        // module-shipped single-permission role on `/`.
        grantRoles('/', [ROLE_NAME], ALLOWED_USER, 'USER');
    });

    after(() => {
        cy.apolloClient(); // Reset the current Apollo client back to root
        cy.login();
        deleteUser(DENIED_USER);
        deleteUser(ALLOWED_USER);
    });

    describe('GraphQL API authorization', () => {
        it('denies the gated mutation for a user without the permission', () => {
            exportAllSitesAs(DENIED_USER).then((result: never) => {
                const errs = errorsOf(result);
                expect(errs, 'denial errors').to.have.length.greaterThan(0);
                expect(errs.map((e: {message: string}) => e.message).join(' ')).to.contain('Permission denied');
            });
        });

        it('allows the gated mutation for a user granted only the module permission', () => {
            exportAllSitesAs(ALLOWED_USER).then((result: never) => {
                expect(errorsOf(result), 'should have no errors').to.have.length(0);
                expect((result as {data: {admin: {jahia: {websites: {exportAllSites: string}}}}}).data.admin.jahia.websites.exportAllSites)
                    .to.eq('AWS_S3_BUCKET_NOT_CONFIGURED');
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
