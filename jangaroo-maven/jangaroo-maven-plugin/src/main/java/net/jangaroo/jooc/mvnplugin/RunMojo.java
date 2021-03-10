package net.jangaroo.jooc.mvnplugin;

import net.jangaroo.apprunner.proxy.AddDynamicPackagesServlet;
import net.jangaroo.apprunner.proxy.AdditionalPackagesFromFolderServlet;
import net.jangaroo.apprunner.util.JettyWrapper;
import net.jangaroo.apprunner.util.ProxyServletConfig;
import net.jangaroo.apprunner.util.StaticResourcesServletConfig;
import net.jangaroo.jooc.mvnplugin.sencha.SenchaUtils;
import net.jangaroo.jooc.mvnplugin.util.MavenPluginHelper;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.jetty.maven.plugin.JettyWebAppContext;
import org.slf4j.impl.StaticLoggerBinder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static net.jangaroo.apprunner.util.JettyWrapper.ROOT_PATH;
import static net.jangaroo.jooc.mvnplugin.sencha.SenchaUtils.ADDITIONAL_PACKAGES_PATH;
import static net.jangaroo.jooc.mvnplugin.sencha.SenchaUtils.APPS_DIRECTORY_NAME;
import static net.jangaroo.jooc.mvnplugin.sencha.SenchaUtils.APP_DIRECTORY_NAME;
import static net.jangaroo.jooc.mvnplugin.sencha.SenchaUtils.DYNAMIC_PACKAGES_FILENAME;
import static net.jangaroo.jooc.mvnplugin.sencha.SenchaUtils.EXT_DIRECTORY_NAME;
import static net.jangaroo.jooc.mvnplugin.sencha.SenchaUtils.LOCAL_APPS_PATH;
import static net.jangaroo.jooc.mvnplugin.sencha.SenchaUtils.LOCAL_PACKAGES_PATH;
import static net.jangaroo.jooc.mvnplugin.sencha.SenchaUtils.PACKAGES_DIRECTORY_NAME;
import static net.jangaroo.jooc.mvnplugin.sencha.SenchaUtils.SEPARATOR;

/**
 * Starts a Jetty server serving the static resources of the workspace of an app or unit test app.
 * <br>
 * If the parameters {@code jooProxyTargetUri} and {@code jooProxyPathSpec} are provided, all requests matching the
 * {@code jooProxyPathSpec} are proxied to the {@code jooProxyTargetUri}.
 * This is convenient to proxy-in some HTTP(S)-based service.
 */
@Mojo(
        name = "run",
        requiresDependencyResolution = ResolutionScope.TEST
)
public class RunMojo extends AbstractSenchaMojo {

  /**
   * The host name of the started server. Defaults to 'localhost'.
   * To expose the server on all network interfaces, use 0.0.0.0 instead.
   */
  @Parameter(property = "jooJettyHost")
  private String jooJettyHost = "localhost";

  /**
   * The port of the started server. Defaults to 8080.
   */
  @Parameter(property = "jooJettyPort")
  private int jooJettyPort = 8080;

  /**
   * The url to which all proxied requests are forwarded to.
   */
  @Parameter(property = "jooProxyTargetUri")
  private String jooProxyTargetUri;

  public void setJooProxyTargetUri(String jooProxyTargetUri) {
    this.jooProxyTargetUri = jooProxyTargetUri.endsWith(SEPARATOR) ? jooProxyTargetUri : jooProxyTargetUri + SEPARATOR;
  }

  /**
   * The pattern that determines which requests should be proxied.
   */
  @Parameter(property = "jooProxyPathSpec")
  private String jooProxyPathSpec;

  /**
   * The configurations for serving static resources.
   * The resource base paths have to be relative to the Sencha workspace root.
   * <br>
   * Per default all resources below the Sencha workspace root are served at '/'.
   * <p>
   *   <b>Experimental</b>
   * </p>
   */
  @Parameter
  private List<StaticResourcesServletConfig> jooStaticResourcesServletConfigs = Collections.emptyList();

  /**
   * The configurations for a proxy servlet.
   * Used only when {@code jooProxyTargetUri} and {@code jooProxyPathSpec} are not set
   * <p>
   *   <b>Experimental</b>
   * </p>
   */
  @Parameter
  private List<ProxyServletConfig> jooProxyServletConfigs;

  /**
   * Set the list of absolute file paths of directories containing an additional 'packages' subdirectory.
   * Use this to complement an application with plugins that are developed in
   * dedicated workspaces available under some local file paths.
   * For each plugin, this is usually the 'target/app' directory of the Jangaroo App plugin module
   * (the one that uses the Maven goal 'package-plugin' to produce the plugin ZIP).
   */
  @Parameter(property = "additionalPackagesDirs")
  private File[] additionalPackagesDirs;

