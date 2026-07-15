import {DocumentNode} from 'graphql';

describe('GraphQL Extension Websites', () => {
    const TEST_SITE_KEY = 'cypress-test-website';

    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const createSiteByKey: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createSiteByKey.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deleteSiteByKey: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteSiteByKey.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const exportWebsite: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/exportWebsite.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const exportAllSites: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/exportAllSites.graphql');
    // Flat (documented-but-wrong) path, missing the `websites` container — used by the D1 schema-shape guard.
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const exportAllSitesFlat: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/exportAllSitesFlat.graphql');

    const errorsOf = (result: {graphQLErrors?: Array<{message: string}>; errors?: Array<{message: string}>}) =>
        result.graphQLErrors ?? result.errors ?? [];

    before(() => {
        cy.login();
    });

    it('creates a site via GraphQL and returns true', () => {
        cy.apollo({
            mutation: createSiteByKey,
            variables: {
                siteKey: TEST_SITE_KEY,
                serverName: 'localhost',
                title: 'Cypress Test Website',
                templateSet: 'default',
                locale: 'en'
            }
        })
            .its('data.admin.jahia.websites.createSiteByKey')
            .should('eq', true);

        // Cleanup
        cy.apollo({mutation: deleteSiteByKey, variables: {siteKey: TEST_SITE_KEY}});
    });

    it('deletes a site via GraphQL and returns true', () => {
        // First create the site to be deleted
        cy.apollo({
            mutation: createSiteByKey,
            variables: {
                siteKey: TEST_SITE_KEY,
                serverName: 'localhost',
                title: 'Cypress Test Website',
                templateSet: 'default',
                locale: 'en'
            }
        });

        cy.apollo({
            mutation: deleteSiteByKey,
            variables: {siteKey: TEST_SITE_KEY}
        })
            .its('data.admin.jahia.websites.deleteSiteByKey')
            .should('eq', true);
    });

    it('returns false when deleting a non-existent site', () => {
        cy.apollo({
            mutation: deleteSiteByKey,
            variables: {siteKey: 'non-existent-cypress-site-12345'}
        })
            .its('data.admin.jahia.websites.deleteSiteByKey')
            .should('eq', false);
    });

    it('exports a website via GraphQL and returns true', () => {
        cy.apollo({
            mutation: exportWebsite,
            variables: {
                siteKey: 'systemsite',
                exportPath: 'cypress-export',
                onlyStaging: false
            }
        })
            .its('data.admin.jahia.websites.exportWebsite')
            .should('eq', true);
    });

    it('returns AWS_S3_BUCKET_NOT_CONFIGURED when exportAllSites is called without AWS configuration', () => {
        cy.apollo({mutation: exportAllSites})
            .its('data.admin.jahia.websites.exportAllSites')
            .should('eq', 'AWS_S3_BUCKET_NOT_CONFIGURED');
    });

    // U4 — exportWebsite path confinement. A traversal path is rejected by
    // resolveContainedOrNull (WebsitesAdminMutation.java:196-199) and the mutation returns
    // false without writing anything outside {jahiaVarDiskPath}/exports.
    it('rejects an exportWebsite path that escapes the exports directory', () => {
        cy.apollo({
            mutation: exportWebsite,
            variables: {
                siteKey: 'systemsite',
                exportPath: '../../etc/evil',
                onlyStaging: false
            }
        })
            .its('data.admin.jahia.websites.exportWebsite')
            .should('eq', false);
    });

    // U4 — repeated export to the same path is idempotent: the prior export is deleted via
    // FileUtils.deleteQuietly (WebsitesAdminMutation.java:216) so the second call does not 403.
    it('allows exporting to the same path twice (idempotent delete of the prior export)', () => {
        const vars = {siteKey: 'systemsite', exportPath: 'cypress-export-idempotent', onlyStaging: false};
        cy.apollo({mutation: exportWebsite, variables: vars})
            .its('data.admin.jahia.websites.exportWebsite')
            .should('eq', true);
        cy.apollo({mutation: exportWebsite, variables: vars})
            .its('data.admin.jahia.websites.exportWebsite')
            .should('eq', true);
    });

    // D1 — schema-shape guard. The real path is admin.jahia.websites.exportAllSites (U1); the
    // flat path documented in README/AGENTS.md (admin.jahia.exportAllSites, no `websites`
    // container) does NOT exist and must fail schema validation. Pins the real shape and
    // documents the doc error for the Stage-7 doc fix.
    it('rejects the flat (documented) exportAllSites path — websites container is required', () => {
        cy.apollo({mutation: exportAllSitesFlat}).then((result: never) => {
            const errs = errorsOf(result);
            expect(errs, 'schema-validation errors for the flat path').to.have.length.greaterThan(0);
            expect(errs.map((e: {message: string}) => e.message).join(' ').toLowerCase())
                .to.match(/exportallsites|undefined|validation|field/);
        });
    });
});
