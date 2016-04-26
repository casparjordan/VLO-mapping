package eu.clarin.cmdi.vlo;

import de.agilecoders.wicket.core.Bootstrap;
import de.agilecoders.wicket.core.settings.BootstrapSettings;
import de.agilecoders.wicket.core.settings.SingleThemeProvider;
import de.agilecoders.wicket.core.settings.Theme;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import javax.inject.Inject;

import org.apache.wicket.Application;
import org.apache.wicket.markup.html.WebPage;
import org.apache.wicket.protocol.http.WebApplication;
import org.apache.wicket.resource.loader.BundleStringResourceLoader;
import org.apache.wicket.spring.injection.annot.SpringComponentInjector;
import org.apache.wicket.util.lang.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import eu.clarin.cmdi.vlo.config.VloConfig;
import eu.clarin.cmdi.vlo.service.FacetDescriptionService;
import eu.clarin.cmdi.vlo.service.PermalinkService;
import eu.clarin.cmdi.vlo.service.XmlTransformationService;
import eu.clarin.cmdi.vlo.service.solr.SolrDocumentService;
import eu.clarin.cmdi.vlo.wicket.FragmentEncodingMountedMapper;
import eu.clarin.cmdi.vlo.wicket.pages.AboutPage;
import eu.clarin.cmdi.vlo.wicket.pages.AllFacetValuesPage;
import eu.clarin.cmdi.vlo.wicket.pages.FacetedSearchPage;
import eu.clarin.cmdi.vlo.wicket.pages.HelpPage;
import eu.clarin.cmdi.vlo.wicket.pages.RecordPage;
import eu.clarin.cmdi.vlo.wicket.pages.SimpleSearchPage;
import eu.clarin.cmdi.vlo.wicket.pages.VloBasePage;
import eu.clarin.cmdi.vlo.wicket.provider.FieldValueConverterProvider;
import java.util.Arrays;
import java.util.List;
import org.apache.wicket.Page;
import org.apache.wicket.markup.head.CssHeaderItem;
import org.apache.wicket.markup.head.HeaderItem;
import org.apache.wicket.request.resource.CssResourceReference;

/**
 * Application object for your web application. If you want to run this
 * application without deploying, run the Start class.
 *
 * @see eu.clarin.cmdi.Start#main(String[])
 */
public class VloWicketApplication extends WebApplication implements ApplicationContextAware {

    private final static Logger logger = LoggerFactory.getLogger(VloWicketApplication.class);

    @Inject
    private SolrDocumentService documentService;
    @Inject
    private XmlTransformationService cmdiTransformationService;
    @Inject
    private FieldValueConverterProvider fieldValueConverterProvider;
    @Inject
    private FacetDescriptionService facetDescriptionService;
    @Inject
    private PermalinkService permalinkService;
    @Inject
    private VloConfig vloConfig;

    private ApplicationContext applicationContext;
    private String appVersionQualifier;

    /**
     * @return the home page of this application
     * @see org.apache.wicket.Application#getHomePage()
     */
    @Override
    public Class<? extends WebPage> getHomePage() {
        return SimpleSearchPage.class;
    }

    /**
     * @see org.apache.wicket.Application#init()
     */
    @Override
    public void init() {
        super.init();

        initBootstrap();

        // register global resource bundles (from .properties files)
        registerResourceBundles();

        // mount pages on URL paths
        mountPages();

        // configure Wicket cache according to parameters set in VloConfig 
        setupCache();

        // determine version qualifier (e.g. 'beta'), which can be used to visually mark the base page
        appVersionQualifier = determineVersionQualifier();
        logger.info("Version qualifier: {}", appVersionQualifier);
    }

    private void registerResourceBundles() {
        // this listener will inject any spring beans that need to be autowired
        getComponentInstantiationListeners().add(new SpringComponentInjector(this, applicationContext));
        // register the resource of field names (used by eu.clarin.cmdi.vlo.wicket.componentsSolrFieldNameLabel)
        getResourceSettings().getStringResourceLoaders().add(new BundleStringResourceLoader("fieldNames"));
        // register the resource of resource type names and class properties
        getResourceSettings().getStringResourceLoaders().add(new BundleStringResourceLoader("resourceTypes"));
        // register the resource of license URLs (used in RecordLicenseInfoPanel)
        getResourceSettings().getStringResourceLoaders().add(new BundleStringResourceLoader("licenseUrls"));
        // register the resource of application properties (version information filtered at build time)
        getResourceSettings().getStringResourceLoaders().add(new BundleStringResourceLoader("application"));
        // register JavaScript bundle (combines  JavaScript source in a single resource to decrease number of client requests)
        getResourceBundles().addJavaScriptBundle(VloBasePage.class, "vlo-js",
                JavaScriptResources.getVloFrontJS(),
                JavaScriptResources.getVloHeaderJS(),
                JavaScriptResources.getSyntaxHelpJS(),
                JavaScriptResources.getVloFacetsJS(),
                JavaScriptResources.getJQueryWatermarkJS()
        );
    }

