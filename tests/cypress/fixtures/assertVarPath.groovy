/*
 * Asserts the ON-DISK side effect of an export, server-side.
 *
 * Why this exists: a mutation's return value is not evidence of what was persisted. exportWebsite
 * returns false both when it refuses a caller and when it writes an archive it should not have —
 * reordering the target-scoped permission check after the export would produce a real archive of
 * an unauthorized site and STILL return false. Only the filesystem distinguishes the two, and
 * Cypress runs in its own container and cannot reach Jahia's filesystem, so the check runs here.
 *
 * exportWebsite is @GraphQLAsync: the GraphQL response returns before the export completes, so
 * neither expectation may be evaluated immediately.
 *   PRESENT — poll until the path exists and every marker inside it exists, up to the timeout.
 *   ABSENT  — poll for the WHOLE timeout and fail the moment the path appears. A regression
 *             therefore fails fast; only the passing case waits out the full settle window.
 *
 * Tokens replaced by the caller:
 *   __RELATIVE_PATH__  path to check, relative to {jahiaVarDiskPath} (e.g. exports/gew-export);
 *                      checked for substitution, NOT for shape — see the note below
 *   __MARKERS__        space-separated paths that must exist INSIDE it (PRESENT only; may be empty)
 *   __EXPECT__         PRESENT or ABSENT
 *   __TIMEOUT_MS__     poll budget in milliseconds
 *
 * Throws on violation. A throw makes the provisioning request return non-200, which fails the
 * calling Cypress step loudly — the same contract as stageImportTree.groovy.
 */

import org.jahia.settings.SettingsBean

def settings = SettingsBean.getInstance()
def relativePath = '__RELATIVE_PATH__'

// The ABSENT branch passes whenever the path does not exist — including when the token was never
// substituted or was misspelled, which would turn the falsifiability control for "an unauthorized
// export must not reach disk" into a permanently-green no-op. Prove the substitution happened.
//
// DELIBERATE ASYMMETRY, do not "fix" it: unlike cleanupExportImportDirs.groovy / deleteRole.groovy,
// the path is NOT validated as a safe single segment here. Two reasons. First, those scripts DELETE
// and this one only reads — it calls exists()/list() and nothing else, so a bad value cannot damage
// anything. Second, callers legitimately pass MULTI-SEGMENT paths ('exports/gew-owned-export'), and
// the single-segment regex the deleting scripts use would reject every one of them.
// Only substitution is checked, because that is the failure that would make this control vacuous.
if (relativePath.contains('__') || relativePath.trim().isEmpty()) {
    throw new IllegalArgumentException(
            "Token __RELATIVE_PATH__ was not substituted (got '${relativePath}'); the assertion would pass vacuously")
}

def target = new File(new File(settings.getJahiaVarDiskPath()), relativePath)
def markerNames = '__MARKERS__'.trim().isEmpty() ? [] : ('__MARKERS__'.trim().split('\\s+') as List)
def expectation = '__EXPECT__'.trim()
def deadline = System.currentTimeMillis() + Long.parseLong('__TIMEOUT_MS__'.trim())

def missingMarkers = { -> markerNames.findAll { name -> !new File(target, name).exists() } }

if (expectation == 'PRESENT') {
    while (System.currentTimeMillis() < deadline && !(target.exists() && missingMarkers().isEmpty())) {
        Thread.sleep(500L)
    }
    if (!target.exists()) {
        throw new IllegalStateException("Expected ${target} to have been written, but it does not exist")
    }
    def missing = missingMarkers()
    if (!missing.isEmpty()) {
        throw new IllegalStateException(
                "Export at ${target} is incomplete: missing ${missing}; found ${target.list()?.toList()}")
    }
    println "assertVarPath: ${target} is present and complete (markers ${markerNames})"
} else if (expectation == 'ABSENT') {
    while (System.currentTimeMillis() < deadline) {
        if (target.exists()) {
            throw new IllegalStateException(
                    "Expected ${target} NEVER to be written, but it exists: ${target.list()?.toList()}")
        }
        Thread.sleep(500L)
    }
    println "assertVarPath: ${target} stayed absent for the whole settle window"
} else {
    throw new IllegalArgumentException("Unsupported expectation '${expectation}': use PRESENT or ABSENT")
}
