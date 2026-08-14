import {DocumentNode} from 'graphql';

/**
 * F3 — importWebsite.
 *
 * importWebsite is gated twice: the `websitesAdmin` annotation AND the programmatic
 * callerIsServerAdministrator() check, so it must run as a FULL administrator (root).
 * See U2 (02-...Permissions.cy.ts) for the negative gate test.
 *
 * The mutation reads a prepared directory under {jahiaImportsDiskPath}:
 *   {importPath}/export.properties
 *   {importPath}/roles/
 *   {importPath}/users/
 *   {importPath}/{siteKey}/site.properties
 *
 * That is exactly the layout exportWebsite writes, so the happy path below is a genuine round
 * trip — create, export, stage, delete, import — rather than a committed fixture that would rot
 * against the exporter. Staging between {jahiaVarDiskPath}/exports and {jahiaImportsDiskPath}
 * happens server-side via stageImportTree.groovy, because Cypress runs in its own container and
 * cannot reach Jahia's filesystem.
 */
describe('GraphQL Extension Websites — importWebsite', () => {
    const SITE_KEY = 'cypress-roundtrip-site';
    const EXPORT_DIR = 'cypress-roundtrip-export';
    const IMPORT_DIR = 'cypress-roundtrip-import';
    const MISSING_IMPORT_DIR = 'cypress-import-does-not-exist';

    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const createSiteByKey: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createSiteByKey.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const exportWebsite: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/exportWebsite.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const importWebsite: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/importWebsite.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deleteSiteByKey: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteSiteByKey.graphql');

    const errorsOf = (result: { graphQLErrors?: Array<{ message: string }>; errors?: Array<{ message: string }> }) =>
        result.graphQLErrors ?? result.errors ?? [];

    before(() => {
        cy.login();
    });

    after(() => {
        // Best-effort cleanup — the round trip deletes the site itself, so this normally no-ops.
        cy.apollo({mutation: deleteSiteByKey, variables: {siteKey: SITE_KEY}});
    });

    it('round-trips a site through exportWebsite and importWebsite', () => {
        // Arrange — a real site to export
        cy.apollo({
            mutation: createSiteByKey,
            variables: {
                siteKey: SITE_KEY,
                serverName: 'roundtrip.localhost',
                title: 'Cypress Roundtrip Site',
                templateSet: 'default',
                locale: 'en'
            }
        })
            .its('data.admin.jahia.websites.createSiteByKey')
            .should('eq', true);

        cy.apollo({
            mutation: exportWebsite,
            variables: {siteKey: SITE_KEY, exportPath: EXPORT_DIR, onlyStaging: false}
        })
            .its('data.admin.jahia.websites.exportWebsite')
            .should('eq', true);

        // Stage the export into the imports directory. The script also waits out the
        // @GraphQLAsync export, which returns before the tree is on disk.
        cy.executeGroovy('stageImportTree.groovy', {
            __EXPORT_DIR__: EXPORT_DIR,
            __IMPORT_DIR__: IMPORT_DIR,
            __SITE_KEY__: SITE_KEY
        });

        // Remove the original so the import genuinely re-creates the site rather than
        // silently colliding with one that is already there.
        cy.apollo({mutation: deleteSiteByKey, variables: {siteKey: SITE_KEY}})
            .its('data.admin.jahia.websites.deleteSiteByKey')
            .should('eq', true);

        // Act
        cy.apollo({mutation: importWebsite, variables: {importPath: IMPORT_DIR, siteKey: SITE_KEY}})
            .its('data.admin.jahia.websites.importWebsite')
            .should('eq', true);

        // Assert — the site is back. A successful delete is proof it exists, and doubles as cleanup.
        cy.apollo({mutation: deleteSiteByKey, variables: {siteKey: SITE_KEY}})
            .its('data.admin.jahia.websites.deleteSiteByKey')
            .should('eq', true);
    });

    // U2 (positive half) — root clears BOTH gates. Deliberately points at a path that does not
    // exist so the assertion depends only on authorization, not on fixture state: the mutation
    // must fail as an ordinary false, never as a GraphQL authorization error.
    it('is not denied for root even when the import tree is missing', () => {
        cy.apollo({
            mutation: importWebsite,
            variables: {importPath: MISSING_IMPORT_DIR, siteKey: SITE_KEY}
        }).then((result: never) => {
            expect(errorsOf(result), 'root must not be denied').to.have.length(0);
            const value = (result as { data: { admin: { jahia: { websites: { importWebsite: boolean } } } } }).data
                .admin.jahia.websites.importWebsite;
            expect(value, 'missing import tree returns false, not an error').to.eq(false);
        });
    });
});
