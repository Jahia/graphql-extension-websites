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
});
