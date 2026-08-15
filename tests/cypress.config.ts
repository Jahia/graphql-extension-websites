import {defineConfig} from 'cypress';
import fs from 'fs';

export default defineConfig({
    defaultCommandTimeout: 30000,
    // `defaultCommandTimeout` does NOT cover cy.request, which has its own `responseTimeout`
    // (default 30 000 ms). Every provisioning call — cy.executeGroovy → runProvisioningScript →
    // cy.request — is therefore capped by this value, and the scripts this suite runs deliberately
    // block server-side while they wait out @GraphQLAsync exports (stageImportTree.groovy budgets
    // 120 s, assertVarPath.groovy up to 120 s). At the default, a slow node aborts the request
    // first and the failure reads as an opaque "cy.request timed out" instead of the script's own
    // diagnostic. Keep this comfortably above the longest server-side deadline.
    responseTimeout: 180000,
    // VideoUploadOnPasses: false,
    reporter: 'cypress-multi-reporters',
    reporterOptions: {
        configFile: 'reporter-config.json'
    },
    screenshotsFolder: './results/screenshots',
    video: true, // In Cypress, videos are disabled by default
    videosFolder: './results/videos',
    viewportWidth: 1366,
    viewportHeight: 768,
    watchForFileChanges: false,
    e2e: {
        // We've imported your old cypress plugins here.
        // You may want to clean this up later by importing these.
        setupNodeEvents(on, config) {
            // Delete videos for tests that did not fail
            on('after:spec', (spec: Cypress.Spec, results: CypressCommandLine.RunResult) => {
                if (results && results.video) {
                    // Do we have failures for any retry attempts?
                    const failures = results.tests.some(test =>
                        test.attempts.some(attempt => attempt.state === 'failed')
                    );
                    if (!failures) {
                        // Delete the video if the spec passed and no tests retried
                        fs.unlinkSync(results.video);
                    }
                }
            });
            // eslint-disable-next-line @typescript-eslint/no-var-requires
            return require('./cypress/plugins/index.js')(on, config);
        },
        excludeSpecPattern: '*.ignore.ts',
        baseUrl: 'http://localhost:8080'
    },
    env: {}
});
