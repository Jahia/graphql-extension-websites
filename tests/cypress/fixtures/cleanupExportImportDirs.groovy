/*
 * Removes the export and/or import directories a spec staged, server-side.
 *
 * Used both to ARRANGE (so a "the archive is present" assertion cannot pass on a leftover from an
 * earlier run) and to TEAR DOWN. Cypress cannot reach Jahia's filesystem, hence the provisioning
 * round trip.
 *
 * Tokens replaced by the caller — either may be left empty to skip that half:
 *   __EXPORT_DIR__  directory under {jahiaVarDiskPath}/exports
 *   __IMPORT_DIR__  directory under {jahiaImportsDiskPath}
 *
 * Both are validated as a single safe path segment before anything is deleted: these are literal
 * constants in the specs, but the script deletes recursively and must not be turnable into one
 * that walks out of its base directory.
 */

import org.apache.commons.io.FileUtils
import org.jahia.settings.SettingsBean

def settings = SettingsBean.getInstance()

def removeDir = { File baseDir, String name ->
    if (name.isEmpty()) {
        return
    }
    if (!(name ==~ /[A-Za-z0-9][A-Za-z0-9._-]*/)) {
        throw new IllegalArgumentException("Refusing to delete '${name}': not a safe single path segment")
    }
    def target = new File(baseDir, name)
    if (target.exists()) {
        FileUtils.deleteDirectory(target)
        println "cleanupExportImportDirs: deleted ${target}"
    } else {
        println "cleanupExportImportDirs: nothing to delete at ${target}"
    }
}

removeDir(new File(settings.getJahiaVarDiskPath(), 'exports'), '__EXPORT_DIR__'.trim())
removeDir(new File(settings.getJahiaImportsDiskPath()), '__IMPORT_DIR__'.trim())
