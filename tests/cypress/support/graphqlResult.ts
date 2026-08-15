/**
 * Typed access to whatever `cy.apollo` yields.
 *
 * `cy.apollo` resolves with EITHER a GraphQL response (`{data, errors?}`) OR — when the client
 * rejects — the ApolloError itself (`{graphQLErrors}`), because the command catches the rejection
 * and passes the error object through as the subject. A caller can therefore never assume `data`
 * is present, which is why every accessor below is optional-chained: an error-shaped result must
 * surface as a readable assertion failure ("expected undefined to equal true"), not as a raw
 * `TypeError: Cannot read properties of undefined`.
 *
 * Specs used to annotate the subject as `never` and cast it inline at each use site. `never` is
 * assignable to everything, so those casts were unchecked by construction — strictly worse than
 * `any`, which at least reads as an admission. Take the subject as `unknown` in the spec and
 * narrow it here, in one place.
 */

/** Minimal shape of a GraphQL error; the suite only ever reads the message. */
export interface GraphQLErrorLike {
    message: string
}

/** The two shapes `cy.apollo` can yield, unified. */
export interface ApolloOutcome<T> {
    data?: T | null
    /** Present on a GraphQL response (including `errorPolicy: 'all'`). */
    errors?: ReadonlyArray<GraphQLErrorLike>
    /** Present when the subject is an ApolloError rather than a response. */
    graphQLErrors?: ReadonlyArray<GraphQLErrorLike>
}

/**
 * Every operation this module exposes lives under `admin.jahia.websites.<op>`. Declaring the
 * payload once is what stops the 4-level literal type being hand-written at each call site — the
 * variant that silently drifted from the schema ten times over.
 */
export interface WebsitesPayload {
    __typename?: string
    createSiteByKey?: boolean
    deleteSiteByKey?: boolean
    exportWebsite?: boolean
    importWebsite?: boolean
    /** `ExportAllSitesResults` is a GraphQL enum, so it arrives as a string. */
    exportAllSites?: string
}

export interface WebsitesData {
    admin?: { jahia?: { websites?: WebsitesPayload } }
}

/** Read-back shape of `cypress/fixtures/graphql/query/siteExists.graphql`. */
export interface JcrNodeRef {
    uuid: string
}

export interface JcrData {
    jcr?: { nodeByPath?: JcrNodeRef | null }
}

const asOutcome = <T>(result: unknown): ApolloOutcome<T> => (result ?? {}) as ApolloOutcome<T>;

/** GraphQL errors carried by either shape; never null, so `.length` is always safe. */
export const errorsOf = (result: unknown): ReadonlyArray<GraphQLErrorLike> => {
    const outcome = asOutcome<unknown>(result);
    return outcome.graphQLErrors ?? outcome.errors ?? [];
};

/** All error messages joined, for `.to.contain('Permission denied')` style assertions. */
export const errorMessagesOf = (result: unknown): string =>
    errorsOf(result)
        .map(error => error.message)
        .join(' ');

/**
 * Value of a single `admin.jahia.websites` field, or `undefined` when the request errored out
 * before producing one.
 */
export const websitesResult = <K extends keyof WebsitesPayload>(result: unknown, field: K): WebsitesPayload[K] =>
    asOutcome<WebsitesData>(result).data?.admin?.jahia?.websites?.[field];

/**
 * Node yielded by the `siteExists` read-back. `null` (DXM resolves a missing path to null under
 * `errorPolicy: 'all'`) and `undefined` (the whole request errored) both mean "not there".
 */
export const nodeByPathOf = (result: unknown): JcrNodeRef | null | undefined =>
    asOutcome<JcrData>(result).data?.jcr?.nodeByPath;
