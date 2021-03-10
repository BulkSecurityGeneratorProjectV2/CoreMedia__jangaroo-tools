package net.jangaroo.jooc.mvnplugin;

import com.google.common.collect.ImmutableList;
import net.jangaroo.jooc.mvnplugin.sencha.SenchaProfileConfiguration;
import net.jangaroo.jooc.mvnplugin.sencha.SenchaUtils;
import net.jangaroo.jooc.mvnplugin.sencha.configbuilder.SenchaPackageConfigBuilder;
import net.jangaroo.jooc.mvnplugin.sencha.configbuilder.SenchaPackageOrAppConfigBuilder;
import net.jangaroo.jooc.mvnplugin.util.MavenDependencyHelper;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.jangaroo.jooc.mvnplugin.sencha.SenchaUtils.getSenchaPackageName;

public abstract class AbstractSenchaPackageOrAppMojo<T extends SenchaPackageOrAppConfigBuilder>
        extends AbstractSenchaMojo
        implements SenchaProfileConfiguration {

  private static final String SENCHA_FALLBACK_VERSION = "0.0.1";

  @Parameter
  private MavenSenchaProfileConfiguration production;

  @Parameter
  private MavenSenchaProfileConfiguration testing;

  @Parameter
  private MavenSenchaProfileConfiguration development;

  @Parameter
  private List<String> requiredClasses;
  @Parameter
  private List<String> additionalCssNonBundle;
  @Parameter
  private List<String> additionalJsNonBundle;
  @Parameter
  private List<String> additionalCssIncludeInBundle;
  @Parameter
  private List<String> additionalJsIncludeInBundle;

  @Parameter
  private String theme;

  @Parameter (defaultValue = "${project.basedir}/src/main/sencha")
  private File senchaSrcDir;

  /**
   * Experimental: The Ext namespace is stripped from the relative path to the source root.
   */
  @Parameter(property = "extNamespace")
  private String extNamespace = "";

  /**
   * Experimental: The Ext sass namespace is stripped from the relative path inside the sencha/sass/var
   * and sencha/sass/src of the source root. If not set (or null) it falls back to {@link #extNamespace}.
   */
  @Parameter(property = "extSassNamespace")
  private String extSassNamespace = null;

  public abstract String getType();

  public abstract String getJsonConfigFileName();

  String getExtNamespace() {
    return extNamespace;
  }

  void configure(SenchaPackageOrAppConfigBuilder configBuilder) throws MojoExecutionException {
    configureMetadata(configBuilder);
    if (!StringUtils.isEmpty(extNamespace)) {
      configBuilder.namespace(extNamespace);
    }
    String extSassNamespaceWithFallback = extSassNamespace != null ? extSassNamespace : extNamespace;
    if (!StringUtils.isEmpty(extSassNamespaceWithFallback)) {
      // in contrast to the jangaroo-maven-plugin sencha configures namespace and sassNamespace separately with no
      // fallback involved
      configBuilder.sassNamespace(extSassNamespaceWithFallback);
    }
    configureRequires(configBuilder);
    configureModule(configBuilder);
    configureProfile(configBuilder, null, this);
    configureProfile(configBuilder, SenchaUtils.PRODUCTION_PROFILE, getProduction());
    configureProfile(configBuilder, SenchaUtils.TESTING_PROFILE, getTesting());
    configureProfile(configBuilder, SenchaUtils.DEVELOPMENT_PROFILE, getDevelopment());
    configureCustomProperties(configBuilder);
  }

  private void configureCustomProperties(SenchaPackageOrAppConfigBuilder configBuilder) throws MojoExecutionException {
    File jsonFile = new File(project.getBasedir(), getJsonConfigFileName());
    if (jsonFile.exists()) {
      try (FileInputStream fileInputStream = new FileInputStream(jsonFile)) {
        //noinspection unchecked
        configBuilder.namesValues((Map<String, Object>) SenchaUtils.getObjectMapper().readValue(fileInputStream, Map.class));
      } catch (IOException e) {
        throw new MojoExecutionException("Could not read json file", e);
      }
    }
  }

  private void configureMetadata(SenchaPackageOrAppConfigBuilder configBuilder) {
    String version = SenchaUtils.getSenchaVersionForMavenVersion(project.getVersion());
    if (version == null) {
      version = SENCHA_FALLBACK_VERSION;
      getLog().warn("Could not determine Sencha version from maven version " + project.getVersion() +
              ", falling back to " + version + ".");
    }
    configBuilder.name(getSenchaPackageName(project.getGroupId(), project.getArtifactId()));
    configBuilder.version(version);
    configBuilder.creator(StringUtils.defaultString(project.getOrganization() != null ? project.getOrganization().getName() : ""));
    configBuilder.summary(StringUtils.defaultString(project.getDescription()));
  }


  private void configureProfile(SenchaPackageOrAppConfigBuilder configBuilder,
                                String profileName,
                                SenchaProfileConfiguration senchaProfileConfiguration) {
    if (null == profileName) {
      configureProfile(senchaProfileConfiguration, configBuilder);
    } else {
      SenchaPackageOrAppConfigBuilder profileConfigBuilder = createSenchaConfigBuilder();
      configureProfile(senchaProfileConfiguration, profileConfigBuilder);
      //noinspection unchecked
      configBuilder.profile(profileName, profileConfigBuilder.build());
    }
  }

  private static void configureProfile(SenchaProfileConfiguration senchaProfileConfiguration,
                                       SenchaPackageOrAppConfigBuilder configBuilder) {
    addAdditionalResources(configBuilder,
            SenchaPackageOrAppConfigBuilder.CSS,
            senchaProfileConfiguration == null ? Collections.emptyList() : senchaProfileConfiguration.getAdditionalCssNonBundle(),
            senchaProfileConfiguration == null ? Collections.emptyList() : senchaProfileConfiguration.getAdditionalCssIncludeInBundle());

    addAdditionalResources(configBuilder,
            SenchaPackageOrAppConfigBuilder.JS,
            senchaProfileConfiguration == null ? Collections.emptyList() : senchaProfileConfiguration.getAdditionalJsNonBundle(),
            senchaProfileConfiguration == null ? Collections.emptyList() : senchaProfileConfiguration.getAdditionalJsIncludeInBundle());
  }

  private void configureRequires(SenchaPackageOrAppConfigBuilder configBuilder) throws MojoExecutionException {
    for (String dependency : getRequiredDependencies()) {
      configBuilder.require(dependency);
    }
  }

  private void configureModule(SenchaPackageOrAppConfigBuilder configBuilder) {
    boolean useExtend = false;
    if (Type.CODE.equals(getType())) {
      configBuilder.toolkit(getToolkit());
    }
    if (Type.THEME.equals(getType())) {
      configBuilder.toolkit(getToolkit());
      useExtend = true;
    }
    String themePackageName = getThemePackageName();
    if (org.apache.commons.lang3.StringUtils.isNotBlank(themePackageName)) {
      if (useExtend) {
        ((SenchaPackageConfigBuilder)configBuilder).extend(themePackageName);
      } else {
        configBuilder.theme(themePackageName);
      }
    }
    if (Type.CODE.equals(getType())
            || Type.THEME.equals(getType())) {
      configureResourcesEntry(configBuilder);
    }
  }

  private void configureResourcesEntry(SenchaPackageOrAppConfigBuilder configBuilder) {
    configBuilder.resource(SenchaUtils.absolutizeToModuleWithPlaceholder(getType(), SenchaUtils.SENCHA_RESOURCES_PATH));
  }

  private Set<String> getRequiredDependencies() throws MojoExecutionException {
    Set<String> requiredDependencies = new LinkedHashSet<>();
    Dependency themeDependency = SenchaUtils.getThemeDependency(getTheme(), project);
    List<Dependency> projectDependencies = resolveRequiredDependencies(project);
    for (Dependency dependency : projectDependencies) {
      String senchaPackageNameForArtifact = getSenchaPackageName(dependency.getGroupId(), dependency.getArtifactId());
      if (!isExtFrameworkDependency(dependency) &&
              !MavenDependencyHelper.equalsGroupIdAndArtifactId(dependency,themeDependency) &&
              SenchaUtils.isRequiredSenchaDependency(dependency, false, true) &&
              !isProvidedThemeDependency(dependency)) {
        requiredDependencies.add(senchaPackageNameForArtifact);
      }
    }
    return requiredDependencies;
  }

  private boolean isProvidedThemeDependency(Dependency dependency) throws MojoExecutionException {
    if (Artifact.SCOPE_PROVIDED.equals(dependency.getScope()) && Type.JANGAROO_SWC_PACKAGING.equals(dependency.getType())) {
      // To find out whether the SWC dependency is a theme, we must peek into its POM:
      MavenProject projectFromDependency = getProjectFromDependency(project, dependency);
      // find jangaroo-maven-plugin configuration:
      List<Plugin> plugins = projectFromDependency.getBuildPlugins();
      for (Plugin plugin : plugins) {
        if ("net.jangaroo".equals(plugin.getGroupId()) && "jangaroo-maven-plugin".equals(plugin.getArtifactId())) {
          Object jangarooPluginConfiguration = plugin.getConfiguration();
          // find packageType configuration:
          if (jangarooPluginConfiguration instanceof Xpp3Dom) {
            Xpp3Dom configClassPackageElement = ((Xpp3Dom) jangarooPluginConfiguration).getChild("packageType");
            if (configClassPackageElement != null) {
              String packageType = configClassPackageElement.getValue();
              if (packageType != null) {
                packageType = packageType.trim();
                if ("theme".equals(packageType)) {
                  return true;
                }
              }
            }
          }
        }
      }
    }
    return false;
  }

  @Nonnull
  private List<Dependency> resolveRequiredDependencies(@Nonnull MavenProject project)
          throws MojoExecutionException {
    List<Dependency> resolvedDependencies = new ArrayList<>();
    for (Dependency dependency : project.getDependencies()) {
      // only resolve POM packages that are not the remote packages artifact
      if (Type.POM_PACKAGING.equalsIgnoreCase(dependency.getType())) {
        MavenProject projectFromPom = getProjectFromDependency(project, dependency);
        List<Dependency> fromPomDependencies = resolveRequiredDependencies(projectFromPom);
        if (!fromPomDependencies.isEmpty()) {
          resolvedDependencies.addAll(fromPomDependencies);
        }
      } else {
        resolvedDependencies.add(dependency);
      }
    }
    return resolvedDependencies;
  }

  private static void addAdditionalResources(SenchaPackageOrAppConfigBuilder configBuilder,
                                             String resourceType,
                                             List<String> resourcesNonBundle,
                                             List<String> resourcesIncludeInBundle) {
    for (String resource : resourcesNonBundle) {
      configBuilder.cssOrJs(resourceType, resource, false, false);
    }

    for (String resource : resourcesIncludeInBundle) {
      configBuilder.cssOrJs(resourceType, resource, false, true);
    }
  }

  @Nonnull
  private String getThemePackageName() {
    Dependency themeDependency = SenchaUtils.getThemeDependency(getTheme(), project);
    String themePackageName;
    if (themeDependency == null) {
      themePackageName = org.apache.commons.lang3.StringUtils.defaultString(getTheme());
      // print a warning if a theme was set but it could not be found in the list of dependencies
      if (getTheme() != null) {
        getLog().warn(String.format("Could not identify theme dependency. Using theme  \"%s\" from configuration instead.",
                themePackageName));
      }
    } else {
      themePackageName = SenchaUtils.getSenchaPackageName(themeDependency.getGroupId(), themeDependency.getArtifactId());
      getLog().info(String.format("Setting theme to \"%s\"", themePackageName));
    }
    return themePackageName;
  }


  protected abstract  T createSenchaConfigBuilder();

  File getSenchaSrcDir() {
    return senchaSrcDir;
  }

  SenchaProfileConfiguration getProduction() {
    return production;
  }

  SenchaProfileConfiguration getDevelopment() {
    return development;
  }

  public SenchaProfileConfiguration getTesting() {
    return testing;
  }

  @Nonnull
  @Override
  public List<String> getRequiredClasses() {
    return requiredClasses == null ? Collections.emptyList() : requiredClasses;
  }

  @SuppressWarnings("WeakerAccess")
  public String getTheme() {
    return theme;
  }

  @Nonnull
  @Override
  public List<String> getAdditionalCssNonBundle() {
    return additionalCssNonBundle != null ? ImmutableList.copyOf(additionalCssNonBundle) : Collections.emptyList();
  }

  @Nonnull
  @Override
  public List<String> getAdditionalJsNonBundle() {
    return additionalJsNonBundle != null ? ImmutableList.copyOf(additionalJsNonBundle) : Collections.emptyList();
  }

  @Nonnull
  @Override
  public List<String> getAdditionalCssIncludeInBundle() {
    return additionalCssIncludeInBundle != null ? ImmutableList.copyOf(additionalCssIncludeInBundle) : Collections.emptyList();
  }

  @Nonnull
  @Override
  public List<String> getAdditionalJsIncludeInBundle() {
    return additionalJsIncludeInBundle != null ? ImmutableList.copyOf(additionalJsIncludeInBundle) : Collections.emptyList();
  }
}
