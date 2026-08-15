import {DocumentNode} from 'graphql';
import {errorsOf, websitesResult} from '../support/graphqlResult';
import {expectWebsitesApiAvailable} from '../support/deploymentSmoke';
import {cleanupStagedDirs} from '../support/exportArtifacts';
import {deleteSiteAndExpectItGone, deleteSiteQuietly, expectSiteToExist} from '../support/siteLifecycle';

/**
 * The importWebsite round trip.
 *
 * importWebsite is gated twice: the `websitesAdmin` annotation AND the programmatic
 * callerIsServerAdministrator() check, so it must run as a FULL administrator (root). The
 * negative half of that gate lives in 02-...Permissions.cy.ts, where it is exercised against a
 * genuinely staged tree — a caller who cleared the gate would really import, so the refusal is
 * falsifiable there.
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

    before(() => {
        cy.login();
        expectWebsitesApiAvailable();
    });

    after(() => {
        // Best-effort cleanup — the round trip deletes the site itself, so this normally no-ops.
        // It still has to run: a surviving site collides with the next run's createSiteByKey.
        deleteSiteQuietly(SITE_KEY);
        cleanupStagedDirs({exportDir: EXPORT_DIR, importDir: IMPORT_DIR});
    });

    it('round-trips a site through exportWebsite and importWebsite', () => {
        // Arrange — a real site to export, and no leftovers from an earlier run that could make
        // the staging step succeed against a stale tree.
        cleanupStagedDirs({exportDir: EXPORT_DIR, importDir: IMPORT_DIR});
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
        //
        // Nothing is pruned here: this spec IS the full round trip, so it deliberately stages —
        // and imports — `users/` and `roles/` exactly as exportWebsite wrote them. The
        // authorization spec (02) prunes them instead, because rewriting instance-wide /users and
        // /roles is not a side effect an authorization test should have.
        cy.executeGroovy('stageImportTree.groovy', {
            __EXPORT_DIR__: EXPORT_DIR,
            __IMPORT_DIR__: IMPORT_DIR,
            __SITE_KEY__: SITE_KEY,
            __PRUNE_DIRS__: ''
        });

        // Remove the original so the import genuinely re-creates the site rather than
        // silently colliding with one that is already there. The read-back is what proves the
        // deletion happened: `true` is the mutation's opinion, not the repository's.
        deleteSiteAndExpectItGone(SITE_KEY, 'the exported source site must be removed before the import');

        // Act
        cy.apollo({mutation: importWebsite, variables: {importPath: IMPORT_DIR, siteKey: SITE_KEY}})
            .its('data.admin.jahia.websites.importWebsite')
            .should('eq', true);

        // Assert — the site is back in the repository...
        expectSiteToExist(SITE_KEY, 'must exist after the round trip');
        // ...and removing it again both proves it was a real site and doubles as cleanup.
        deleteSiteAndExpectItGone(SITE_KEY, 'the re-imported site must be deletable');
    });

    // Root clears BOTH gates, so a missing tree must fail as an ordinary `false` rather than as a
    // GraphQL authorization error. Note what this does NOT test: because a nonexistent path also
    // yields `false` (readExportProperties hits FileNotFoundException and returns null), the same
    // outcome would appear with the administrator gate removed. The gate itself is pinned in
    // 02-...Permissions.cy.ts against a real staged tree.
    it('is not denied for root even when the import tree is missing', () => {
        cy.apollo({
            mutation: importWebsite,
            variables: {importPath: MISSING_IMPORT_DIR, siteKey: SITE_KEY}
        }).then((result: unknown) => {
            expect(errorsOf(result), 'root must not be denied').to.have.length(0);
            expect(websitesResult(result, 'importWebsite'), 'missing import tree returns false, not an error').to.eq(
                false
            );
        });
    });
});
