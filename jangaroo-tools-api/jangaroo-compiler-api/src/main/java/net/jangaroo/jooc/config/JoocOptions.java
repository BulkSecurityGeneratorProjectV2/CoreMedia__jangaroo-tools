package net.jangaroo.jooc.config;

import java.io.File;

public interface JoocOptions {

  SemicolonInsertionMode getSemicolonInsertionMode();

  DebugMode getDebugMode();

  boolean isSuppressCommentedActionScriptCode();

  boolean isEnableAssertions();

  boolean isGenerateApi();

  boolean isUseEcmaParameterInitializerSemantics();

  boolean isMigrateToTypeScript();

  boolean isTypeScriptThisBeforeSuperViaIgnore();

  String getExtNamespace();

  String getExtSassNamespace();

  String getNpmPackageName();

  PublicApiViolationsMode getPublicApiViolationsMode();

  /**
   * If true, the compiler will add an [ExcludeClass] annotation to any
   * API stub whose source class contains neither an [PublicApi] nor an [ExcludeClass]
   * annotation.
   */
  boolean isExcludeClassByDefault();

  boolean isGenerateSourceMaps();

  boolean isFindUnusedDependencies();

  String getDependencyReportOutputFile();

  File getKeepGeneratedActionScriptDirectory();
}
