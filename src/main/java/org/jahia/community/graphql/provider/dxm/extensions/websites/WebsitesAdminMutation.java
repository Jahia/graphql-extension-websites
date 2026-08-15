package org.jahia.community.graphql.provider.dxm.extensions.websites;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import org.apache.commons.collections.ExtendedProperties;
import org.apache.commons.io.FileUtils;
import org.jahia.api.settings.SettingsBean;
import org.jahia.bin.listeners.JahiaContextLoaderListener;
import org.jahia.commons.Version;
import org.jahia.exceptions.JahiaException;
import org.jahia.modules.graphql.provider.dxm.DataFetchingException;
import org.jahia.modules.graphql.provider.dxm.security.GraphQLAsync;
import org.jahia.modules.graphql.provider.dxm.security.GraphQLRequiresPermission;
import org.jahia.osgi.BundleUtils;
import org.jahia.registries.ServicesRegistry;
import org.jahia.services.SpringContextSingleton;
import org.jahia.services.content.JCRObservationManager;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.importexport.ImportExportBaseService;
import org.jahia.services.importexport.ImportExportService;
import org.jahia.services.importexport.ImportUpdateService;
import org.jahia.services.search.spell.CompositeSpellChecker;
import org.jahia.services.sites.JahiaSite;
import org.jahia.services.sites.JahiaSitesService;
import org.jahia.services.sites.SiteCreationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.xml.sax.SAXException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.SizeConstant;
import software.amazon.awssdk.transfer.s3.progress.LoggingTransferListener;

