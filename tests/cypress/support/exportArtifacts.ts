/**
 * Filesystem assertions for the export mutations, executed server-side.
 *
 * `exportWebsite` returns `false` for a refused caller AND for a caller whose archive was written
 * anyway (reorder the target-scoped check after the export and the return value does not change),
 * so a spec that only reads the boolean cannot tell a working gate from a broken one. These
 * helpers close that gap by looking at the disk instead.
 *
 * The mutation is `@GraphQLAsync`, so nothing here reads the filesystem immediately — every check
 * polls with a deadline. See cypress/fixtures/assertVarPath.groovy.
 */

/** Marker written by the exporter at the root of an export tree, used as a completion signal. */
const EXPORT_COMPLETION_MARKER = 'export.properties';
/** Poll budget for "the export must appear", matched to stageImportTree.groovy. */
const EXPORT_APPEARS_TIMEOUT_MS = '120000';
/**
 * Settle window for "the export must NOT appear". Long enough that a regression which starts the
 * export would be caught (the exporter creates its server directory early); a violation fails as
 * soon as the path shows up, so only the passing case waits this out.
 */
const EXPORT_STAYS_ABSENT_TIMEOUT_MS = '15000';

/** Waits until `{jahiaVarDiskPath}/exports/<exportDir>` holds a COMPLETE export of `siteKey`. */
export const expectExportArtifactPresent = (exportDir: string, siteKey: string): void => {
    cy.executeGroovy('assertVarPath.groovy', {
        __RELATIVE_PATH__: `exports/${exportDir}`,
        __MARKERS__: `${EXPORT_COMPLETION_MARKER} ${siteKey}`,
        __EXPECT__: 'PRESENT',
        __TIMEOUT_MS__: EXPORT_APPEARS_TIMEOUT_MS
    });
};

/** Asserts a path relative to `{jahiaVarDiskPath}` is never created (path-confinement checks). */
export const expectVarPathAbsent = (relativePath: string): void => {
    cy.executeGroovy('assertVarPath.groovy', {
        __RELATIVE_PATH__: relativePath,
        __MARKERS__: '',
        __EXPECT__: 'ABSENT',
        __TIMEOUT_MS__: EXPORT_STAYS_ABSENT_TIMEOUT_MS
    });
};

/** Asserts `{jahiaVarDiskPath}/exports/<exportDir>` is never created. */
export const expectExportArtifactAbsent = (exportDir: string): void => {
    expectVarPathAbsent(`exports/${exportDir}`);
};

/**
 * Removes staged directories. Call it in setup as well as teardown: a "the archive is present"
 * assertion that runs against a leftover from an earlier run proves nothing.
 */
export const cleanupStagedDirs = (dirs: { exportDir?: string; importDir?: string }): void => {
    cy.executeGroovy('cleanupExportImportDirs.groovy', {
        __EXPORT_DIR__: dirs.exportDir ?? '',
        __IMPORT_DIR__: dirs.importDir ?? ''
    });
};
