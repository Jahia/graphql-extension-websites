import {DocumentNode} from 'graphql';
import {errorMessagesOf, errorsOf, websitesResult} from '../support/graphqlResult';
import {expectModuleStarted, expectWebsitesApiAvailable} from '../support/deploymentSmoke';
import {cleanupStagedDirs, expectExportArtifactPresent, expectVarPathAbsent} from '../support/exportArtifacts';
import {
    createSiteAsRoot,
    deleteSiteAndExpectItGone,
    deleteSiteQuietly,
    expectSiteToExist
} from '../support/siteLifecycle';

describe('GraphQL Extension Websites', () => {
    const TEST_SITE_KEY = 'cypress-test-website';
    // Every site gets its own server name. Jahia requires them to be unique, and reusing one
    // literal across fixtures couples specs that are supposed to be independent.
    const TEST_SITE_SERVER_NAME = 'cypress-test-website.local';
    const TEMPLATE_SET = 'default';
    const OPTIONAL_MODULE = 'siteSettings';
    const SYSTEM_SITE_KEY = 'systemsite';
    const EXPORT_DIR = 'cypress-export';
    const IDEMPOTENT_EXPORT_DIR = 'cypress-export-idempotent';
    // One level up already leaves {jahiaVarDiskPath}/exports. Unlike a deep `../../etc/...` target,
    // this one lands somewhere the test can prove stayed empty.
    const ESCAPING_EXPORT_PATH = '../cypress-escaped-export';
    const ESCAPED_VAR_PATH = 'cypress-escaped-export';

    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const createSiteByKey: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createSiteByKey.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const createSiteByKeyWithModules: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createSiteByKeyWithModules.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deleteSiteByKey: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteSiteByKey.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const exportWebsite: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/exportWebsite.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const exportAllSites: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/exportAllSites.graphql');
    // Flat path, missing the `websites` container — used by the schema-shape guard at the bottom.
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const exportAllSitesFlat: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/exportAllSitesFlat.graphql');

    before(() => {
        cy.login();
        // Fail here, once and legibly, if the module is not deployed. Without this a broken
        // deployment shows up as a wall of unrelated failures — and any spec that only asserts
        // "this call was refused" would pass, because an API that does not exist refuses
        // everything.
        expectWebsitesApiAvailable();
    });

    // Cleanup must not live at the end of the happy path: when an assertion fails the test aborts
    // and the delete never runs, `cypress-test-website` survives, and every later createSiteByKey
    // collides with the existing key and returns false. One real failure then reads as four.
    afterEach(() => {
        deleteSiteQuietly(TEST_SITE_KEY);
    });

    after(() => {
        cleanupStagedDirs({exportDir: EXPORT_DIR});
        cleanupStagedDirs({exportDir: IDEMPOTENT_EXPORT_DIR});
    });

    it('creates a site via GraphQL and returns true', () => {
        cy.apollo({
            mutation: createSiteByKey,
            variables: {
                siteKey: TEST_SITE_KEY,
                serverName: TEST_SITE_SERVER_NAME,
                title: 'Cypress Test Website',
                templateSet: TEMPLATE_SET,
                locale: 'en'
            }
        })
            .its('data.admin.jahia.websites.createSiteByKey')
            .should('eq', true);

        expectSiteToExist(TEST_SITE_KEY, 'must exist after a successful createSiteByKey');
    });

    // Regression: modulesToDeploy was declared String[] in Java, and graphql-java-annotations
    // only converts a GraphQL list when the parameter is a parameterized List<T>. Supplying the
    // argument — even as [] — therefore failed with "argument type mismatch" for EVERY caller,
    // root included, while omitting it worked.
    //
    // Every other spec in this file omits modulesToDeploy, which is exactly why a fully green
    // suite never caught it. Both cases below are needed: the empty list is the minimal input
    // that reproduced the failure, and the populated list is the one operators actually send.
    it('creates a site when modulesToDeploy is supplied as an empty list', () => {
        cy.apollo({
            mutation: createSiteByKeyWithModules,
            variables: {
                siteKey: TEST_SITE_KEY,
                serverName: TEST_SITE_SERVER_NAME,
                title: 'Cypress Test Website',
                templateSet: TEMPLATE_SET,
                modulesToDeploy: [],
                locale: 'en'
            }
        }).then((result: unknown) => {
            expect(
                errorMessagesOf(result),
                'an empty modulesToDeploy must not raise argument type mismatch'
            ).to.not.contain('argument type mismatch');
            expect(websitesResult(result, 'createSiteByKey')).to.eq(true);
        });

        expectSiteToExist(TEST_SITE_KEY, 'must exist after createSiteByKey with an empty module list');
    });

    it('creates a site when modulesToDeploy names an installed module', () => {
        // The argument only exercises the conversion path if the module really is deployable;
        // an absent module would make addSite fail and turn this into a false negative.
        expectModuleStarted(OPTIONAL_MODULE);

        cy.apollo({
            mutation: createSiteByKeyWithModules,
            variables: {
                siteKey: TEST_SITE_KEY,
                serverName: TEST_SITE_SERVER_NAME,
                title: 'Cypress Test Website',
                templateSet: TEMPLATE_SET,
                modulesToDeploy: [OPTIONAL_MODULE],
                locale: 'en'
            }
        })
            .its('data.admin.jahia.websites.createSiteByKey')
            .should('eq', true);

        expectSiteToExist(TEST_SITE_KEY, 'must exist after createSiteByKey with a populated module list');
    });

    it('deletes a site via GraphQL and returns true', () => {
        // Asserted, not fire-and-forget: a silently failed creation would make the deletion below
        // return false for "not found" and the test would fail for a misleading reason.
        createSiteAsRoot(TEST_SITE_KEY, {
            templateSet: TEMPLATE_SET,
            serverName: TEST_SITE_SERVER_NAME,
            title: 'Cypress Test Website'
        });

        deleteSiteAndExpectItGone(TEST_SITE_KEY, 'deleteSiteByKey must report success for an existing site');
    });

    it('returns false when deleting a non-existent site', () => {
        cy.apollo({
            mutation: deleteSiteByKey,
            variables: {siteKey: 'non-existent-cypress-site-12345'}
        })
            .its('data.admin.jahia.websites.deleteSiteByKey')
            .should('eq', false);
    });

    it('exports a website via GraphQL and writes the archive to disk', () => {
        cleanupStagedDirs({exportDir: EXPORT_DIR});

        cy.apollo({
            mutation: exportWebsite,
            variables: {
                siteKey: SYSTEM_SITE_KEY,
                exportPath: EXPORT_DIR,
                onlyStaging: false
            }
        })
            .its('data.admin.jahia.websites.exportWebsite')
            .should('eq', true);

        // The boolean only says the mutation was accepted; exportWebsite is @GraphQLAsync, so the
        // archive is what proves the export actually ran.
        expectExportArtifactPresent(EXPORT_DIR, SYSTEM_SITE_KEY);
    });

    it('returns AWS_S3_BUCKET_NOT_CONFIGURED when exportAllSites is called without AWS configuration', () => {
        cy.apollo({mutation: exportAllSites})
            .its('data.admin.jahia.websites.exportAllSites')
            .should('eq', 'AWS_S3_BUCKET_NOT_CONFIGURED');
    });

    // Path confinement for exportWebsite: a traversal path is rejected by resolveContainedOrNull
    // (which delegates to PathSecurity.resolveContained) and the mutation returns false without
    // writing anything outside {jahiaVarDiskPath}/exports. Both halves matter — `false` alone
    // cannot distinguish "refused" from "wrote it somewhere and failed afterwards".
    it('rejects an exportWebsite path that escapes the exports directory', () => {
        cy.apollo({
            mutation: exportWebsite,
            variables: {
                siteKey: SYSTEM_SITE_KEY,
                exportPath: ESCAPING_EXPORT_PATH,
                onlyStaging: false
            }
        })
            .its('data.admin.jahia.websites.exportWebsite')
            .should('eq', false);

        expectVarPathAbsent(ESCAPED_VAR_PATH);
    });

    // Repeated export to the same path is idempotent: the prior export is removed by
    // deleteExportArtifact before the new one runs, so the second call does not hit Jahia's
    // "server directory is not empty" rejection.
    //
    // The two calls are serialised on the on-disk result rather than fired back to back. Because
    // exportWebsite is @GraphQLAsync, two overlapping calls race each other — the second call's
    // cleanup can delete what the first is still writing — and, worse, the original form of this
    // test could pass without either export having produced anything at all.
    it('allows exporting to the same path twice (idempotent delete of the prior export)', () => {
        const variables = {
            siteKey: SYSTEM_SITE_KEY,
            exportPath: IDEMPOTENT_EXPORT_DIR,
            onlyStaging: false
        };

        cleanupStagedDirs({exportDir: IDEMPOTENT_EXPORT_DIR});

        cy.apollo({mutation: exportWebsite, variables})
            .its('data.admin.jahia.websites.exportWebsite')
            .should('eq', true);
        expectExportArtifactPresent(IDEMPOTENT_EXPORT_DIR, SYSTEM_SITE_KEY);

        // Now the directory is genuinely populated, which is the precondition that used to fail.
        cy.apollo({mutation: exportWebsite, variables})
            .its('data.admin.jahia.websites.exportWebsite')
            .should('eq', true);
        expectExportArtifactPresent(IDEMPOTENT_EXPORT_DIR, SYSTEM_SITE_KEY);
    });

    // Schema-shape guard. The real path is admin.jahia.websites.exportAllSites; the flat
    // admin.jahia.exportAllSites (no `websites` container) does not exist and must fail schema
    // validation. This pins the shape against a future change that drops the container — it is
    // not a record of a documentation defect: the module docs state the nested path, and AGENTS.md
    // spells out that a flat `admin.jahia.<operation>` does NOT resolve.
    //
    // The positive half lives in `before()` (expectWebsitesApiAvailable), so this cannot pass
    // merely because the module is absent and every path is undefined.
    it('rejects the flat exportAllSites path — the websites container is required', () => {
        cy.apollo({mutation: exportAllSitesFlat}).then((result: unknown) => {
            const messages = errorMessagesOf(result).toLowerCase();
            expect(errorsOf(result), 'schema-validation errors for the flat path').to.have.length.greaterThan(0);
            // Must be rejected as *this field being undefined*, not by any error that happens to
            // mention validation — the previous /exportallsites|undefined|validation|field/
            // alternation matched almost every conceivable GraphQL error message.
            expect(messages, 'the error must name the offending field').to.contain('exportallsites');
            expect(messages, 'the error must be an undefined-field validation error').to.contain('undefined');
        });
    });
});
