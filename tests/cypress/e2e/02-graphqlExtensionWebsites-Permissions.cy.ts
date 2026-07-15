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
        cy.apolloClient(); // reset the current Apollo client back to root
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
});
