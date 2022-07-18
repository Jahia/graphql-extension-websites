package org.jahia.community.graphql.provider.dxm.extensions.websites;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.jcr.RepositoryException;
import javax.xml.transform.TransformerException;
import org.apache.commons.collections.ExtendedProperties;
import org.jahia.commons.Version;
import org.jahia.exceptions.JahiaException;
import org.jahia.modules.graphql.provider.dxm.admin.GqlJahiaAdminMutation;
import org.jahia.registries.ServicesRegistry;
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
import org.jahia.settings.SettingsBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.xml.sax.SAXException;

@GraphQLTypeExtension(GqlJahiaAdminMutation.class)
public class WebsitesMutation {

    private static final String JAHIA_RELEASE = "JahiaRelease";
    private static final Logger LOGGER = LoggerFactory.getLogger(WebsitesMutation.class);
    private static final String ERR_MSG_ERR_WHEN_GETTING_TPL = "Error when getting templates";
    private static final String ERR_MSG_IMP_TO_CREATE_SITE = "Impossible to create website %s";
    private static final String FILES = "files";
    private static final String SITE = "site";

    @GraphQLField
    @GraphQLDescription("Create a website")
    public static Boolean createSiteByKey(
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
                    LOGGER.error(String.format(ERR_MSG_IMP_TO_CREATE_SITE, siteKey), ex);
                    result = Boolean.FALSE;
                }
                return result;
            });
        } catch (RepositoryException ex) {
            LOGGER.error(String.format(ERR_MSG_IMP_TO_CREATE_SITE, siteKey), ex);
        }
        return Boolean.FALSE;
    }

    @GraphQLField
    @GraphQLDescription("Delete a website")
    public static Boolean deleteSiteByKey(
            @GraphQLName("siteKey") @GraphQLDescription("Site key") String siteKey
    ) {
        try {
            final JahiaSitesService jahiaSitesServices = ServicesRegistry.getInstance().getJahiaSitesService();
            final JahiaSite jahiaSite = jahiaSitesServices.getSiteByKey(siteKey);
            jahiaSitesServices.removeSite(jahiaSite);
            return Boolean.TRUE;
        } catch (JahiaException ex) {
            LOGGER.error(String.format(ERR_MSG_IMP_TO_CREATE_SITE, siteKey), ex);
        }
        return Boolean.FALSE;
    }

    @GraphQLField
    @GraphQLDescription("Export a website")
    public static Boolean exportWebsite(
            @GraphQLName("siteKey") @GraphQLDescription("Site key") String siteKey,
            @GraphQLName("exportPath") @GraphQLDescription("Export path") String exportPath,
            @GraphQLName("onlyStaging") @GraphQLDescription("Export only staging content") boolean onlyStaging
    ) {
        try {
            final Map<String, Object> params = new HashMap<>(6);
            params.put(ImportExportService.VIEW_CONTENT, true);
            params.put(ImportExportService.VIEW_VERSION, false);
            params.put(ImportExportService.VIEW_ACL, true);
            params.put(ImportExportService.VIEW_METADATA, true);
            params.put(ImportExportService.VIEW_JAHIALINKS, true);
            params.put(ImportExportService.VIEW_WORKFLOW, true);
            params.put(ImportExportService.SERVER_DIRECTORY, exportPath);
            params.put(ImportExportService.INCLUDE_ALL_FILES, true);
            params.put(ImportExportService.INCLUDE_TEMPLATES, true);
            params.put(ImportExportService.INCLUDE_SITE_INFOS, true);
            params.put(ImportExportService.INCLUDE_DEFINITIONS, true);
            params.put(ImportExportService.INCLUDE_LIVE_EXPORT, !onlyStaging);
            params.put(ImportExportService.INCLUDE_USERS, true);
            params.put(ImportExportService.INCLUDE_ROLES, true);
            final String cleanupXsl = SettingsBean.getInstance().getJahiaEtcDiskPath() + "/repository/export/cleanup.xsl";
            params.put(ImportExportService.XSL_PATH, cleanupXsl);

            final List<JCRSiteNode> siteList = new ArrayList<>();
            final JahiaSite site = ServicesRegistry.getInstance().getJahiaSitesService().getSiteByKey(siteKey);
            siteList.add((JCRSiteNode) site);
            final ImportExportBaseService importExportBaseService = ServicesRegistry.getInstance().getImportExportService();
            importExportBaseService.exportSites(new ByteArrayOutputStream(), params, siteList);
            return Boolean.TRUE;
        } catch (JahiaException | RepositoryException | IOException | SAXException | TransformerException ex) {
            LOGGER.error(String.format("Impossible to export website %s", siteKey), ex);
        }
        return Boolean.FALSE;
    }

    @GraphQLField
    @GraphQLDescription("Import a website")
    public static Boolean importWebsite(@GraphQLName("importPath") @GraphQLDescription("Import path") String importPath,
            @GraphQLName("siteKey") @GraphQLDescription("Site key") String siteKey) throws IOException {
        LOGGER.info("Processing Import");
        Boolean successful = Boolean.TRUE;
        final Path absoluteImportPath = Paths.get(SettingsBean.getInstance().getJahiaImportsDiskPath(), importPath);
        try ( InputStream input = new FileInputStream(Paths.get(absoluteImportPath.toString(), "export.properties").toString())) {
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
                        anythingImported = true;
                        importFiles(importExportBaseService, jahiaSitesService, importsInfos, infos);
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
            if (infos.isSelected() && infos.getImportFileName().equals(ImportExportBaseService.USERS_XML)) {
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

    private static void importFiles(ImportExportBaseService importExportBaseService, JahiaSitesService jahiaSitesService, List<ImportInfo> importsInfos, ImportInfo infos) {
        try {
            final File file = ImportUpdateService.getInstance().updateImport(
                    infos.getImportFile(),
                    infos.getImportFileName(),
                    infos.getType(),
                    new Version(infos.getOriginatingJahiaRelease()));
            final JahiaSite system = jahiaSitesService.getSiteByKey(JahiaSitesService.SYSTEM_SITE_KEY);

            final Map<String, String> pathMapping = JCRSessionFactory.getInstance()
                    .getCurrentUserSession().getPathMapping();
            String pathStart = "/sites/";
            pathMapping.put("/shared/files/", pathStart + system.getSiteKey() + "/files/");
            pathMapping.put("/shared/mashups/", pathStart + system.getSiteKey() + "/portlets/");
            importsInfos.stream().filter(infos2 -> (infos2.getOldSiteKey() != null && infos2.getSiteKey() != null && !infos2.getOldSiteKey().equals(infos2.getSiteKey()))).forEachOrdered((ImportInfo infos2)
                    -> pathMapping.put(pathStart + infos2.getOldSiteKey(), pathStart + infos2.getSiteKey())
            );

            JCRTemplate.getInstance().doExecuteWithSystemSession((JCRSessionWrapper session) -> {
                try {
                    session.getPathMapping().putAll(pathMapping);
                    importExportBaseService.importSiteZip(file == null ? null : new FileSystemResource(file),
                            system,
                            infos.asMap(),
                            null,
                            null,
                            session);
                } catch (IOException | RepositoryException ex) {
                    LOGGER.error(ERR_MSG_ERR_WHEN_GETTING_TPL, ex);
                }
                return null;
            });
        } catch (NumberFormatException | RepositoryException | JahiaException ex) {
            LOGGER.error(ERR_MSG_ERR_WHEN_GETTING_TPL, ex);
        }
    }

    private static boolean importSite(JahiaSitesService jahiaSitesService, ImportInfo infos, String absoluteImportPath) {
        Boolean successful = Boolean.TRUE;
        try ( InputStream inputSite = new FileInputStream(Paths.get(absoluteImportPath, infos.getSiteKey(), "site.properties").toString())) {
            final ExtendedProperties siteProperties = new ExtendedProperties();
            siteProperties.load(inputSite);
            final File file = ImportUpdateService.getInstance().updateImport(
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
            JCRObservationManager.doWithOperationType(null, JCRObservationManager.IMPORT, (JCRSessionWrapper jcrSession) -> {
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

        } catch (Exception e) {
            LOGGER.error("Cannot create site " + infos.getSiteTitle(), e);
            successful = Boolean.FALSE;
        }
        return successful;
    }
}
