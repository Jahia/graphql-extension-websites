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

    const errorsOf = (result: {graphQLErrors?: Array<{message: string}>; errors?: Array<{message: string}>}) =>
        result.graphQLErrors ?? result.errors ?? [];

    const exportAllSitesAs = (username: string) => {
        cy.apolloClient({username, password: PASSWORD});
        return cy.apollo({mutation: exportAllSites});
    };

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
                expect((result as {data: {admin: {jahia: {exportAllSites: string}}}}).data.admin.jahia.exportAllSites)
                    .to.eq('AWS_S3_BUCKET_NOT_CONFIGURED');
            });
        });
    });
});