import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.xml.transform.TransformerException;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * GraphQL mutation extensions for Jahia website lifecycle operations (create, delete,
 * export, import, bulk export-to-S3).
 *
 * <p><b>Permissions.</b> Each mutation is gated by its own permission via
 * {@link GraphQLRequiresPermission}, so an operator can delegate one operation without
 * delegating the others.  Unauthenticated or insufficiently privileged requests are rejected
 * by the GraphQL security layer before the method body executes.
 *
 * <table border="1">
 *   <caption>Permission per mutation</caption>
 *   <tr><th>Mutation</th><th>Annotation (checked at {@code /})</th><th>Additional in-body gate</th></tr>
 *   <tr><td>{@link #createSiteByKey}</td><td>{@code websitesCreate}</td><td>—</td></tr>
 *   <tr><td>{@link #exportWebsite}</td><td>{@code websitesAdmin}</td><td>{@code websitesExport} <em>on the target site</em></td></tr>
 *   <tr><td>{@link #exportAllSites()}</td><td>{@code websitesExportAll}</td><td>{@code admin} at the repository root</td></tr>
 *   <tr><td>{@link #deleteSiteByKey}</td><td>{@code websitesAdmin}</td><td>{@code websitesDelete} <em>on the target site</em></td></tr>
 *   <tr><td>{@link #importWebsite}</td><td>{@code websitesAdmin}</td><td>{@code admin} at the repository root</td></tr>
 * </table>
 *
 * <p><b>Why {@code deleteSiteByKey} and {@code importWebsite} keep the coarse
 * {@code websitesAdmin} annotation.</b> Their real authorization lives in the method body, and
 * neither can be expressed at the annotation level.  The same applies to {@link #exportWebsite}.
 *
 * <p>For the two target-scoped mutations the annotation <em>must not</em> name the fine
 * permission ({@code websitesDelete} / {@code websitesExport}). Those are deliberately never
 * granted at the repository root — they live on the site-scoped role — so naming them here would
 * deny every site administrator before the body could run. Nor can they simply be added to the
 * root-granted server role to "fix" that: JCR permissions inherit downward, so a root grant would
 * satisfy the in-body per-site check on <em>every</em> site and make it vacuous. The coarse
 * annotation plus a fine in-body check is the only combination that works.
 *
 * <p><b>An annotation is a coarse gate, not an authorization model.</b> The provider
 * evaluates it against the <em>repository root</em> ({@code GqlJcrPermissionChecker} resolves
 * the annotation's optional path, defaulting to {@code "/"}), so it answers only "may this
 * caller reach the websites API at all". It cannot express a per-target rule, because the
 * annotation's path is static while the target arrives as a runtime argument. Any mutation
 * that acts on a <em>specific</em> site must therefore carry its own target-scoped check in
 * the method body — {@link #deleteSiteByKey} does, and the absence of one there was SEC-136.
 *
 * <p><b>Privilege scope of the exports (SEC-136).</b> Both export mutations run the JCR export
 * under the <em>caller's own</em> session — neither escalates to the root user — so an archive is
 * bounded by what that session may read.
 *
 * <p>Be precise about what that bound is worth: it confines the export to the caller's read
 * rights, which only means something if those rights are themselves narrow. Until §4.3 the
 * shipped {@code graphql-extension-websites-administrator} role granted
 * {@code jcr:read_default} at the repository root, so for a holder of that role the "bound" was
 * the entire repository and the de-escalation bought no confidentiality at all. The role no
 * longer carries that grant; read is granted per site through the site-scoped role instead.
 * (The root grant was never needed to reach the API — verified on a live instance, a caller
 * holding only {@code graphqlAdminMutation} and {@code websitesCreate} can invoke
 * {@link #createSiteByKey}.)
 *
 * <p><b>Why the privilege scopes differ between mutations.</b> The tiers below are
 * deliberate, not an inconsistency — each reflects whether the operation can degrade safely
 * when it is denied rights:
 * <ul>
 *   <li>{@link #exportWebsite} runs <em>as the caller</em> and is <em>target-scoped</em>: the
 *       caller must hold {@code websitesExport} on the site being exported. Relying on the
 *       session bound alone would make the security property depend on read ACLs lining up, and
 *       would still write a misleading near-empty archive to disk for an unauthorized caller.</li>
 *   <li>{@link #exportAllSites()} spans the whole instance, so it is restricted to <em>server
 *       administrators</em> (§4.3). A read-bounded bulk export would hand a delegated holder an
 *       archive silently containing only their own sites — a partial backup that looks
 *       complete, which is worse than a refusal.</li>
 *   <li>{@link #createSiteByKey} runs under a <em>system session</em>
 *       ({@code doExecuteWithSystemSession}). This escalation is load-bearing and must not
 *       be removed: creating {@code /sites/<siteKey>} requires write rights on {@code /sites}
 *       that a delegated {@code websitesAdmin} holder does not have. A caller-scoped session
 *       would make the mutation fail for precisely the non-admin users the {@code websitesAdmin}
 *       permission exists to serve — the escalation <em>is</em> the delegation mechanism.
 *       Residual risk is bounded: the caller supplies {@code templateSet} and
 *       {@code modulesToDeploy}, so they can enable any <em>already-installed</em> module on the
 *       new site, but cannot install modules (that requires the module manager) nor touch
 *       existing sites. {@code WebsitesAdminMutationCreateSiteTest} pins this behaviour.</li>
 *   <li>{@link #importWebsite} needs system rights <em>and</em> imports users and roles, which
 *       no de-escalation can bound. It therefore carries a second, explicit gate requiring full
 *       server-administrator rights on top of {@code websitesAdmin} (SEC-136).</li>
 *   <li>{@link #deleteSiteByKey} is <em>target-scoped</em>: it additionally requires
 *       {@code websitesDelete} on the site being deleted, checked in the caller's own session.
 *       Deletion cannot degrade gracefully — it either destroys the site or does not — so
 *       neither de-escalation (the export tier) nor a global second gate (the import tier)
 *       fits. The permission is granted per site through the
 *       {@code graphql-extension-websites-site-administrator} role, and is deliberately absent
 *       from the root-granted server role, since a root grant would inherit down to every site
 *       and make the check vacuous.</li>
 * </ul>
 */
@GraphQLName("WebsitesAdminMutation")
@GraphQLDescription("Website lifecycle administrative mutations")
public class WebsitesAdminMutation {
    private static final String JAHIA_RELEASE = "JahiaRelease";
    private static final Logger LOGGER = LoggerFactory.getLogger(WebsitesAdminMutation.class);
    private static final String ERR_MSG_ERR_WHEN_GETTING_TPL = "Error when getting templates";
    private static final String ERR_MSG_IMP_TO_CREATE_SITE = "Impossible to create website '{}'";
    private static final String ERR_MSG_IMP_TO_DELETE_SITE = "Impossible to delete website '{}'";
    private static final String FILES = "files";
    private static final String SITE = "site";
    /** Descriptor file an import directory must contain; carries the originating Jahia release. */
    private static final String EXPORT_PROPERTIES = "export.properties";
    private static final String SHARED_FILES = "/shared/files/";
    private static final String SHARED_MASHUPS = "/shared/mashups/";
    private static final String SITES_PATH_PREFIX = "/sites/"; // NOSONAR java:S1075
    private static final double S3_TARGET_THROUGHPUT_GBPS = 20.0;
    private static final long S3_MINIMUM_PART_SIZE_BYTES = 8L * SizeConstant.MB;
    /**
     * Second-granular so the archive name still sorts chronologically; a random suffix is
     * appended separately because the timestamp alone is not collision-free (see
     * {@link #buildExportFileName(LocalDateTime)}).
     */
    private static final DateTimeFormatter EXPORT_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** Hex characters of randomness appended to an export file name to make it unique. */
    private static final int EXPORT_SUFFIX_LENGTH = 8;
    /**
     * Target-scoped permission required to delete a site (SEC-136). Checked against the site
     * node itself, not the repository root — see {@link #callerMayActOnSite(JahiaSite, String, String)}.
     */
    private static final String WEBSITES_DELETE_PERMISSION = "websitesDelete";
    /**
     * Target-scoped permission required to export a single site (SEC-136 §4.3). Like
     * {@link #WEBSITES_DELETE_PERMISSION} this is checked against the site node itself, so a site
     * administrator can export the site they administer and nothing else.
     */
    private static final String WEBSITES_EXPORT_PERMISSION = "websitesExport";

    /**
     * Creates a Jahia website with the supplied parameters.
     *
     * @return {@code true} on success; {@code false} if site creation fails (e.g. the
     *         template set is not installed, or a site with that key already exists)
     */
    @GraphQLField
    @GraphQLDescription("Create a website")
    @GraphQLRequiresPermission("websitesCreate")
    public Boolean createSiteByKey(
            @GraphQLName("siteKey") @GraphQLDescription("Site key") String siteKey,
            @GraphQLName("serverName") @GraphQLDescription("Server name") String serverName,
            @GraphQLName("serverNameAliasesAsString") @GraphQLDescription("Server name aliases") String serverNameAliases,
            @GraphQLName("title") @GraphQLDescription("Title") String title,
            @GraphQLName("templateSet") @GraphQLDescription("Template set") String templateSet,
            @GraphQLName("modulesToDeploy") @GraphQLDescription("Modules to deploy") List<String> modulesToDeploy,
            @GraphQLName("locale") @GraphQLDescription("Locale") String locale
    ) {

        try {
            return JCRTemplate.getInstance().doExecuteWithSystemSession((JCRSessionWrapper session) -> {
                Boolean result;
                try {
                    final SiteCreationInfo siteCreationInfo = new SiteCreationInfo();
                    siteCreationInfo.setSiteKey(siteKey);
                    siteCreationInfo.setServerName(serverName);
                    siteCreationInfo.setServerNameAliasesAsString(serverNameAliases);
                    siteCreationInfo.setTitle(title);
                    siteCreationInfo.setTemplateSet(templateSet);
                    siteCreationInfo.setModulesToDeploy(toModulesArray(modulesToDeploy));
                    siteCreationInfo.setLocale(locale);
                    ServicesRegistry.getInstance().getJahiaSitesService().addSite(siteCreationInfo, session);
                    result = Boolean.TRUE;
                } catch (IOException | JahiaException ex) {
                    LOGGER.error(ERR_MSG_IMP_TO_CREATE_SITE, siteKey, ex);
                    result = Boolean.FALSE;
                }
                return result;
            });
        } catch (RepositoryException ex) {
            LOGGER.error(ERR_MSG_IMP_TO_CREATE_SITE, siteKey, ex);
        }
        return Boolean.FALSE;
    }

    /**
     * Deletes the Jahia website identified by {@code siteKey}.
     *
     * <p><b>Authorization (SEC-136).</b> The class-level {@code websitesAdmin} annotation is
     * evaluated at the repository root and therefore only answers "may this caller reach the
     * websites API at all" — it says nothing about <em>which</em> site may be destroyed. Until
     * 2.1.0 that was the only gate, so any holder of the delegated
     * {@code graphql-extension-websites-administrator} role could delete <em>any</em> site on
     * the instance, including sites it never created and held no rights on.
     *
     * <p>This method therefore performs a second, target-scoped check: the caller must hold
     * {@code websitesDelete} <em>on the site node itself</em>, evaluated in the caller's own
     * session (see {@link #callerMayActOnSite(JahiaSite, String, String)}). Note that no such check is implied
     * anywhere below us — {@code JahiaSitesService.removeSite} performs the deletion under
     * {@code doExecuteWithSystemSession}, so the caller's rights are never consulted by Jahia
     * and this check is the only thing standing between a caller and site destruction.
     *
     * <p>Only {@link JahiaException} — the domain failure {@code removeSite} declares — is
     * translated to {@code false}. Unchecked exceptions propagate to the GraphQL layer rather
     * than being reported as an ordinary "deletion failed", matching how
     * {@link #exportAllSites()} surfaces unexpected errors as {@link DataFetchingException}.
     * The previous {@code catch (JahiaException | RuntimeException)} conflated the two, so a
     * bug such as an NPE was indistinguishable from a site that genuinely could not be deleted.
     *
     * @return {@code true} on success; {@code false} if the site was not found, the caller is
     *         not authorized to delete it, or deletion fails
     * @throws RuntimeException if deletion fails unexpectedly (not a domain-level failure)
     */
    @GraphQLField
    @GraphQLDescription("Delete a website")
    @GraphQLRequiresPermission("websitesAdmin")
    public Boolean deleteSiteByKey(
            @GraphQLName("siteKey") @GraphQLDescription("Site key") String siteKey
    ) {
        boolean success = Boolean.FALSE;
        try {
            final JahiaSitesService jahiaSitesServices = ServicesRegistry.getInstance().getJahiaSitesService();
            final JahiaSite jahiaSite = jahiaSitesServices.getSiteByKey(siteKey);
            if (jahiaSite == null) {
                // Fix A: SLF4J parameterized logging — no String.format, no concatenation
                LOGGER.error("Impossible to delete website '{}': site not found", siteKey);
                return Boolean.FALSE;
            }
            if (!callerMayActOnSite(jahiaSite, WEBSITES_DELETE_PERMISSION, "deleteSiteByKey")) {
                return Boolean.FALSE;
            }
            jahiaSitesServices.removeSite(jahiaSite);
            success = Boolean.TRUE;
        } catch (JahiaException ex) {
            LOGGER.error(ERR_MSG_IMP_TO_DELETE_SITE, siteKey, ex);
        }
        return success;
    }

    /**
     * Adapts the GraphQL {@code [String]} argument to the {@code String[]} that
     * {@link SiteCreationInfo#setModulesToDeploy(String[])} takes.
     *
     * <p><b>Why the mutation parameter is a {@link List} and must stay one.</b> It was declared
     * {@code String[]} until 2.2.1, which made {@code createSiteByKey} fail with
     * {@code IllegalArgumentException: argument type mismatch} for <em>every</em> caller — root
     * included — whenever {@code modulesToDeploy} was supplied, even as an empty list.
     *
     * <p>graphql-java-annotations converts a GraphQL list argument to the Java parameter type only
     * when that parameter is a parameterized {@code List<T>}: {@code MethodDataFetcher.buildArg}
     * guards the conversion on {@code instanceof ParameterizedType && instanceof GraphQLList}. An
     * array parameter is a plain {@code Class}, misses that branch, and receives the raw
     * {@code ArrayList}, which then fails reflective {@code Method.invoke}.
     *
     * <p>Do not "simplify" this back to an array. {@code WebsitesAdminMutationArgumentTypeTest}
     * fails if any {@code @GraphQLField} method in this class declares an array parameter.
     *
     * @param modulesToDeploy modules requested by the caller; may be {@code null} when the
     *                        argument is omitted
     * @return {@code null} when nothing was supplied, so the behaviour of omitting the argument is
     *         unchanged from before the fix
     */
    private static String[] toModulesArray(List<String> modulesToDeploy) {
        if (modulesToDeploy == null) {
            return null;
        }
        return modulesToDeploy.toArray(new String[0]);
    }

    /**
     * Answers whether the current caller holds {@code permission} on {@code site}, by checking it
     * against the site's own JCR node.
     *
     * <p>This is the target-scoping primitive for SEC-136. It backs both
     * {@link #deleteSiteByKey} ({@code websitesDelete}) and {@link #exportWebsite}
     * ({@code websitesExport}) — operations whose authorization depends on <em>which</em> site is
     * named, and which the root-evaluated {@link GraphQLRequiresPermission} annotation therefore
     * cannot express.
     *
     * <p>Three properties of this check are load-bearing and must survive refactoring:
     *
     * <ul>
     *   <li><b>The caller's session, never a system session.</b> Resolving the node through
     *       {@code doExecuteWithSystemSession} would re-ask the question with the wrong subject
     *       and always answer "yes".</li>
     *   <li><b>The site's own path, not a path built from the {@code siteKey} argument.</b>
     *       {@link JahiaSite#getJCRLocalPath()} is repository-derived, so no untrusted input is
     *       concatenated into a JCR path here.</li>
     *   <li><b>Fail closed.</b> A caller who cannot even see the node gets
     *       {@link PathNotFoundException}, which is indistinguishable from "not authorized" and
     *       is treated as such — the same convention the GraphQL provider itself uses in
     *       {@code GqlJcrPermissionChecker}.</li>
     * </ul>
     *
     * <p>Server administrators are covered without a special case: these permissions are declared
     * as children of the {@code admin} permission, and Jahia registers nested permission nodes as
     * aggregated sub-privileges, so holding {@code admin} at the root implies them.
     *
     * @param site       the target site, already resolved from the repository
     * @param permission the permission to require on that site
     * @param operation  mutation name, for logging only
     * @return {@code true} only if the permission is positively held on the target site
     */
    private static boolean callerMayActOnSite(JahiaSite site, String permission, String operation) {
        final String sitePath = site.getJCRLocalPath();
        try {
            if (JCRSessionFactory.getInstance().getCurrentUserSession()
                    .getNode(sitePath)
                    .hasPermission(permission)) {
                return true;
            }
            LOGGER.warn("{}: refused on '{}': caller lacks the '{}' permission on that site",
                    operation, sitePath, permission);
            return false;
        } catch (PathNotFoundException ex) {
            // The caller cannot see the site at all. Deny — invisible and unauthorized are the
            // same answer here, and distinguishing them would leak site existence.
            LOGGER.warn("{}: refused on '{}': the caller cannot resolve that site", operation, sitePath);
            return false;
        } catch (RepositoryException ex) {
            LOGGER.error("{}: refused on '{}': unable to verify the '{}' permission",
                    operation, sitePath, permission, ex);
            return false;
        }
    }

    /**
     * Resolves a user-supplied path against a base directory using {@link PathSecurity},
     * returning {@code null} (and logging) instead of propagating {@link IllegalArgumentException}
     * when the path is rejected. Extracted to avoid a nested try block (Sonar S1141).
     */
    private static Path resolveContainedOrNull(Path baseDir, String candidate, String operation) {
        try {
            return PathSecurity.resolveContained(baseDir, candidate);
        } catch (IllegalArgumentException ex) {
            LOGGER.error("{}: rejected path '{}': {}", operation, candidate, ex.getMessage());
            return null;
        }
    }

    /**
     * Builds the export parameter map for a single-site export ({@link #exportWebsite}).
     *
     * <p>Extracted (behaviour-preserving) so the exposure-relevant flags can be unit-tested
     * without a Jahia container. Note this "single-site" export intentionally includes
     * {@code INCLUDE_USERS}, {@code INCLUDE_ROLES} and {@code VIEW_ACL} — bounded by the
     * caller's own read rights (no escalation), see D6.
     */
    static Map<String, Object> buildSingleSiteExportParams(String serverDirectory, String cleanupXslPath, boolean onlyStaging) {
        final Map<String, Object> params = new HashMap<>(6);
        params.put(ImportExportService.VIEW_CONTENT, true);
        params.put(ImportExportService.VIEW_VERSION, false);
        params.put(ImportExportService.VIEW_ACL, true);
        params.put(ImportExportService.VIEW_METADATA, true);
        params.put(ImportExportService.VIEW_JAHIALINKS, true);
        params.put(ImportExportService.VIEW_WORKFLOW, true);
        params.put(ImportExportService.SERVER_DIRECTORY, serverDirectory);
        params.put(ImportExportService.INCLUDE_ALL_FILES, true);
        params.put(ImportExportService.INCLUDE_TEMPLATES, true);
        params.put(ImportExportService.INCLUDE_SITE_INFOS, true);
        params.put(ImportExportService.INCLUDE_DEFINITIONS, true);
        params.put(ImportExportService.INCLUDE_LIVE_EXPORT, !onlyStaging);
        params.put(ImportExportService.INCLUDE_USERS, true);
        params.put(ImportExportService.INCLUDE_ROLES, true);
        params.put(ImportExportService.XSL_PATH, cleanupXslPath);
        return params;
    }

    /**
     * Builds the export parameter map for the bulk all-sites export ({@link #exportAllSites(Path)}).
     * Extracted (behaviour-preserving) for unit testing without a Jahia container.
     */
    static Map<String, Object> buildAllSitesExportParams(String cleanupXslPath) {
        Map<String, Object> params = new HashMap<>();
        params.put(ImportExportService.VIEW_CONTENT, true);
        params.put(ImportExportService.VIEW_VERSION, false);
        params.put(ImportExportService.VIEW_ACL, true);
        params.put(ImportExportService.VIEW_METADATA, true);
        params.put(ImportExportService.VIEW_JAHIALINKS, true);
        params.put(ImportExportService.VIEW_WORKFLOW, true);
        params.put(ImportExportService.INCLUDE_ALL_FILES, true);
        params.put(ImportExportService.INCLUDE_TEMPLATES, true);
        params.put(ImportExportService.INCLUDE_SITE_INFOS, true);
        params.put(ImportExportService.INCLUDE_DEFINITIONS, true);
        params.put(ImportExportService.INCLUDE_LIVE_EXPORT, true);
        params.put(ImportExportService.INCLUDE_USERS, true);
        params.put(ImportExportService.INCLUDE_ROLES, true);
        params.put(ImportExportService.XSL_PATH, cleanupXslPath);
        return params;
    }

    /**
     * Exports a single website to an on-disk directory.
     *
     * <p>This mutation is {@link GraphQLAsync}: the GraphQL response is returned before the
     * export completes.  Clients cannot poll for completion via this mutation.
     *
     * <p><b>Authorization (SEC-136 §4.3).</b> Beyond the coarse {@code websitesAdmin}
     * annotation, the caller must hold {@code websitesExport} <em>on the target site</em>, checked
     * in the caller's own session by {@link #callerMayActOnSite(JahiaSite, String, String)}.
     *
     * @param siteKey      key of the site to export
     * @param exportPath   path relative to {@code jahiaVarDiskPath/exports/}; must not
     *                     escape that directory
     * @param onlyStaging  when {@code true} only staging content is included
     * @return {@code true} on success; {@code false} if the path is rejected, the site does not
     *         exist, the caller is not authorized to export it, or the export fails
     */
    @GraphQLField
    @GraphQLDescription("Export a website")
    @GraphQLAsync
    // Coarse gate only — see callerMayActOnSite. The annotation is evaluated at the repository
    // root, and websitesExport is granted per site, so naming it here would deny every site
    // administrator before the body ran (the same trap documented on deleteSiteByKey).
    @GraphQLRequiresPermission("websitesAdmin")
    public Boolean exportWebsite(
            @GraphQLName("siteKey") @GraphQLDescription("Site key") String siteKey,
            @GraphQLName("exportPath") @GraphQLDescription("Export path") String exportPath,
            @GraphQLName("onlyStaging") @GraphQLDescription("Export only staging content") boolean onlyStaging
    ) {
        try {
            final SettingsBean settingsBean = BundleUtils.getOsgiService(SettingsBean.class, null);
            final Path exportsBaseDir = Paths.get(settingsBean.getJahiaVarDiskPath(), "exports").toAbsolutePath().normalize();
            final Path resolvedExportPath = resolveContainedOrNull(exportsBaseDir, exportPath, "exportWebsite");
            if (resolvedExportPath == null) {
                return Boolean.FALSE;
            }
            final JahiaSite site = ServicesRegistry.getInstance().getJahiaSitesService().getSiteByKey(siteKey);
            if (site == null) {
                LOGGER.error("exportWebsite: site '{}' not found", siteKey);
                return Boolean.FALSE;
            }
            // SEC-136 §4.3: exporting a site is target-scoped, exactly like deleting one. The
            // annotation above is evaluated at the repository root and cannot name the target,
            // so the caller must additionally hold websitesExport ON THIS SITE.
            //
            // The export itself runs under the caller's session, so an unauthorized caller would
            // in practice get an empty archive rather than data — but relying on that would make
            // the security property depend on read ACLs happening to line up. Refusing outright
            // is explicit, and it avoids writing a misleading near-empty archive to disk.
            if (!callerMayActOnSite(site, WEBSITES_EXPORT_PERMISSION, "exportWebsite")) {
                return Boolean.FALSE;
            }
            // Refuse to delete if the resolved path is a symlink — a symlink at the
            // export location could redirect deletion to an arbitrary target outside the
            // exports directory (Fix J continuation).
            if (Files.isSymbolicLink(resolvedExportPath)) {
                LOGGER.error("exportWebsite: refused to delete '{}': path is a symbolic link", resolvedExportPath);
                return Boolean.FALSE;
            }
            // Jahia rejects a server export directory that already contains files
            // (ImportExportBaseService.isValidServerDirectory requires it to be empty or
            // non-existent). Remove any previous export at this path so repeated exports
            // to the same exportPath are idempotent instead of failing with a 403.
            deleteExportArtifact(resolvedExportPath, "exportWebsite");
            final String cleanupXsl = settingsBean.getJahiaEtcDiskPath() + "/repository/export/cleanup.xsl";
            final Map<String, Object> params = buildSingleSiteExportParams(resolvedExportPath.toString(), cleanupXsl, onlyStaging);

            final List<JCRSiteNode> siteList = new ArrayList<>();
            siteList.add((JCRSiteNode) site);
            final ImportExportBaseService importExportBaseService = ServicesRegistry.getInstance().getImportExportService();
            // The export content is produced via SERVER_DIRECTORY in params; the OutputStream
            // argument is required by the API. Use a try-with-resources stream sink (matching
            // the proven upstream behavior) rather than discarding it.
            try (ByteArrayOutputStream exportSink = new ByteArrayOutputStream()) {
                importExportBaseService.exportSites(exportSink, params, siteList);
            }
            return Boolean.TRUE;
        } catch (JahiaException | RepositoryException | IOException | SAXException | TransformerException ex) {
            LOGGER.error("Impossible to export website '{}'", siteKey, ex);
        }
        return Boolean.FALSE;
    }

    /**
     * Imports a website from a prepared directory on disk.
     *
     * <p>The directory at {@code importPath} (relative to {@code jahiaImportsDiskPath})
     * must contain {@code export.properties}, {@code roles/}, {@code users/}, and a
     * sub-directory named after {@code siteKey}.
     *
     * @param importPath path relative to {@code jahiaImportsDiskPath}
     * @param siteKey    target site key; also used as a directory name under importPath
     * @return {@code true} on success; {@code false} on error or invalid path
     */
    @GraphQLField
    @GraphQLDescription("Import a website")
    @GraphQLRequiresPermission("websitesAdmin")
    // Fix F: removed dead `throws IOException` — the IOException is caught internally
    public Boolean importWebsite(@GraphQLName("importPath") @GraphQLDescription("Import path") String importPath,
                                        @GraphQLName("siteKey") @GraphQLDescription("Site key") String siteKey) {
        LOGGER.info("Processing Import");
        // SEC-136: importWebsite imports arbitrary users AND roles (a privilege-escalation vector), so require
        // full server-administrator rights, not merely the delegated `websitesAdmin` permission.
        if (!callerIsServerAdministrator()) {
            LOGGER.error("importWebsite denied: requires full administrator privileges (it imports users and roles)");
            return Boolean.FALSE;
        }
        final Path absoluteImportPath = resolveImportRoot(importPath, siteKey);
        if (absoluteImportPath == null) {
            return Boolean.FALSE;
        }

        final Properties exportProperties = readExportProperties(absoluteImportPath);
        if (exportProperties == null) {
            return Boolean.FALSE;
        }

        return runImport(absoluteImportPath, siteKey, exportProperties);
    }

    /**
     * Resolves the import directory, rejecting anything that escapes {@code jahiaImportsDiskPath}.
     *
     * @return the validated absolute import directory, or {@code null} if either the path or the
     *         site key was rejected (already logged)
     */
    private static Path resolveImportRoot(String importPath, String siteKey) {
        final Path importsBaseDir = Paths.get(BundleUtils.getOsgiService(SettingsBean.class, null).getJahiaImportsDiskPath())
                .toAbsolutePath().normalize();
        try {
            final Path resolved = PathSecurity.resolveContained(importsBaseDir, importPath);
            // siteKey is untrusted GraphQL input used as a directory name; reject traversal.
            PathSecurity.resolveContained(resolved, siteKey);
            return resolved;
        } catch (IllegalArgumentException ex) {
            LOGGER.error("importWebsite: rejected import location (importPath '{}', siteKey '{}'): {}",
                    importPath, siteKey, ex.getMessage());
            return null;
        }
    }

    /**
     * Reads {@code export.properties} from the import directory.
     *
     * @return the loaded properties, or {@code null} if the file could not be read (already logged)
     */
    private static Properties readExportProperties(Path absoluteImportPath) {
        final Path propertiesFile = absoluteImportPath.resolve(EXPORT_PROPERTIES);
        try (InputStream input = new FileInputStream(propertiesFile.toString())) {
            final Properties exportProperties = new Properties();
            exportProperties.load(input);
            return exportProperties;
        } catch (IOException ex) {
            LOGGER.error("Impossible to read file {}", propertiesFile, ex);
            return null;
        }
    }

    /**
     * Builds the three descriptors {@code importWebsite} feeds to the import services, in the order
     * they must be processed: roles, then users, then the site itself.
     *
     * <p>Package-private so the wiring can be asserted without a Jahia container — this is pure
     * boilerplate that is easy to get subtly wrong (note the deliberately {@code null} site key on
     * the users entry, and that the site directory is named after {@code siteKey}).
     */
    static List<ImportInfo> buildImportDescriptors(Path absoluteImportPath, String siteKey, Properties exportProperties) {
        final String jahiaRelease = exportProperties.getProperty(JAHIA_RELEASE);
        final List<ImportInfo> descriptors = new ArrayList<>(3);
        descriptors.add(newImportInfo(JahiaSitesService.SYSTEM_SITE_KEY,
                absoluteImportPath.resolve("roles").toFile(), ImportExportBaseService.ROLES_ZIP, FILES, jahiaRelease));
        // Users are repository-wide, not site-scoped: the null site key is intentional.
        descriptors.add(newImportInfo(null,
                absoluteImportPath.resolve("users").toFile(), ImportExportBaseService.USERS_ZIP, FILES, jahiaRelease));
        descriptors.add(newImportInfo(siteKey,
                absoluteImportPath.resolve(siteKey).toFile(), siteKey, SITE, jahiaRelease));
        return descriptors;
    }

    private static ImportInfo newImportInfo(String siteKey, File importFile, String importFileName, String type, String jahiaRelease) {
        final ImportInfo importInfo = new ImportInfo();
        importInfo.setSiteKey(siteKey);
        importInfo.setImportFile(importFile);
        importInfo.setImportFileName(importFileName);
        importInfo.setSelected(true);
        importInfo.setType(type);
        importInfo.setOriginatingJahiaRelease(jahiaRelease);
        return importInfo;
    }

    /**
     * Runs the import described by {@code exportProperties}.
     *
     * <p>Only the site import determines the returned value; a failed roles or files import is
     * logged and counted towards the re-index but does not by itself fail the mutation. That is
     * pre-existing behaviour, preserved deliberately during the extraction of this method.
     *
     * @return {@code true} unless the site import failed
     */
    private static Boolean runImport(Path absoluteImportPath, String siteKey, Properties exportProperties) {
        final List<ImportInfo> importsInfos = buildImportDescriptors(absoluteImportPath, siteKey, exportProperties);
        final ImportExportBaseService importExportBaseService = ServicesRegistry.getInstance().getImportExportService();
        final JahiaSitesService jahiaSitesService = ServicesRegistry.getInstance().getJahiaSitesService();

        importUsers(importExportBaseService, importsInfos);

        Boolean successful = Boolean.TRUE;
        boolean anythingImported = false;

        for (final ImportInfo infos : importsInfos) {
            if (!infos.isSelected()) {
                continue;
            }
            if (FILES.equals(infos.getType())) {
                // Fix H: only mark imported when importFiles reports success
                anythingImported |= importFiles(importExportBaseService, jahiaSitesService, importsInfos, infos);
            } else if (SITE.equals(infos.getType())) {
                anythingImported = true;
                successful = importSite(jahiaSitesService, infos, absoluteImportPath.toString());
            }
        }

        // Re-index once, after every descriptor has been processed. This call used to sit inside
        // the loop, so it re-ran on every remaining iteration once anythingImported flipped true —
        // rebuilding the index against a half-imported repository and then again at the end.
        // Doing it once here is both cheaper and a more accurate reflection of the final state.
        if (anythingImported) {
            CompositeSpellChecker.updateSpellCheckerIndex();
        }
        return successful;
    }

    private static void importUsers(ImportExportBaseService importExportBaseService, List<ImportInfo> importsInfos) {
        for (ImportInfo infos : importsInfos) {
            // Match USERS_ZIP to align with the ImportInfo wired in importWebsite()
            if (infos.isSelected() && infos.getImportFileName().equals(ImportExportBaseService.USERS_ZIP)) {
                File file = infos.getImportFile();
                try {
                    importExportBaseService.importUsers(file);
                } catch (RepositoryException | IOException e) {
                    LOGGER.error(e.getMessage(), e);
                }
                break;
            }
        }
    }

    /**
     * Imports files (roles zip) for the given {@link ImportInfo} entry.
     *
     * <p>Fix B: the nested {@code try} block that was previously inlined inside
     * {@link #importWebsite} has been extracted here to resolve SonarQube S1141.
     *
     * <p>Fix H: returns {@code true} when the import completed without error so the
     * caller only counts a genuinely successful import.
     *
     * @return {@code true} if the import succeeded; {@code false} on any error
     */
    private static boolean importFiles(ImportExportBaseService importExportBaseService, JahiaSitesService jahiaSitesService, List<ImportInfo> importsInfos, ImportInfo infos) {
        try {
            return doImportFiles(importExportBaseService, jahiaSitesService, importsInfos, infos);
        } catch (NumberFormatException | RepositoryException | JahiaException ex) {
            LOGGER.error(ERR_MSG_ERR_WHEN_GETTING_TPL, ex);
            return false;
        }
    }

    /**
     * Inner helper extracted to resolve SonarQube S1141 (nested try).
     *
     * @return {@code true} if the import completed without error
     * @throws RepositoryException propagated from {@link ImportUpdateService} or the JCR call
     * @throws JahiaException      propagated from site lookup
     */
    private static boolean doImportFiles(ImportExportBaseService importExportBaseService, JahiaSitesService jahiaSitesService, List<ImportInfo> importsInfos, ImportInfo infos) throws RepositoryException, JahiaException {
        final File file = ImportUpdateService.getInstance().updateImport( // NOSONAR java:S5738
                infos.getImportFile(),
                infos.getImportFileName(),
                infos.getType(),
                new Version(infos.getOriginatingJahiaRelease()));
        final JahiaSite system = jahiaSitesService.getSiteByKey(JahiaSitesService.SYSTEM_SITE_KEY);
        if (system == null) {
            LOGGER.error("Cannot import files: system site '{}' not found", JahiaSitesService.SYSTEM_SITE_KEY);
            return false;
        }

        final Map<String, String> pathMapping = JCRSessionFactory.getInstance()
                .getCurrentUserSession().getPathMapping();
        pathMapping.put(SHARED_FILES, SITES_PATH_PREFIX + system.getSiteKey() + "/files/");
        pathMapping.put(SHARED_MASHUPS, SITES_PATH_PREFIX + system.getSiteKey() + "/portlets/");
        importsInfos.stream()
                .filter(infos2 -> (infos2.getOldSiteKey() != null && infos2.getSiteKey() != null && !infos2.getOldSiteKey().equals(infos2.getSiteKey())))
                .forEachOrdered((ImportInfo infos2)
                        -> pathMapping.put(SITES_PATH_PREFIX + infos2.getOldSiteKey(), SITES_PATH_PREFIX + infos2.getSiteKey())
                );

        final boolean[] success = {false};
        JCRTemplate.getInstance().doExecuteWithSystemSession((JCRSessionWrapper session) -> {
            try {
                session.getPathMapping().putAll(pathMapping);
                importExportBaseService.importSiteZip(file == null ? null : new FileSystemResource(file),
                        system,
                        infos.asMap(),
                        null,
                        null,
                        session);
                success[0] = true;
            } catch (IOException | RepositoryException ex) {
                LOGGER.error(ERR_MSG_ERR_WHEN_GETTING_TPL, ex);
            }
            return null;
        });
        return success[0];
    }

    private static boolean importSite(JahiaSitesService jahiaSitesService, ImportInfo infos, String absoluteImportPath) {
        Boolean successful = Boolean.TRUE;
        try (InputStream inputSite = new FileInputStream(Paths.get(absoluteImportPath, infos.getSiteKey(), "site.properties").toString())) {
            final ExtendedProperties siteProperties = new ExtendedProperties();
            siteProperties.load(inputSite);
            final File file = ImportUpdateService.getInstance().updateImport( // NOSONAR java:S5738
                    infos.getImportFile(),
                    infos.getImportFileName(),
                    infos.getType(),
                    new Version(infos.getOriginatingJahiaRelease()));
            final Iterator<String> installedModulesIterator = siteProperties.getKeys("installedModules");
            final List<String> installedModules = new ArrayList<>();
            while (installedModulesIterator.hasNext()) {
                final String installedModule = installedModulesIterator.next();
                installedModules.add(siteProperties.getString(installedModule));
            }
            JCRObservationManager.doWithOperationType(null, JCRObservationManager.IMPORT, (JCRSessionWrapper unusedSession) -> {
                try {
                    SiteCreationInfo siteCreationInfo = SiteCreationInfo.builder().
                            siteKey(infos.getSiteKey()).
                            serverName(siteProperties.getString("siteservername")).
                            serverNameAliases(siteProperties.getString("siteservernamealiases")).
                            title(siteProperties.getString("sitetitle")).
                            description(siteProperties.getString("description")).
                            templateSet(siteProperties.getString("templatePackageName")).
                            modulesToDeploy(installedModules.toArray(new String[installedModules.size()])).
                            locale(siteProperties.getString("defaultLanguage")).
                            firstImport("fileImport").
                            fileImport(file == null ? null : new FileSystemResource(file)).
                            fileImportName(infos.getImportFileName()).
                            originatingJahiaRelease(infos.getOriginatingJahiaRelease()).build();
                    jahiaSitesService.addSite(siteCreationInfo);
                } catch (JahiaException | IOException e) {
                    throw new RepositoryException(e);
                }
                return null;
            });
        // Only the checked exceptions actually thrown are caught here, so unchecked
        // RuntimeException and Error propagate instead of being silently swallowed.
        } catch (IOException | RepositoryException e) {
            LOGGER.error("Cannot create site '{}'", infos.getSiteTitle(), e);
            successful = Boolean.FALSE;
        }
        return successful;
    }

    /**
     * Exports all sites in this Jahia instance to a timestamped ZIP and uploads it to the
     * configured AWS S3 bucket.
     *
     * <p><b>Permission:</b> requires {@code websitesExportAll}, evaluated at the repository root
     * by {@link GraphQLRequiresPermission}, <em>and</em> full server-administrator rights
     * ({@code admin} at the repository root), checked in the method body by
     * {@link #callerIsServerAdministrator()}. A holder of the delegated {@code websitesAdmin}
     * role cannot run this mutation: since 2.2.0 it returns
     * {@link ExportAllSitesResults#NOT_SERVER_ADMINISTRATOR} before any other work
     * (SEC-136 §4.3) — a bulk export spans the whole instance, and a read-bounded one would
     * yield a partial backup that looks complete.
     *
     * <p><b>Privilege scope (SEC-136):</b> the server-administrator gate above is the primary
     * control. As defence in depth the JCR export additionally runs under the <em>caller's own</em>
     * session — it does <b>not</b> switch the session user to root — so the archive is still
     * bounded by what that session may read (sites enumerated via
     * {@link JahiaSitesService#getSitesNodeList()}). That bound is no longer what keeps a
     * delegated holder out; the gate is.
     *
     * <p><b>S3 credentials:</b> configure AWS credentials via the OSGi ConfigurationAdmin
     * service (PID {@code org.jahia.community.graphql.websites}) or the
     * {@code src/main/resources/META-INF/configurations/org.jahia.community.graphql.websites.cfg}
     * file — <em>never</em> pass credentials inline in the GraphQL mutation.
     *
     * <p><b>How failures are reported.</b> This mutation deliberately uses two channels, split by
     * whether the caller can act on the outcome:
     * <ul>
     *   <li><b>Typed result</b> — an {@link ExportAllSitesResults} value for an <em>expected,
     *       actionable</em> outcome. {@link ExportAllSitesResults#AWS_S3_BUCKET_NOT_CONFIGURED}
     *       is a precondition the operator fixes by supplying configuration; it is not an
     *       internal error and carries no diagnostic detail worth propagating.</li>
     *   <li><b>{@link DataFetchingException}</b> — for anything <em>unexpected</em> (JCR, I/O,
     *       XML, AWS transfer failures). These are wrapped so the underlying cause reaches the
     *       GraphQL error extensions and the logs. Flattening them into an enum constant would
     *       discard exactly the information needed to diagnose them.</li>
     * </ul>
     * Callers should therefore branch on the returned value for configuration problems and
     * handle GraphQL errors for everything else.
     *
     * @return {@link ExportAllSitesResults#NOT_SERVER_ADMINISTRATOR} if the caller is not a server
     *         administrator; {@link ExportAllSitesResults#AWS_S3_BUCKET_NOT_CONFIGURED} if S3 is
     *         not configured; {@link ExportAllSitesResults#SUCCESS} on success
     * @throws DataFetchingException on any unexpected failure during export or upload
     */
    @GraphQLField
    @GraphQLDescription("Export all sites to the configured S3 bucket. Returns AWS_S3_BUCKET_NOT_CONFIGURED "
            + "(without exporting) when S3 configuration is incomplete; raises a GraphQL error for any "
            + "unexpected export or upload failure.")
    @GraphQLRequiresPermission("websitesExportAll")
    public ExportAllSitesResults exportAllSites() {
        // SEC-136 §4.3: a bulk instance export is inherently instance-wide, so it is restricted
        // to server administrators. The alternative — letting a delegated holder run it under
        // their own session — produces an archive silently containing only the sites they can
        // read. A partial backup that looks complete is worse than a refusal.
        //
        // Checked before the S3 precondition so an unauthorized caller learns nothing about
        // whether the instance is configured.
        if (!callerIsServerAdministrator()) {
            LOGGER.error("exportAllSites denied: requires full administrator privileges "
                    + "(a bulk export spans the whole instance)");
            return ExportAllSitesResults.NOT_SERVER_ADMINISTRATOR;
        }

        GraphQLWebsitesConfig websitesConfig = BundleUtils.getOsgiService(GraphQLWebsitesConfig.class, null);

        // Check the S3 precondition BEFORE exporting. This used to run after exportAllSites(...),
        // which meant an unconfigured instance built a full archive of every site — potentially
        // minutes of CPU and gigabytes of disk — only for the finally block to delete it unread.
        // Nothing downstream of this point is reachable without configuration, so failing here is
        // both cheaper and a more honest "precondition".
        if (!websitesConfig.isConfigured()) {
            LOGGER.error("exportAllSites: AWS S3 is not configured (PID {}); no export performed",
                    "org.jahia.community.graphql.websites");
            return ExportAllSitesResults.AWS_S3_BUCKET_NOT_CONFIGURED;
        }

        SettingsBean settingsBean = BundleUtils.getOsgiService(SettingsBean.class, null);
        // Fix I: build path with Paths.get + normalize instead of String concatenation with File.separator
        final Path exportFile = Paths.get(settingsBean.getJahiaVarDiskPath(), "exports",
                        buildExportFileName(LocalDateTime.now(ZoneOffset.UTC)))
                .toAbsolutePath().normalize();
        try {
            exportAllSites(exportFile);
            uploadExport(exportFile, websitesConfig);
        } catch (Exception e) {
            throw new DataFetchingException(e);
        } finally {
            deleteExportArtifact(exportFile, "exportAllSites");
        }
        return ExportAllSitesResults.SUCCESS;
    }

    /**
     * Builds the file name for a bulk export archive.
     *
     * <p>The name doubles as the S3 object key (see {@link #uploadExport}), so it must be
     * unique per invocation. A timestamp alone is not: the previous {@code yyyyMMddHHmm}
     * format was minute-granular, so two exports started in the same minute resolved to the
     * same path — the second overwrote the first's archive mid-upload, and the {@code finally}
     * block of whichever finished first deleted the file out from under the other. Seconds
     * narrow the window but do not close it, so a random suffix makes the name collision-free
     * regardless of timing. The leading timestamp is retained so archives still sort
     * chronologically in the bucket.
     *
     * @param timestamp export start time, expected in UTC
     * @return a file name of the form {@code export-<yyyyMMddHHmmss>-<8 hex chars>.zip}
     */
    static String buildExportFileName(LocalDateTime timestamp) {
        final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, EXPORT_SUFFIX_LENGTH);
        return "export-" + EXPORT_TIMESTAMP_FORMAT.format(timestamp) + "-" + suffix + ".zip";
    }

    /**
     * Deletes a transient export artifact, logging when a file that exists cannot be removed.
     *
     * <p>Replaces a bare {@code FileUtils.deleteQuietly}, which discards the failure entirely
     * and leaves stale archives accumulating in the exports directory with no trace. Cleanup
     * failure must not fail the surrounding mutation, so this still swallows the error — it
     * just stops swallowing it <em>silently</em>. A file that was never created is not a
     * failure and is not logged.
     */
    private static void deleteExportArtifact(Path path, String operation) {
        final File file = path.toFile();
        if (!file.exists()) {
            return;
        }
        if (!FileUtils.deleteQuietly(file)) {
            LOGGER.warn("{}: failed to delete export artifact '{}'; it will remain on disk", operation, path);
        }
    }

    /** True only when the current caller holds the full-administrator permission at the repository root. */
    private static boolean callerIsServerAdministrator() {
        try {
            return JCRSessionFactory.getInstance().getCurrentUserSession().getNode("/").hasPermission("admin");
        } catch (RepositoryException e) {
            LOGGER.error("Unable to verify administrator permission", e);
            return false;
        }
    }

    private static void exportAllSites(Path exportFile) throws IOException, RepositoryException, JahiaException, SAXException, TransformerException {
        LOGGER.info("<<< Export all sites job");
        Map<String, Object> params = buildAllSitesExportParams(JahiaContextLoaderListener.getServletContext().getRealPath("/WEB-INF/etc/repository/export/cleanup.xsl"));

        // SEC-136: export as the CURRENT caller — do NOT switch the session user to root. Previously this
        // elevated to root and dumped getSitesNodeList() (every site's content, users and roles), so a holder
        // of the delegated `websitesAdmin` role could exfiltrate the entire instance including content they
        // cannot normally read. Running under the caller's own session confines the archive to authorized content.
        try (FileOutputStream fos = new FileOutputStream(exportFile.toFile())) {
            ((ImportExportBaseService) SpringContextSingleton.getBean("ImportExportService")).exportSites(new BufferedOutputStream(fos), params, JahiaSitesService.getInstance().getSitesNodeList());
        }
        LOGGER.info(">>> END Export all sites job");
    }

    private static void uploadExport(Path exportFile, GraphQLWebsitesConfig websitesConfig) {
        final String awsS3Region = websitesConfig.getAwsS3Region();
        final String awsS3AccessKey = websitesConfig.getAwsS3AccessKey();
        final String awsS3BucketName = websitesConfig.getAwsS3BucketName();
        final String awsS3SecretAccessKey = websitesConfig.getAwsS3SecretAccessKey();
        LOGGER.info("<<< Upload exportFile: {}", exportFile);
        try (S3TransferManager s3TransferManager = S3TransferManager.builder()
                .s3Client(S3AsyncClient.crtBuilder()
                        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(awsS3AccessKey, awsS3SecretAccessKey)))
                        .region(Region.of(awsS3Region))
                        .targetThroughputInGbps(S3_TARGET_THROUGHPUT_GBPS)
                        .minimumPartSizeInBytes(S3_MINIMUM_PART_SIZE_BYTES)
                        .build())
                .build()) {
            LOGGER.info("ETag: {}", s3TransferManager.uploadFile(builder -> builder.putObjectRequest(b -> b.bucket(awsS3BucketName).key(exportFile.getFileName().toString()))
                    .addTransferListener(LoggingTransferListener.create())
                    .source(exportFile)
                    .build()).completionFuture().join().response().eTag());
        }
        LOGGER.info(">>> END upload exportFile: {}", exportFile);
    }

    /**
     * Outcomes of {@link #exportAllSites()} that the caller can act on.
     *
     * <p>This is deliberately <em>not</em> a general error enum: unexpected failures are raised as
     * {@link DataFetchingException} instead, so their cause survives. Only add a constant here for
     * an outcome an operator can actually remedy — see the {@code exportAllSites()} javadoc for the
     * reasoning behind the split.
     */
    public enum ExportAllSitesResults {
        /** Export and S3 upload both completed; the local archive was removed. */
        SUCCESS,
        /**
         * One or more S3 settings are blank, so nothing was uploaded — and, since the check runs
         * first, nothing was exported either. Remedy by completing the configuration under PID
         * {@code org.jahia.community.graphql.websites}.
         */
        AWS_S3_BUCKET_NOT_CONFIGURED,
        /**
         * The caller is not a server administrator, so nothing was exported (SEC-136 §4.3).
         *
         * <p>This is an <em>actionable</em> outcome, which is why it belongs in this enum rather
         * than being raised as a GraphQL error: the operator remedies it by granting the caller
         * the {@code admin} role at the repository root. It mirrors how {@link #importWebsite}
         * self-aborts rather than throwing when its administrator gate is not met.
         */
        NOT_SERVER_ADMINISTRATOR
    }
}