    private void mountPages() {
        // Faceted search page (simple search is on root)
        mountPage("/search", FacetedSearchPage.class);
        // Record (query result) page. E.g. /vlo/record?docId=abc123
        // (cannot encode docId in path because it contains a slash)
        mountPageWithFragmentSupport("/record", RecordPage.class);
        // All facet values page (kept for compatibility with old bookmarks)
        // E.g. /vlo/values/genre?facetMinOccurs=1 (min occurs not in path 
        // because it's a filter on the facet list)
        mountPage("/values/${" + AllFacetValuesPage.SELECTED_FACET_PARAM + "}", AllFacetValuesPage.class);
        // About page
        mountPage("/about", AboutPage.class);
        // Help page
        mountPage("/help", HelpPage.class);
    }

    private void setupCache() {
        // configure cache by applying the vlo configuration settings to it
        final int pagesInApplicationCache = vloConfig.getPagesInApplicationCache();
        logger.info("Setting Wicket in-memory cache size to {}", pagesInApplicationCache);
        this.getStoreSettings().setInmemoryCacheSize(pagesInApplicationCache);

        final Bytes sessionCacheSize = Bytes.kilobytes((long) vloConfig.getSessionCacheSize());
        logger.info("Setting Wicket max size per session to {}", sessionCacheSize);
        this.getStoreSettings().setMaxSizePerSession(sessionCacheSize);
    }

    /**
     *
     * @return a version qualifier, either 'snapshot', 'beta' or null
     */
    private String determineVersionQualifier() {
        try (InputStream applicationPropertiesStream = getClass().getResourceAsStream("/application.properties")) {
            Properties applicationProperties = new Properties();
            applicationProperties.load(applicationPropertiesStream);
            final String version = applicationProperties.getProperty("vlo.version");
            if (version != null) {
                if (version.endsWith("-SNAPSHOT")) {
                    return "snapshot";
                } else if (version.contains("beta")) {
                    return "beta";
                }
            }
        } catch (IOException ex) {
            logger.error("Could not read application properties on init", ex);
        }
        return null;
    }

    /**
     *
     * @return the active VLO wicket application
     */
    public static VloWicketApplication get() {
        return (VloWicketApplication) Application.get();
    }

    /**
     * Method needed for dynamic injection of application context (as happens in
     * unit tests)
     *
     * @param applicationContext
     * @throws BeansException
     */
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     *
     * @return a service that retrieves SolrDocuments from the attached index
     */
    public SolrDocumentService getDocumentService() {
        return documentService;
    }

    /**
     *
     * @return a service that transforms CMDI documents to HTML representations
     */
    public XmlTransformationService getCmdiTransformationService() {
        return cmdiTransformationService;
    }

    public FieldValueConverterProvider getFieldValueConverterProvider() {
        return fieldValueConverterProvider;
    }

    public FacetDescriptionService getFacetDescriptionService() {
        return facetDescriptionService;
    }

    public PermalinkService getPermalinkService() {
        return permalinkService;
    }

    public String getAppVersionQualifier() {
        return appVersionQualifier;
    }

    /**
     * Like {@link #mountPage(java.lang.String, java.lang.Class) }, but using
     * {@link FragmentEncodingMountedMapper}
     *
     * @param <T> type of page
     *
     * @param path the path to mount the page class on
     * @param pageClass the page class to be mounted
     * @return the mapper that provides the mount point
     *
     * @see WebApplication#mountPage(java.lang.String, java.lang.Class)
     */
    public <T extends Page> FragmentEncodingMountedMapper mountPageWithFragmentSupport(String path, Class<T> pageClass) {
        final FragmentEncodingMountedMapper mapper = new FragmentEncodingMountedMapper(path, pageClass);
        mount(mapper);
        return mapper;
    }

    private void initBootstrap() {
        BootstrapSettings settings = new BootstrapSettings();
        settings.setThemeProvider(new SingleThemeProvider(new ClarinBootstrapTheme()));
        Bootstrap.install(this, settings);
    }

    static class ClarinBootstrapTheme extends Theme {

        public ClarinBootstrapTheme() {
            super("clarin-theme");
        }

        @Override
        public List<HeaderItem> getDependencies() {
            return Arrays.<HeaderItem>asList(
                    //TODO: Make bundle?
                    CssHeaderItem.forReference(Bootstrap.getSettings().getCssResourceReference()),
                    CssHeaderItem.forReference(new CssResourceReference(VloBasePage.class, "css/clarin.css")),
                    CssHeaderItem.forReference(new CssResourceReference(VloBasePage.class, "css/clarin-override.css"))
            );
        }
    }

}