  @Override
  public void execute() throws MojoExecutionException {
    boolean isSwcPackaging = Type.JANGAROO_SWC_PACKAGING.equals(project.getPackaging());
    boolean isAppPackaging = Type.JANGAROO_APP_PACKAGING.equals(project.getPackaging());
    boolean isAppOverlayPackaging = Type.JANGAROO_APP_OVERLAY_PACKAGING.equals(project.getPackaging());
    boolean isAppsPackaging = Type.JANGAROO_APPS_PACKAGING.equals(project.getPackaging());
    boolean isProxyRootPath = JettyWrapper.ROOT_PATH_SPEC.equals(jooProxyPathSpec);

    File baseDir = isAppPackaging || isAppOverlayPackaging || (isSwcPackaging && isProxyRootPath)
            ? new File(project.getBuild().getDirectory(), APP_DIRECTORY_NAME)
            : isSwcPackaging ? new File(project.getBuild().getTestOutputDirectory())
            : isAppsPackaging ? new File(project.getBuild().getDirectory(), APPS_DIRECTORY_NAME)
            : null;

    if (baseDir == null) {
      getLog().info(String.format("jangaroo:run does not support packaging '%s' (module %s:%s).", project.getPackaging(), project.getGroupId(), project.getArtifactId()));
      return;
    }

    StaticLoggerBinder.getSingleton().setLog(getLog());
    JettyWrapper jettyWrapper = new JettyWrapper(baseDir.toPath());
    jettyWrapper.setWebAppContextClass(JettyWebAppContext.class);

    List<StaticResourcesServletConfig> staticResourcesServletConfigs = new ArrayList<>(jooStaticResourcesServletConfigs);
    String senchaPackageName = null;
    if (isSwcPackaging) {
      senchaPackageName = SenchaUtils.getSenchaPackageName(project);
      staticResourcesServletConfigs.add(new StaticResourcesServletConfig(LOCAL_PACKAGES_PATH + senchaPackageName + SEPARATOR + "*"));
    }
    if ((isAppOverlayPackaging || isSwcPackaging) && isProxyRootPath) {
      // If root path, the developer wants to proxy-in the base app, so all static resources are already there.
      // We just need to add all overlay packages as static resource folders:
      File[] packageDirs = new File(baseDir, PACKAGES_DIRECTORY_NAME).listFiles(File::isDirectory);
      if (packageDirs != null) {
        List<String> packageNames = Arrays.stream(packageDirs).map(File::getName).collect(Collectors.toList());
        for (String packageName : packageNames) {
          if (!packageName.equals(senchaPackageName)) {
            staticResourcesServletConfigs.add(new StaticResourcesServletConfig(LOCAL_PACKAGES_PATH + packageName + SEPARATOR + "*", SEPARATOR));
          }
        }
        jettyWrapper.setAdditionalServlets(Collections.singletonMap(SEPARATOR + DYNAMIC_PACKAGES_FILENAME,
                new AddDynamicPackagesServlet(jooProxyTargetUri + DYNAMIC_PACKAGES_FILENAME, packageNames)
        ));
      }
    } else if (isAppOverlayPackaging) {
      // if any other or no proxy path spec, we have to set up the static resources of the required base app and possibly the required overlay app.

      // Add base app and all app overlays
      JangarooApp jangarooApp = createJangarooApp(project);
      while (jangarooApp instanceof JangarooAppOverlay) {
        jangarooApp = ((JangarooAppOverlay) jangarooApp).baseApp;
        if (jangarooApp != null) {
          addAppToResources(jettyWrapper, jangarooApp.mavenProject, ROOT_PATH, "");
        }
      }

      staticResourcesServletConfigs.add(new StaticResourcesServletConfig(JettyWrapper.ROOT_PATH_SPEC, SEPARATOR));
    } else if (isAppsPackaging) {
      JangarooApps jangarooApps = createJangarooApps(project);
      if (isProxyRootPath) {
        throw new MojoExecutionException("Not supported yet!");
      } else {
        // if any other or no proxy path spec, we have to set up the static resources of the required base app and possibly the required overlay app.
        Dependency rootApp = getRootApp();
        for (JangarooApp jangarooApp : jangarooApps.apps) {
          boolean isRootApp = rootApp != null
                  && jangarooApp.mavenProject.getGroupId().equals(rootApp.getGroupId())
                  && jangarooApp.mavenProject.getArtifactId().equals(rootApp.getArtifactId());
          String senchaAppName = SenchaUtils.getSenchaPackageName(jangarooApp.mavenProject);
          String appPath = isRootApp ? ROOT_PATH : LOCAL_APPS_PATH + senchaAppName;
          if (!isRootApp) {
            // add local apps folder
            jettyWrapper.addBaseDir(new File(baseDir, APPS_DIRECTORY_NAME + SEPARATOR + senchaAppName).toPath(), appPath);
          }
          // Add base app and all app overlays
          do {
            addAppToResources(jettyWrapper, jangarooApp.mavenProject, appPath, "");
            addAppToResources(jettyWrapper, jangarooApp.mavenProject, SEPARATOR + EXT_DIRECTORY_NAME, EXT_DIRECTORY_NAME + SEPARATOR);
            addAppToResources(jettyWrapper, jangarooApp.mavenProject, SEPARATOR + PACKAGES_DIRECTORY_NAME, PACKAGES_DIRECTORY_NAME + SEPARATOR);
            jangarooApp = jangarooApp instanceof JangarooAppOverlay ? ((JangarooAppOverlay) jangarooApp).baseApp : null;
          } while (jangarooApp != null);
        }
        jettyWrapper.setStaticResourcesServletConfigs(
                Collections.singletonList(
                        new StaticResourcesServletConfig(JettyWrapper.ROOT_PATH_SPEC, SEPARATOR)
                ),
                SEPARATOR + EXT_DIRECTORY_NAME
        );
        jettyWrapper.setStaticResourcesServletConfigs(
                Collections.singletonList(
                        new StaticResourcesServletConfig(JettyWrapper.ROOT_PATH_SPEC, SEPARATOR)
                ),
                SEPARATOR + PACKAGES_DIRECTORY_NAME
        );
      }
    }

    if (!isSwcPackaging && !isProxyRootPath) {
      jettyWrapper.setAdditionalServlets(Collections.singletonMap(ADDITIONAL_PACKAGES_PATH,
              new AdditionalPackagesFromFolderServlet(additionalPackagesDirs)));
      if (additionalPackagesDirs != null) {
        for (File additionalPackagesDir : additionalPackagesDirs) {
          File additionalPackagesSubDir = new File(additionalPackagesDir, PACKAGES_DIRECTORY_NAME);
          if (!additionalPackagesSubDir.isDirectory()) {
            throw new MojoExecutionException("The directory " + additionalPackagesDir.getAbsolutePath() +
                    " configured in 'additionalPackagesDirs' does not exist" +
                    " or does not contain a 'packages' subdirectory.");
          } else {
            if (isAppsPackaging) {
              // for apps packaging, only map the "packages" subdirectory:
              jettyWrapper.addBaseDir(additionalPackagesSubDir.toPath(), SEPARATOR + PACKAGES_DIRECTORY_NAME);
            } else {
              // for app(-overlay) packaging, map the root path, so all packages are "overlaid":
              jettyWrapper.addBaseDir(additionalPackagesDir.toPath(), SEPARATOR);
            }
          }
        }
      }
    }

    jettyWrapper.setStaticResourcesServletConfigs(staticResourcesServletConfigs);

    if (jooProxyServletConfigs != null && !jooProxyServletConfigs.isEmpty()) {
      jettyWrapper.setProxyServletConfigs(jooProxyServletConfigs);
    } else if (jooProxyTargetUri != null && jooProxyPathSpec != null) {
      jettyWrapper.setProxyServletConfigs(Collections.singletonList(
              new ProxyServletConfig(jooProxyTargetUri, jooProxyPathSpec)));
    } else if (jooProxyTargetUri != null){
      getLog().warn("Ignoring 'jooProxyTargetUri' since there is no 'jooProxyPathSpec'.");
    } else if (jooProxyPathSpec != null){
      getLog().warn("Ignoring 'jooProxyPathSpec' since there is no 'jooProxyTargetUri'.");
    }

    try {
      jettyWrapper.start(jooJettyHost, jooJettyPort);

      getLog().info("Started Jetty server at: " + jettyWrapper.getUri());

      logJangarooAppUrl(baseDir, jettyWrapper, project);

      jettyWrapper.blockUntilInterrupted();
    } catch (Exception e) {
      throw new MojoExecutionException("Could not start Jetty", e);
    } finally {
      jettyWrapper.stop();
    }
  }

