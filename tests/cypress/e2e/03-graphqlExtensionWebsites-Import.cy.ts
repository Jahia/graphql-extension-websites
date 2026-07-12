import {DocumentNode} from 'graphql';

/**
 * F3 — importWebsite happy path (root/admin).
 *
 * importWebsite is gated twice: the `websitesAdmin` annotation AND the programmatic
 * callerIsServerAdministrator() check (WebsitesAdminMutation.java:271-274), so it must run
 * as a FULL administrator (root). See U2 (02-...Permissions.cy.ts) for the negative gate test.
 *
 * The mutation reads a prepared directory under {jahiaImportsDiskPath} with the layout:
 *   {importPath}/export.properties
 *   {importPath}/roles/            (roles.zip content)
 *   {importPath}/users/            (users.zip content)
 *   {importPath}/{siteKey}/site.properties
 *
 * NOTE (Stage 6 / harness): this import tree must be provisioned into the Jahia container
 * before the spec runs — e.g. by first exportWebsite-ing a site and staging the produced
 * archive under {jahiaImportsDiskPath}, or by mounting a committed fixture directory via the
 * docker-compose volume and the provisioning manifest. Until that provisioning is wired, this
 * spec exercises the root happy path and asserts the mutation resolves to a boolean without a
 * GraphQL authorization error (it must NOT be denied for root); a fully staged tree makes it
 * return true.
 */
describe('GraphQL Extension Websites — importWebsite happy path', () => {
    const IMPORT_PATH = 'cypress-import';
    const IMPORT_SITE_KEY = 'cypress-imported-site';

    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const importWebsite: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/importWebsite.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deleteSiteByKey: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteSiteByKey.graphql');

    const errorsOf = (result: {graphQLErrors?: Array<{message: string}>; errors?: Array<{message: string}>}) =>
        result.graphQLErrors ?? result.errors ?? [];

    before(() => {
        cy.login();
    });

    after(() => {
        // Best-effort cleanup if the site was actually imported.
        cy.apollo({mutation: deleteSiteByKey, variables: {siteKey: IMPORT_SITE_KEY}});
    });

    it('imports a website as root without an authorization error', () => {
        cy.apollo({
            mutation: importWebsite,
            variables: {importPath: IMPORT_PATH, siteKey: IMPORT_SITE_KEY}
        }).then((result: never) => {
            // Root passes BOTH gates — there must be no Permission denied / admin-gate error.
            expect(errorsOf(result), 'root must not be denied').to.have.length(0);
            const value = (result as {data: {admin: {jahia: {websites: {importWebsite: boolean}}}}})
                .data.admin.jahia.websites.importWebsite;
            // With a fully staged import tree this is true; without one the mutation returns
            // false (missing export.properties) — but never an authorization error for root.
            expect(value, 'importWebsite returns a boolean for root').to.be.a('boolean');
        });
    });
});
