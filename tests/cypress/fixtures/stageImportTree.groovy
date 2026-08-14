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
 *
 * Throws on any problem. A throw makes the provisioning request return non-200, which fails
 * the calling Cypress step loudly instead of leaving the import to fail later for an unrelated
 * reason.
 */

import org.apache.commons.io.FileUtils
import org.jahia.settings.SettingsBean

def settings = SettingsBean.getInstance()
def source = new File(new File(settings.getJahiaVarDiskPath(), 'exports'), '__EXPORT_DIR__')
def target = new File(settings.getJahiaImportsDiskPath(), '__IMPORT_DIR__')

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

// Report the staged layout into jahia.log so a CI failure is diagnosable without a shell.
def staged = []
target.eachFileRecurse { f -> staged << f.getAbsolutePath().substring(target.getAbsolutePath().length() + 1) }
println "stageImportTree: staged ${staged.size()} entries into ${target}: ${staged.sort()}"