  private void addAppToResources(JettyWrapper jettyWrapper, MavenProject baseAppProject, String appPath, String subDirectory) throws MojoExecutionException {
    File appDirOrJar = getAppDirOrJar(baseAppProject);
    if (appDirOrJar.isDirectory()) {
      // base app is part of our Reactor, so we can determine its output directory:
      File appResourceDir = subDirectory.isEmpty() ? appDirOrJar : new File(appDirOrJar, subDirectory);
      if (appResourceDir.exists()) {
        getLog().info(String.format("Adding base app resource directory %s for handler with context path %s", appResourceDir.getAbsolutePath(), appPath));
        jettyWrapper.addBaseDir(appResourceDir.toPath(), appPath);
      }
    } else {
      getLog().info(String.format("Adding base app JAR %s for handler with context path %s", appDirOrJar.getAbsolutePath(), appPath));
      jettyWrapper.addBaseDirInResourceJar(appDirOrJar, MavenPluginHelper.META_INF_RESOURCES + subDirectory, appPath);
    }
  }

  private void logJangarooAppUrl(File baseDir, JettyWrapper jettyWrapper, MavenProject project) {
    if (baseDir.exists()) {
      getLog().info("Found " + project.getPackaging() + " at: " + jettyWrapper.getUri());
    }
  }
}
