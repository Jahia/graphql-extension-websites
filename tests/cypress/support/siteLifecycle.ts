import {DocumentNode} from 'graphql';
import {errorsOf, nodeByPathOf, websitesResult} from './graphqlResult';

/**
 * Site setup, teardown and read-back shared by every spec.
 *
 * Two rules are encoded here and must not be relaxed:
 *
 *  1. **Setup is asserted.** An unasserted `createSiteByKey` makes a security spec pass for the
 *     wrong reason: if creation silently fails, `exportWebsite` / `deleteSiteByKey` hit
 *     `site == null` and return `false` — exactly the value the denial spec expects, so it goes
 *     green having tested nothing.
 *  2. **A boolean return is not evidence of persisted state.** `deleteSiteByKey` returns `false`
 *     both for "denied" and for "no such site", and `true` only claims the mutation thought it
 *     succeeded. Only the JCR read-back proves what actually happened, so every authorization
 *     assertion pairs the return value with `expectSiteToExist` / `expectSiteToBeAbsent`.
 */

// eslint-disable-next-line @typescript-eslint/no-var-requires
const createSiteByKey: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createSiteByKey.graphql');
// eslint-disable-next-line @typescript-eslint/no-var-requires
const deleteSiteByKey: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteSiteByKey.graphql');
// eslint-disable-next-line @typescript-eslint/no-var-requires
const siteExists: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/siteExists.graphql');

export interface SiteFixtureOptions {
    /** Required: an uninstalled template set makes `addSite` throw and the mutation return false. */
    templateSet: string
    /**
     * Defaults to `<siteKey>.local`. Server names must be unique across sites that exist at the
     * same time, so fixtures never share one.
     */
    serverName?: string
    title?: string
    locale?: string
}

export const sitePathOf = (siteKey: string): string => `/sites/${siteKey}`;

/** Reads `/sites/<siteKey>` back as the current caller, tolerating the "missing node" error. */
export const readSiteNode = (siteKey: string): Cypress.Chainable =>
    cy.apollo({query: siteExists, variables: {path: sitePathOf(siteKey)}, errorPolicy: 'all'});

export const expectSiteToExist = (siteKey: string, message: string): void => {
    readSiteNode(siteKey).then((result: unknown) => {
        expect(nodeByPathOf(result), `${sitePathOf(siteKey)}: ${message}`).to.exist;
    });
};

export const expectSiteToBeAbsent = (siteKey: string, message: string): void => {
    readSiteNode(siteKey).then((result: unknown) => {
        expect(nodeByPathOf(result), `${sitePathOf(siteKey)}: ${message}`).to.not.exist;
    });
};

/**
 * Creates a site as the currently authenticated caller and asserts it worked. Call it from every
 * setup hook — see rule 1 above.
 */
export const createSiteAsRoot = (siteKey: string, options: SiteFixtureOptions): void => {
    cy.apollo({
        mutation: createSiteByKey,
        variables: {
            siteKey,
            serverName: options.serverName ?? `${siteKey}.local`,
            title: options.title ?? siteKey,
            templateSet: options.templateSet,
            locale: options.locale ?? 'en'
        }
    }).then((result: unknown) => {
        expect(errorsOf(result), `setup: creating '${siteKey}' must not raise a GraphQL error`).to.have.length(0);
        expect(websitesResult(result, 'createSiteByKey'), `setup: site '${siteKey}' must be created`).to.eq(true);
    });
};

/**
 * Best-effort teardown: the site may legitimately be gone already (a test deleted it, or an
 * earlier assertion aborted before it was created), so nothing is asserted. Cleanup must never be
 * the thing that fails a run, and must never be skipped either — a surviving site collides with
 * the next `createSiteByKey` and turns one real failure into a cascade.
 */
export const deleteSiteQuietly = (siteKey: string): void => {
    cy.apollo({mutation: deleteSiteByKey, variables: {siteKey}});
};

/** Deletes a site and proves it is gone, for the positive-control specs. */
export const deleteSiteAndExpectItGone = (siteKey: string, message: string): void => {
    cy.apollo({mutation: deleteSiteByKey, variables: {siteKey}}).then((result: unknown) => {
        expect(errorsOf(result), `deleting '${siteKey}' must not raise a GraphQL error`).to.have.length(0);
        expect(websitesResult(result, 'deleteSiteByKey'), message).to.eq(true);
    });
    expectSiteToBeAbsent(siteKey, 'must be gone after a successful deleteSiteByKey');
};
