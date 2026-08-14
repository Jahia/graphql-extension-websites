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
 * <p><b>Permission:</b> every mutation in this class is gated by the
 * {@code websitesAdmin} permission via {@link GraphQLRequiresPermission}.  The caller
 * must hold that permission; unauthenticated or insufficiently privileged requests are
 * rejected by the GraphQL security layer before the method body executes.
 *
 * <p><b>Privilege scope of {@link #exportAllSites()} (SEC-136):</b> that mutation runs
 * the JCR export under the <em>caller's own</em> session — it does <b>not</b> escalate to
 * the root user. The archive is therefore confined to the content the caller is authorized
 * to read; a {@code websitesAdmin} holder cannot use it to exfiltrate content they cannot
 * otherwise access. See the README for S3 configuration details.
 *
 * <p><b>Why the privilege scopes differ between mutations.</b> The three tiers below are
 * deliberate, not an inconsistency — each reflects whether the operation can degrade safely
 * when it is denied rights:
 * <ul>
 *   <li>{@link #exportAllSites()} and {@link #exportWebsite} run <em>as the caller</em>.
 *       An export degrades gracefully: a session with fewer read rights simply produces a
 *       smaller archive, so de-escalating bounds the blast radius at no functional cost.</li>
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
     * Creates a Jahia website with the supplied parameters.
     *
     * @return {@code true} on success; {@code false} if site creation fails (e.g. the
     *         template set is not installed, or a site with that key already exists)
     */
    @GraphQLField
    @GraphQLDescription("Create a website")
    @GraphQLRequiresPermission("websitesAdmin")
    public Boolean createSiteByKey(
            @GraphQLName("siteKey") @GraphQLDescription("Site key") String siteKey,
            @GraphQLName("serverName") @GraphQLDescription("Server name") String serverName,
            @GraphQLName("serverNameAliasesAsString") @GraphQLDescription("Server name aliases") String serverNameAliases,
            @GraphQLName("title") @GraphQLDescription("Title") String title,
            @GraphQLName("templateSet") @GraphQLDescription("Template set") String templateSet,
            @GraphQLName("modulesToDeploy") @GraphQLDescription("Modules to deploy") String[] modulesToDeploy,
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
                    siteCreationInfo.setModulesToDeploy(modulesToDeploy);
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
     * <p>Only {@link JahiaException} — the domain failure {@code removeSite} declares — is
     * translated to {@code false}. Unchecked exceptions propagate to the GraphQL layer rather
     * than being reported as an ordinary "deletion failed", matching how
     * {@link #exportAllSites()} surfaces unexpected errors as {@link DataFetchingException}.
     * The previous {@code catch (JahiaException | RuntimeException)} conflated the two, so a
     * bug such as an NPE was indistinguishable from a site that genuinely could not be deleted.
     *
     * @return {@code true} on success; {@code false} if the site was not found or deletion
     *         fails
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
            jahiaSitesServices.removeSite(jahiaSite);
            success = Boolean.TRUE;
        } catch (JahiaException ex) {
            LOGGER.error(ERR_MSG_IMP_TO_DELETE_SITE, siteKey, ex);
        }
        return success;
    }

    /**
     * Exports a single website to an on-disk directory.
     *
     * <p>This mutation is {@link GraphQLAsync}: the GraphQL response is returned before the
     * export completes.  Clients cannot poll for completion via this mutation.
     *
     * @param siteKey      key of the site to export
     * @param exportPath   path relative to {@code jahiaVarDiskPath/exports/}; must not
     *                     escape that directory
     * @param onlyStaging  when {@code true} only staging content is included
     * @return {@code true} on success; {@code false} on error or invalid path
     */
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

    @GraphQLField
    @GraphQLDescription("Export a website")
    @GraphQLAsync
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
        Boolean successful = Boolean.TRUE;
        final Path importsBaseDir = Paths.get(BundleUtils.getOsgiService(SettingsBean.class, null).getJahiaImportsDiskPath()).toAbsolutePath().normalize();
        final Path absoluteImportPath;
        try {
            absoluteImportPath = PathSecurity.resolveContained(importsBaseDir, importPath);
            // siteKey is untrusted GraphQL input used as a directory name; reject traversal.
            PathSecurity.resolveContained(absoluteImportPath, siteKey);
        } catch (IllegalArgumentException ex) {
            LOGGER.error("importWebsite: rejected import location (importPath '{}', siteKey '{}'): {}", importPath, siteKey, ex.getMessage());
            return Boolean.FALSE;
        }
        try (InputStream input = new FileInputStream(Paths.get(absoluteImportPath.toString(), "export.properties").toString())) {
            final Properties exportProperties = new Properties();
            exportProperties.load(input);
            final List<ImportInfo> importsInfos = new ArrayList<>();
            ImportInfo importInfo;

            importInfo = new ImportInfo();
            importInfo.setSiteKey("systemsite");
            importInfo.setImportFile(Paths.get(absoluteImportPath.toString(), "roles").toFile());
            importInfo.setImportFileName(ImportExportBaseService.ROLES_ZIP);
            importInfo.setSelected(true);
            importInfo.setType(FILES);
            importInfo.setOriginatingJahiaRelease(exportProperties.getProperty(JAHIA_RELEASE));
            importsInfos.add(importInfo);

            importInfo = new ImportInfo();
            importInfo.setSiteKey(null);
            importInfo.setImportFile(Paths.get(absoluteImportPath.toString(), "users").toFile());
            importInfo.setImportFileName(ImportExportBaseService.USERS_ZIP);
            importInfo.setSelected(true);
            importInfo.setType(FILES);
            importInfo.setOriginatingJahiaRelease(exportProperties.getProperty(JAHIA_RELEASE));
            importsInfos.add(importInfo);

            importInfo = new ImportInfo();
            importInfo.setSiteKey(siteKey);
            importInfo.setImportFile(Paths.get(absoluteImportPath.toString(), siteKey).toFile());
            importInfo.setImportFileName(siteKey);
            importInfo.setSelected(true);
            importInfo.setType(SITE);
            importInfo.setOriginatingJahiaRelease(exportProperties.getProperty(JAHIA_RELEASE));
            importsInfos.add(importInfo);
            final ImportExportBaseService importExportBaseService = ServicesRegistry.getInstance().getImportExportService();
            final JahiaSitesService jahiaSitesService = ServicesRegistry.getInstance().getJahiaSitesService();

            importUsers(importExportBaseService, importsInfos);

            boolean anythingImported = false;

            for (final ImportInfo infos : importsInfos) {
                if (infos.isSelected()) {
                    String type = infos.getType();
                    if (type.equals(FILES)) {
                        // Fix H: only mark imported when importFiles reports success
                        boolean fileImported = importFiles(importExportBaseService, jahiaSitesService, importsInfos, infos);
                        if (fileImported) {
                            anythingImported = true;
                        }
                    } else if (type.equals(SITE)) {
                        // site import
                        anythingImported = true;
                        successful = importSite(jahiaSitesService, infos, absoluteImportPath.toString());
                    }
                }

                if (anythingImported) {
                    CompositeSpellChecker.updateSpellCheckerIndex();
                }
            }
        } catch (IOException ex) {
            LOGGER.error("Impossible to read file export.properties", ex);
            successful = Boolean.FALSE;
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
     * <p><b>Permission:</b> requires {@code websitesAdmin}.
     *
     * <p><b>Privilege scope (SEC-136):</b> the JCR export runs under the <em>caller's own</em>
     * session — it does <b>not</b> switch the session user to root. The resulting archive is
     * confined to the content the caller is authorized to read (enumerated via
     * {@link JahiaSitesService#getSitesNodeList()}), so a {@code websitesAdmin} holder cannot
     * use it to capture content beyond their own read rights.
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
     * @return {@link ExportAllSitesResults#SUCCESS} on success, or
     *         {@link ExportAllSitesResults#AWS_S3_BUCKET_NOT_CONFIGURED} if S3 is not configured
     * @throws DataFetchingException on any unexpected failure during export or upload
     */
    @GraphQLField
    @GraphQLDescription("Export all sites to the configured S3 bucket. Returns AWS_S3_BUCKET_NOT_CONFIGURED "
            + "(without exporting) when S3 configuration is incomplete; raises a GraphQL error for any "
            + "unexpected export or upload failure.")
    @GraphQLRequiresPermission("websitesAdmin")
    public ExportAllSitesResults exportAllSites() {
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
        AWS_S3_BUCKET_NOT_CONFIGURED
    }
}
