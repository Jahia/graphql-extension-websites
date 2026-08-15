/*
 * Stages a tree produced by exportWebsite into {jahiaImportsDiskPath} so that importWebsite
 * has something real to read.
 *
 * Runs server-side inside the Jahia container via the provisioning API (cy.executeGroovy).
 * Cypress runs in a separate container and cannot touch Jahia's filesystem, so this is the
 * only way to move a file between the exports and imports directories.
 *
 * Tokens replaced by the caller:
 *   __EXPORT_DIR__  directory under {jahiaVarDiskPath}/exports written by exportWebsite
 *   __IMPORT_DIR__  directory to create under {jahiaImportsDiskPath}
 *   __SITE_KEY__    exported site key; also the name of the per-site sub-directory
 *   __PRUNE_DIRS__  space-separated top-level directories to EMPTY in the staged copy; pass an
 *                   empty value to stage the tree verbatim (see "Pruning" below)
 *
 * Both directory tokens are validated as a NON-EMPTY single path segment before anything is
 * deleted. new File(baseDir, "") resolves to baseDir itself, so an empty or unsubstituted
 * __IMPORT_DIR__ would hand the whole imports directory to FileUtils.deleteDirectory below.
 * cleanupExportImportDirs.groovy has always guarded that; the guard was never backported here.
 *
 * Pruning. exportWebsite sets INCLUDE_USERS and INCLUDE_ROLES, so the tree it writes carries
 * instance-wide `users/` and `roles/` snapshots, and importWebsite feeds both back into the
 * repository (roles via importSiteZip, which reads the directory through DirectoryZipInputStream).
 * A spec whose subject is AUTHORIZATION, not the round trip, must not rewrite /users and /roles on
 * the shared instance halfway through a run — that makes the outcome depend on where in the file
 * the export happened to be taken. Such a spec passes the directories it does not want applied.
 *
 * They are EMPTIED, not deleted: importWebsite always builds descriptors for {importPath}/roles
 * and {importPath}/users, and Jahia's DirectoryZipInputStream wraps a MISSING directory in an
 * unchecked RuntimeException (Files.walk on a nonexistent path) that would surface as a GraphQL
 * error rather than as an import that simply carried no users or roles. An empty directory yields
 * zero descriptors and is a no-op. Do not "simplify" this into a delete.
 *
 * Throws on any problem. A throw makes the provisioning request return non-200, which fails
 * the calling Cypress step loudly instead of leaving the import to fail later for an unrelated
 * reason.
 */

import org.apache.commons.io.FileUtils
import org.jahia.settings.SettingsBean

// The label is a plain description, never the token spelling: the provisioning API substitutes
// every occurrence of a token in this file, so embedding the token in the message would print the
// substituted value twice and name nothing.
def safeSegment = { String value, String label ->
    def name = value.trim()
    if (!(name ==~ /[A-Za-z0-9][A-Za-z0-9._-]*/)) {
        throw new IllegalArgumentException("Refusing to use '${name}' as the ${label}: not a safe, non-empty single path segment")
    }
    return name
}

def settings = SettingsBean.getInstance()
def source = new File(new File(settings.getJahiaVarDiskPath(), 'exports'), safeSegment('__EXPORT_DIR__', 'export directory'))
def target = new File(settings.getJahiaImportsDiskPath(), safeSegment('__IMPORT_DIR__', 'import directory'))
def pruneRaw = '__PRUNE_DIRS__'.trim()
def pruneNames = pruneRaw.isEmpty() ? [] : (pruneRaw.split('\\s+') as List).collect { n -> safeSegment(n, 'directory to prune') }

// exportWebsite is @GraphQLAsync: the GraphQL response returns before the export finishes and
// the mutation exposes no way to poll. Wait for the tree to be complete rather than racing it.
// export.properties is written by the exporter, so treat it plus the per-site directory as the
// completion marker.
def marker = new File(source, 'export.properties')
def siteDir = new File(source, '__SITE_KEY__')
def deadline = System.currentTimeMillis() + 120000L
while (System.currentTimeMillis() < deadline && !(marker.isFile() && siteDir.isDirectory())) {
    Thread.sleep(500L)
}

if (!source.isDirectory()) {
    throw new IllegalStateException("Export directory was never created: ${source}")
}
if (!marker.isFile()) {
    throw new IllegalStateException("Timed out waiting for ${marker} — export did not complete")
}
if (!siteDir.isDirectory()) {
    throw new IllegalStateException(
            "Export produced no '__SITE_KEY__' directory in ${source}; found ${source.list()?.toList()}")
}

// importWebsite reads {importPath}/{siteKey}/site.properties, so fail here rather than let the
// mutation return a bare false that says nothing about why.
def siteProperties = new File(siteDir, 'site.properties')
if (!siteProperties.isFile()) {
    throw new IllegalStateException("Export is missing ${siteProperties}")
}

if (target.exists()) {
    FileUtils.deleteDirectory(target)
}
FileUtils.copyDirectory(source, target)

// Empty (never remove) the directories the caller does not want applied to the instance. Cleaning
// the COPY leaves the export under {jahiaVarDiskPath}/exports intact, so the on-disk assertions in
// exportArtifacts.ts still see the export exactly as the exporter wrote it.
//
// A name the tree does not contain is an ERROR, not a no-op: the prune is a safety control, and a
// control that silently did not apply is worse than none — the caller would go on believing the
// instance-wide snapshot had been withheld while the import wrote it back.
pruneNames.each { name ->
    def pruned = new File(target, name)
    if (!pruned.isDirectory()) {
        throw new IllegalStateException(
                "Cannot prune '${name}': no such directory in the staged tree ${target}; found ${target.list()?.toList()}")
    }
    FileUtils.cleanDirectory(pruned)
    println "stageImportTree: emptied ${pruned} — its contents must not be imported into the shared instance"
}

// Report the staged layout into jahia.log so a CI failure is diagnosable without a shell.
def staged = []
target.eachFileRecurse { f -> staged << f.getAbsolutePath().substring(target.getAbsolutePath().length() + 1) }
println "stageImportTree: staged ${staged.size()} entries into ${target}: ${staged.sort()}"
