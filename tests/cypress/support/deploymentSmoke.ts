import {DocumentNode} from 'graphql';
import {getStartedModuleVersion} from '@jahia/cypress';
import {errorMessagesOf, errorsOf, websitesResult} from './graphqlResult';

/**
 * Preconditions every spec depends on but none of them used to state.
 *
 * Without these, a run against an instance where the module never started fails deep inside the
 * first authorization assertion — or, worse, passes: a spec that only asserts "this call was
 * refused" is trivially satisfied by an API that does not exist at all.
 */

/** GraphQL type name of the namespace container, from `@GraphQLName` on `WebsitesAdminMutation`. */
const WEBSITES_CONTAINER_TYPE = 'WebsitesAdminMutation';

// eslint-disable-next-line @typescript-eslint/no-var-requires
const websitesApiSmoke: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/websitesApiSmoke.graphql');

/**
 * Fails loudly when a module the fixtures depend on is not started on the instance under test.
 *
 * `expectedVersion` is OPT-IN and no caller passes it today — say so plainly rather than let the
 * comment imply a guard that is switched on. The stale-jar hole it addresses is closed at source
 * instead: `ci.build.sh` used to copy the module jar inside `if [[ -e ../target ]]` with no
 * `set -e`, so a missing target directory (or one holding no SNAPSHOT jar) left the copy undone
 * and the run silently proceeded against whatever version the Jahia image already carried. It now
 * fails loudly, which is the better place to catch it.
 *
 * Pass a version here if you ever need belt-and-braces: asserting only "some version is started"
 * cannot tell 2.2.1-SNAPSHOT from 2.2.0, so it would not notice a run green-lighting the
 * modulesToDeploy fix against a jar that does not contain it.
 */
export const expectModuleStarted = (moduleId: string, expectedVersion?: string): void => {
    getStartedModuleVersion(moduleId).then((version: string | undefined) => {
        expect(version, `module '${moduleId}' must be started on the instance under test`).to.not.eq(undefined);
        if (expectedVersion) {
            expect(
                version,
                `module '${moduleId}' must be the version under test — a stale jar would test the wrong code`
            ).to.eq(expectedVersion);
        }
    });
};

/**
 * Asserts the module is deployed AND its schema extension is reachable, i.e. that a failure below
 * is a real regression rather than a missing deployment.
 */
export const expectWebsitesApiAvailable = (): void => {
    expectModuleStarted('graphql-extension-websites');
    cy.apollo({mutation: websitesApiSmoke}).then((result: unknown) => {
        expect(
            errorsOf(result),
            `the websites namespace must resolve — got: ${errorMessagesOf(result)}`
        ).to.have.length(0);
        expect(websitesResult(result, '__typename'), 'admin.jahia.websites must resolve to the module container').to.eq(
            WEBSITES_CONTAINER_TYPE
        );
    });
};
