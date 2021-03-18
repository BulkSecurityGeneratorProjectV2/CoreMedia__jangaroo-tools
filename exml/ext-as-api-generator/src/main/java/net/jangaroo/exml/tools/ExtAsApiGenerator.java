package net.jangaroo.exml.tools;

import com.vladsch.flexmark.ast.Node;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.options.MutableDataSet;
import net.jangaroo.jooc.Jooc;
import net.jangaroo.jooc.backend.ActionScriptCodeGeneratingModelVisitor;
import net.jangaroo.jooc.backend.JsCodeGenerator;
import net.jangaroo.jooc.model.AbstractAnnotatedModel;
import net.jangaroo.jooc.model.AnnotationModel;
import net.jangaroo.jooc.model.AnnotationPropertyModel;
import net.jangaroo.jooc.model.ClassModel;
import net.jangaroo.jooc.model.CompilationUnitModel;
import net.jangaroo.jooc.model.CompilationUnitModelRegistry;
import net.jangaroo.jooc.model.FieldModel;
import net.jangaroo.jooc.model.MemberModel;
import net.jangaroo.jooc.model.MethodModel;
import net.jangaroo.jooc.model.MethodType;
import net.jangaroo.jooc.model.NamedModel;
import net.jangaroo.jooc.model.NamespacedModel;
import net.jangaroo.jooc.model.ParamModel;
import net.jangaroo.jooc.model.PropertyModel;
import net.jangaroo.jooc.model.ReturnModel;
import net.jangaroo.jooc.model.TypedModel;
import net.jangaroo.jooc.mxml.MxmlUtils;
import net.jangaroo.utils.AS3Type;
import net.jangaroo.utils.CompilerUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static net.jangaroo.exml.tools.ExtJsApi.*;

/**
 * Generate ActionScript 3 APIs from a jsduck JSON export of the Ext JS 4.x API.
 */
public class ExtAsApiGenerator {

  private static final Pattern SINGLETON_CLASS_NAME_PATTERN = Pattern.compile("^S[A-Z]");
  private static final String LINK_PATTERN_STR = "\\{@link(\\s+)([^\\s}]*)(?:\\s+([^}]*))?\\s*}";
  private static final Pattern LINK_PATTERN = Pattern.compile(LINK_PATTERN_STR);
  private static final Pattern INLINE_TAG_OR_LINK_PATTERN = Pattern.compile("<(/?)(code|i)>|" + LINK_PATTERN_STR);
  private static final Pattern TYPED_ARRAY_PATTERN = Pattern.compile("([a-zA-Z0-9._$<>]+)\\[]");
  private static final String REQUIRED_CONFIG_TEXT = "<b>This is a required setting</b>. ";
  private static final String PRIVATE_CLASS_TEXT = "<b>NOTE: This is a private utility class for internal use by the framework. Don't rely on its existence.</b>\n\n";
  private static ExtJsApi extJsApi;
  private static Set<ExtClass> extClasses;
  private static CompilationUnitModelRegistry compilationUnitModelRegistry;
  private static Set<String> interfaces;
  private static final List<String> NON_COMPILE_TIME_CONSTANT_INITIALIZERS = Arrays.asList("window", "document", "document.body", "new Date()", "this", "`this`", "10||document.body", "caller", "array.length");
  private static ExtAsApi referenceApi;
  private static boolean generateEventClasses;
  private static boolean generateForMxml;
  private static Properties jsAsNameMappingProperties;
  private static Properties jsConfigClassNameMappingProperties;
  private static Properties eventWordsProperties;
  private static final String EXT_3_4_EVENT = "ext.IEventObject";
  private static String EXT_EVENT;
  private static final Set<String> invalidJsDocReferences = new HashSet<>();
  private static Map<String, Map<String, String>> aliasGroupToAliasToClass = new TreeMap<String, Map<String, String>>();

  public static void main(String[] args) throws IOException {
    String extVersion = args[0];
    File srcFile = new File(args[1]);
    File outputDir = new File(args[2] + "/joo");
    File configDir = new File(args[2] + "/config");
    referenceApi = new ExtAsApi(loadProperties(configDir, "net/jangaroo/exml/tools/ext-js-3.4-6.0-name-mapping.properties"),
            "2.0.14", "2.0.13");
    EXT_EVENT = referenceApi.getMappedQName(EXT_3_4_EVENT);
    if (EXT_EVENT == null) {
      EXT_EVENT = EXT_3_4_EVENT;
    }

    generateEventClasses = args.length <= 3 || Boolean.valueOf(args[3]);
    generateForMxml = args.length <= 4 ? false : Boolean.valueOf(args[4]);

    ExtAsApiGenerator.jsAsNameMappingProperties = loadProperties(configDir, "net/jangaroo/exml/tools/js-as-name-mapping.properties");
    jsConfigClassNameMappingProperties = loadProperties(configDir, "net/jangaroo/exml/tools/js-config-name-mapping.properties");
    eventWordsProperties = loadProperties(configDir, "net/jangaroo/exml/tools/event-words.properties");

    if (srcFile.exists()) {
      compilationUnitModelRegistry = new CompilationUnitModelRegistry();
      interfaces = new HashSet<String>();
      extJsApi = new ExtJsApi(extVersion, srcFile);
      extClasses = new HashSet<ExtClass>(extJsApi.getExtClasses());
      removePrivateApiClasses();

      Set<ExtClass> mixins = extJsApi.getMixins();
      for (ExtClass mixin : mixins) {
        String mixinName = getActionScriptName(mixin);
        if (mixinName != null && !mixinName.isEmpty()) {
          interfaces.add(mixinName);
        }
      }

      // correct wrong usage of util.Observable as a mixin:
      interfaces.remove("ext.util.Observable");
      // correct wrong usage of dom.Element as a mixin, not superclass, in dom.CompositeElementLite:
      interfaces.remove("ext.dom.Element");
      // since every Ext object extends Base, there is no need to generate an interface for that:
      interfaces.remove("ext.Base");
      interfaces.remove("Object");
      interfaces.add("ext.EventObjectImpl");

      for (ExtClass extClass : extClasses) {
        generateClassModel(extClass);
      }
      compilationUnitModelRegistry.complementOverrides();
      //compilationUnitModelRegistry.complementImports();

      adaptToReferenceApi();

      // again, to make corrections consistent (actually needed?)
      compilationUnitModelRegistry.complementOverrides();
      compilationUnitModelRegistry.complementImports();

      annotateBindableConfigProperties();

      if (!outputDir.exists()) {
        System.err.println("No output directory specified, skipping code generation.");
        return;
      }
      for (CompilationUnitModel compilationUnitModel : compilationUnitModelRegistry.getCompilationUnitModels()) {
        generateActionScriptCode(compilationUnitModel, outputDir);
      }
    }

    generateManifest(outputDir);

    if (!invalidJsDocReferences.isEmpty()) {
      ArrayList<String> sortedInvalidJsDocReferences = new ArrayList<>(invalidJsDocReferences);
      Collections.sort(sortedInvalidJsDocReferences);
      System.out.println("************ Invalid JSDoc References *****************");
      for (String invalidJsDocReference : sortedInvalidJsDocReferences) {
        ExtClass extClass = extJsApi.getExtClass(invalidJsDocReference);
        System.out.println(invalidJsDocReference + (extClass == null ? " (unknown)" : ("private".equals(extClass.access) ? " (private)" : " (not mapped)")));
      }
      System.out.println("************ END Invalid JSDoc References *****************");
    }
  }

  private static Properties loadProperties(File configDir, String configFile) throws IOException {
    Properties properties = new Properties();
    properties.load(new BufferedReader(new FileReader(new File(configDir, configFile))));
    return properties;
  }

  private static void generateManifest(File outputDir) throws FileNotFoundException, UnsupportedEncodingException {
    // create manifest.xml component library:
    File outputFile = new File(outputDir, "manifest.xml");
    System.out.printf("Creating manifest file %s...%n", outputFile.getPath());
    PrintStream out = new PrintStream(new FileOutputStream(outputFile), true, net.jangaroo.exml.api.Exmlc.OUTPUT_CHARSET);
    out.println("<?xml version=\"1.0\"?>");
    out.println("<componentPackage>");
    for (String aliasGroup : aliasGroupToAliasToClass.keySet()) {
      String previousId = "";
      for (Map.Entry<String, String> aliasToClass : aliasGroupToAliasToClass.get(aliasGroup).entrySet()) {
        String alias = aliasToClass.getKey();
        String classQName = aliasToClass.getValue();
        String id = computeId(alias, classQName + previousId);
        previousId = id;
        if (!"widget".equals(aliasGroup)) {
          id = aliasGroup + "_" + id;
        }
        out.printf("  <component id=\"%s\" class=\"%s\"/>%n", id, classQName);
      }
    }
    out.println("</componentPackage>");
    out.close();
  }

  private static String computeId(String alias, String classQName) {
    int dotPos = alias.indexOf('.');
    if (dotPos != -1) {
      // special format used e.g. in "data" aliases: capitalize the dot-prefix and use it as suffix.
      return computeId(alias.substring(dotPos + 1), classQName) + capitalize(alias.substring(0, dotPos));
    }
    // remove all dashes from alias:
    alias = alias.replaceAll("-", "");

    // match unqualified class name word by word:
    String className = CompilerUtils.className(classQName);
    String[] words = className.split("(?<!^)(?=[A-Z])");
    int index = 0;
    StringBuilder result = new StringBuilder();
    for (String word : words) {
      int wordIndex = alias.indexOf(word.toLowerCase(), index);
      if (wordIndex != -1) {
        result.append(capitalize(alias.substring(index, wordIndex)));
        result.append(word);
        index = wordIndex + word.length();
      }
    }
    result.append(capitalize(alias.substring(index)));
    return result.toString();
  }

  private static void adaptToReferenceApi() {
    System.err.printf("Class\tremoved\tchanged\tsame%n");
    for (CompilationUnitModel compilationUnitModel : compilationUnitModelRegistry.getCompilationUnitModels()) {
      String qName = compilationUnitModel.getQName();
      NamedModel primaryDeclaration = compilationUnitModel.getPrimaryDeclaration();
      if (primaryDeclaration instanceof FieldModel) {
        // use compilation units that define type of singleton, not the singleton CUs themselves:
        String singletonType = ((FieldModel) primaryDeclaration).getType(); // already fully qualified!
        compilationUnitModel = compilationUnitModelRegistry.resolveCompilationUnit(singletonType);

        CompilationUnitModel singletonReference = getReferenceDeclaration(qName);
        if (singletonReference == null || !(singletonReference.getPrimaryDeclaration() instanceof FieldModel)) {
          continue;
        }
        qName = CompilerUtils.qName(CompilerUtils.packageName(qName), ((FieldModel) singletonReference.getPrimaryDeclaration()).getType());
      }
      if (primaryDeclaration instanceof ClassModel) {
        for (CompilationUnitModel referenceClass : getReferenceDeclarations(qName)) {
          if (referenceClass.getPrimaryDeclaration() instanceof ClassModel) {
            adaptToReferenceApi(compilationUnitModel, referenceClass);
          }
        }
      }
    }
  }

  private static void adaptToReferenceApi(CompilationUnitModel compilationUnitModel, CompilationUnitModel referenceCompilationUnitModel) {
    // System.err.println("### Adapting " + compilationUnitModel.getQName() + " to " + referenceCompilationUnitModel.getQName());
    ClassModel referenceClassModel = referenceCompilationUnitModel.getClassModel();
    int removedCount = 0;
    int changedCount = 0;
    for (MemberModel member : referenceClassModel.getMembers()) {
      MemberModel newMember = findMemberModel(compilationUnitModel, member);
      if (newMember == null) {
        if (!member.equals(referenceClassModel.getConstructor())) {
          //System.err.printf("*** member %s.%s not found%n", classModel.getName(), member.getName());
          ++removedCount;
        }
      } else {
        adaptNamespaceToReferenceApi(newMember, member);
        boolean changed = adaptTypeToReferenceApi(referenceCompilationUnitModel, newMember, member);
        if (member instanceof MethodModel) {
          Iterator<ParamModel> iterator = ((MethodModel) member).getParams().iterator(),
                         newIterator = ((MethodModel) newMember).getParams().iterator();
          while (iterator.hasNext() && newIterator.hasNext()) {
            ParamModel param = iterator.next();
            ParamModel newParam = newIterator.next();
            changed |= adaptTypeToReferenceApi(referenceCompilationUnitModel, newParam, param);
          }
          if (iterator.hasNext() || newIterator.hasNext()) {
            changed = true;
          }
        }
        if (changed) {
          ++changedCount;
        }
      }
    }
    int sameCount = referenceClassModel.getMembers().size() - changedCount - removedCount;
    //System.err.printf("=== Adapted %s: removed: %d, changed: %d, same: %d%n", compilationUnitModel.getQName(), removedCount, changedCount, sameCount);
    System.err.printf("%s\t%d\t%d\t%d%n", compilationUnitModel.getQName(), removedCount, changedCount, sameCount);
  }


  private static boolean adaptTypeToReferenceApi(CompilationUnitModel referenceCompilationUnitModel, TypedModel newMember, TypedModel member) {
    String oldType = referenceApi.resolveQualifiedName(referenceCompilationUnitModel, member.getType());
    String newType = newMember.getType(); // already fully-qualified!
    if (oldType != null && !oldType.equals(newType)) {
      //System.err.printf("*** found %s member %s type change: from %s to %s%n", classModel.getName(), member.getName(), oldType, newType);
      if (shouldCorrect(newMember, oldType, newType)) {
        newMember.setType(oldType);
        //System.err.printf("!!! corrected type of %s.%s from %s back to %s%n", classModel.getName(), member.getName(), newType, oldType);
        return true;
      }
    }
    return false;
  }

  private static void adaptNamespaceToReferenceApi(MemberModel newMember, MemberModel member) {
    String oldNamespace = member.getNamespace();
    String newNamespace = newMember.getNamespace();
    if (oldNamespace != null && !oldNamespace.equals(newNamespace)) {
      newMember.setNamespace(oldNamespace);
    }
  }

  private static boolean shouldCorrect(TypedModel newMember, String oldType, String newType) {
    return newType == null ||
            "void".equals(newType) ||
            "*".equals(newType) ||
            // keep old type void, but for methods only if they are "chainable" (return this):
            "void".equals(oldType) && (!(newMember instanceof MethodModel) || returnsThis((MethodModel) newMember)) ||
            "Object".equals(newType) && "*".equals(oldType) ||
            EXT_EVENT.equals(oldType) && newType.contains("Event") ||
            "Function".equals(newType) && "Class".equals(oldType);
  }

  private static boolean returnsThis(MethodModel newMember) {
    ReturnModel returnModel = newMember.getReturnModel();
    return returnModel != null && returnModel.getType() != null && returnModel.getType().contains(".") && returnModel.getAsdoc() != null && returnModel.getAsdoc().toLowerCase().startsWith("this");
  }

  private static MemberModel findMemberModel(CompilationUnitModel compilationUnitModel, MemberModel referenceMemberModel) {
    ClassModel classModel = compilationUnitModel.getClassModel();
    String referenceMemberName = referenceApi.getMappedMemberName(compilationUnitModel, referenceMemberModel.getName());
    MemberModel memberModel = referenceMemberModel instanceof MethodModel
            ? classModel.getMethod(referenceMemberModel.isStatic(), ((MethodModel) referenceMemberModel).getMethodType(), referenceMemberName)
            : classModel.getMember(referenceMemberModel.isStatic(), referenceMemberName);
    if (memberModel == null) {
      CompilationUnitModel superclass = compilationUnitModelRegistry.getSuperclassCompilationUnit(classModel);
      if (superclass != null) {
        return findMemberModel(superclass, referenceMemberModel);
      }
    }
    return memberModel;
  }

  private static void generateClassModel(ExtClass extClass) {
    String extClassName = getActionScriptName(extClass);
    if (extClassName == null || extClassName.isEmpty()) {
      return;
    }
    CompilationUnitModel extAsClassUnit = createClassModel(convertType(extClass.name));
    ClassModel extAsClass = extAsClassUnit.getClassModel();
    System.out.printf("Generating AS3 API model %s for %s...%n", extAsClassUnit.getQName(), extClassName);
    extAsClass.setAsdoc(toAsDoc(extClass, getThisClassName(extAsClass, extClass), extClass.name));
    addDeprecation(extClass.deprecatedMessage, extClass.deprecatedVersion, extAsClass);
    CompilationUnitModel extAsInterfaceUnit = null;
    if (interfaces.contains(extClassName)) {
      extAsInterfaceUnit = createClassModel(convertToInterface(extClassName));
      System.out.printf("Generating AS3 API model %s for %s...%n", extAsInterfaceUnit.getQName(), extClassName);
      ClassModel extAsInterface = (ClassModel)extAsInterfaceUnit.getPrimaryDeclaration();
      extAsInterface.setInterface(true);
      extAsInterface.setAsdoc(toAsDoc(extClass, getThisClassName(extAsInterface, extClass), extClass.name) + "\n * @see " + extClassName);
      if (extClass.extends_ != null) {
        String superInterface = convertToInterface(getActionScriptName(extClass.extends_));
        if (superInterface != null) {
          extAsInterface.addInterface(superInterface);
        }
      }
    }
    AnnotationModel nativeAnnotation = createNativeAnnotation(extClass.name);
    if (isSingleton(extClass)) {
      extAsClass.addAnnotation(createNativeAnnotation(null));
      FieldModel singleton = new FieldModel(CompilerUtils.className(extAsClass.getName().substring(1)), extAsClassUnit.getQName());
      singleton.setConst(true);
      singleton.setValue("new " + extAsClassUnit.getQName());
      singleton.addAnnotation(nativeAnnotation);
      singleton.setAsdoc(extAsClass.getAsdoc());
      CompilationUnitModel singletonUnit = new CompilationUnitModel(extAsClassUnit.getPackage(), singleton);
      compilationUnitModelRegistry.register(singletonUnit);

      extAsClass.setAsdoc(String.format("%s%n<p>Type of singleton %s.</p>%n@see %s %s",
        extAsClass.getAsdoc(),
        singleton.getName(),
        CompilerUtils.qName(extAsClassUnit.getPackage(), "#" + singleton.getName()),
        singletonUnit.getQName()));
    } else {
      extAsClass.addAnnotation(nativeAnnotation);
    }
    if (extAsInterfaceUnit != null) {
      extAsInterfaceUnit.getClassModel().addAnnotation(new AnnotationModel(Jooc.MIXIN_ANNOTATION_NAME,
              new AnnotationPropertyModel(null, CompilerUtils.quote(extClassName))));
    }
    extAsClass.setSuperclass(convertType(extClass.extends_));
    if (extAsInterfaceUnit != null) {
      extAsClass.addInterface(extAsInterfaceUnit.getQName());
    }
    for (String mixin : extClass.mixins) {
      ExtClass extMixinClass = extJsApi.getExtClass(mixin);
      if (extMixinClass != null) {
        String superInterface = convertToInterface(getActionScriptName(extMixinClass));
        if (superInterface == null) {
          // mix-in all members of Mixin:
          addNonStaticMembers(extMixinClass, extAsClassUnit);
        } else {
          // mix-in only non-public members of Mixin, the interface will take care about the rest:
          addNonStaticMembers(extMixinClass, true, extAsClassUnit);
          extAsClass.addInterface(superInterface);
          if (extAsInterfaceUnit != null) {
            extAsInterfaceUnit.getClassModel().addInterface(superInterface);
          } 
        }
      }
    }

    if (extAsInterfaceUnit != null) {
      addNonStaticMembers(extClass, extAsInterfaceUnit);
    } else {
      addFields(extAsClass, extJsApi.filterByOwner(false, true, extClass, "static-properties", Property.class), extClass);
      addMethods(extAsClass, extClass, extJsApi.filterByOwner(false, true, extClass, "static-methods", Method.class));
    }

    addNonStaticMembers(extClass, extAsClassUnit);

    // todo: remove #getConfigClassQName and its mapping properties, a constructor needs to be generated if and only if the class or a superclass has config parameters
    if (getConfigClassQName(extClass) != null) {
      addConfigConstructor(extAsClassUnit);
    }

    if (extClass.alias != null) {
      for (String alias : extClass.alias.split(",")) {
        int dotIndex = alias.indexOf('.');
        String aliasGroup = alias.substring(0, dotIndex);
        Map<String, String> aliasMapping = aliasGroupToAliasToClass.computeIfAbsent(aliasGroup, k -> new TreeMap<>());
        aliasMapping.put(alias.substring(dotIndex + 1), extAsClassUnit.getQName());
      }
    }
  }

  private static void addConfigConstructor(CompilationUnitModel extAsClassUnit) {
    ClassModel extAsClass = extAsClassUnit.getClassModel();
    MethodModel targetClassConstructor = extAsClass.getConstructor();
    if (targetClassConstructor == null) {
      targetClassConstructor = extAsClass.createConstructor();
      targetClassConstructor.addParam(new ParamModel("config", extAsClassUnit.getQName(), "null", "@inheritDoc"));
    } else {
      for (ParamModel param : targetClassConstructor.getParams()) {
        if ("config".equals(param.getName())) {
          param.setType(extAsClass.getName());
          param.setOptional(true);
          break;
        }
      }
    }
  }

  private static String getConfigClassQName(ExtClass extClass) {
    String extClassName = extClass.name;
    String alias = jsConfigClassNameMappingProperties.getProperty(extClassName);
    if (alias == null) {
      return null;
    }
    String configClassQName = CompilerUtils.qName("ext.config", alias);
    System.err.println("********* derived config class name "  + configClassQName + " from Ext class " + extClassName + " with alias " + alias);
    return configClassQName;
  }

  private static String getPreferredAlias(Map.Entry<String, List<String>> aliases) {
    String alias;
    alias = aliases.getValue().get(aliases.getValue().size() - 1);
    if (aliases.getValue().size() > 1) {
      // for the following exceptions, use the first alias:
      if ("box".equals(alias)) {
        alias = aliases.getValue().get(0);
      }
      System.err.println("***### multiple aliases: " + aliases.getValue() + ", choosing " + alias);
    }
    return alias;
  }

  private static AnnotationModel createNativeAnnotation(String nativeName) {
    AnnotationModel nativeAnnotation = new AnnotationModel(Jooc.NATIVE_ANNOTATION_NAME);
    if (nativeName != null) {
      nativeAnnotation.addProperty(new AnnotationPropertyModel(null, CompilerUtils.quote(nativeName)));
      nativeAnnotation.addProperty(new AnnotationPropertyModel(Jooc.NATIVE_ANNOTATION_REQUIRE_PROPERTY, null));
    }
    return nativeAnnotation;
  }

  private static CompilationUnitModel createClassModel(String qName) {
    CompilationUnitModel oldCompilationUnitModel = compilationUnitModelRegistry.resolveCompilationUnit(qName);
    if (oldCompilationUnitModel != null) {
      System.err.println("[WARN] Redefining class " + qName);
      return oldCompilationUnitModel;
    }
    CompilationUnitModel compilationUnitModel = new CompilationUnitModel(null, new ClassModel());
    compilationUnitModel.setQName(qName);
    compilationUnitModelRegistry.register(compilationUnitModel);
    return compilationUnitModel;
  }

  private static void addNonStaticMembers(ExtClass extClass, CompilationUnitModel extAsClassUnit) {
    addNonStaticMembers(extClass, false, extAsClassUnit);
  }

  private static void addNonStaticMembers(ExtClass extClass, boolean isMixin, CompilationUnitModel extAsClassUnit) {
    ClassModel extAsClass = extAsClassUnit.getClassModel();
    if (!extAsClass.isInterface()) {
      addEvents(extAsClass, extAsClassUnit, extJsApi.filterByOwner(isMixin, false, false, extClass, "events", Event.class), extClass.name);
    }
    addProperties(extAsClass, extClass, extJsApi.filterByOwner(isMixin, extAsClass.isInterface(), false, extClass, "properties", Property.class), false);
    addMethods(extAsClass, extClass, extJsApi.filterByOwner(isMixin, extAsClass.isInterface(), false, extClass, "methods", Method.class));
    // always add configs, even from mixins, as [ExtConfig] annotations are not inherited from interfaces:
    addProperties(extAsClass, extClass, extJsApi.filterByOwner(false, extAsClass.isInterface(), false, extClass, "configs", Property.class), true);
  }

  private static void generateActionScriptCode(CompilationUnitModel extAsClass, File outputDir) throws IOException {
    File outputFile = CompilerUtils.fileFromQName(extAsClass.getQName(), outputDir, Jooc.AS_SUFFIX);
    //noinspection ResultOfMethodCallIgnored
    outputFile.getParentFile().mkdirs(); // NOSONAR
    System.out.printf("Generating AS3 API for %s into %s ...\n", extAsClass.getQName(), outputFile.getCanonicalPath());
    extAsClass.visit(new ActionScriptCodeGeneratingModelVisitor(new OutputStreamWriter(new FileOutputStream(outputFile), "UTF-8")));
  }

  private static void addDeprecation(String deprecatedMessage, String deprecatedVersion, AbstractAnnotatedModel model) {
    if (deprecatedMessage != null) {
      final AnnotationModel deprecated = new AnnotationModel("Deprecated");
      if (!deprecatedMessage.matches("\\s*")) {
        Matcher replacementMatcher = LINK_PATTERN.matcher(deprecatedMessage);
        String name;
        String value;
        if (replacementMatcher.find()) {
          name = "replacement";
          String reference = replacementMatcher.group(2);
          String[] parts = reference.split("[#!-]");
          if (parts.length > 1) {
            value = parts[parts.length - 1]; // TODO: check whether part 1 contains the current class, otherwise mention it!
          } else {
            value = convertType(parts[0]);
          }
        } else {
          name = "message";
          value = deprecatedMessage.replace("<p>", "").replace("</p>", "");
        }
        deprecated.addProperty(new AnnotationPropertyModel(name, CompilerUtils.quote(value, false)));
      }
      if (deprecatedVersion != null && !deprecatedVersion.matches("\\s*")) {
        deprecated.addProperty(new AnnotationPropertyModel(
                "since",
                deprecatedVersion.startsWith("\"") ? deprecatedVersion : CompilerUtils.quote(deprecatedVersion)));
      }
      model.addAnnotation(deprecated);
    }
  }

  private static void addEvents(ClassModel classModel, CompilationUnitModel compilationUnitModel, List<Event> events, String thisJsClassName) {
    for (Event event : events) {
      String eventName = toCamelCase(event.name);
      AnnotationModel annotationModel = new AnnotationModel(Jooc.EVENT_ANNOTATION_NAME,
              new AnnotationPropertyModel(Jooc.EVENT_ANNOTATION_NAME_ATTRIBUTE_NAME, "'on" + eventName + "'"));
      String asdoc;
      if (!generateEventClasses) {
        asdoc = toAsDoc(event, "", thisJsClassName);
      } else {
        String eventTypeQName = generateEventClass(compilationUnitModel, event, thisJsClassName);
        annotationModel.addProperty(new AnnotationPropertyModel("type", "'" + eventTypeQName + "'"));
        String eventReference =  String.format("%s.%s", eventTypeQName, toConstantName(event.name));
        asdoc = "@see " + eventReference + "\n" + "@eventType " + eventReference;
      }
      annotationModel.setAsdoc(asdoc);
      classModel.addAnnotation(annotationModel);
    }
  }

  public static String capitalize(String name) {
    return name == null || name.length() == 0 ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
  }

  private static String generateEventClass(CompilationUnitModel eventClientClass, Event event, String thisJsClassName) {
    String eventTypeNamePrefix = eventClientClass.getPrimaryDeclaration().getName();
    if (SINGLETON_CLASS_NAME_PATTERN.matcher(eventTypeNamePrefix).find()) {
      eventTypeNamePrefix = eventTypeNamePrefix.substring(1);
    }
    StringBuilder eventTypeQNameBuilder = new StringBuilder()
            .append(eventClientClass.getPackage())
            .append(".events.")
            .append(eventTypeNamePrefix);
    String eventClientClassQName = eventClientClass.getQName();
    for (int i = 0; i < event.items.size(); i++) {
      Var param = event.items.get(i);
      if (param instanceof Param) { // not a rare Return!
        String asType = convertType(param.type);
        if (!"this".equals(param.name) && (i > 0 || !eventClientClassQName.equals(asType))) {
          eventTypeQNameBuilder.append("_").append(param.name);
        }
      }
    }
    eventTypeQNameBuilder.append("Event");
    String eventTypeQName = eventTypeQNameBuilder.toString();

    String eventName = toCamelCase(event.name);

    CompilationUnitModel eventType = compilationUnitModelRegistry.resolveCompilationUnit(eventTypeQName);
    if (eventType == null) {
      eventType = createClassModel(eventTypeQName);
      ClassModel extAsClass = eventType.getClassModel();
      extAsClass.addAnnotation(new AnnotationModel("Rename", new AnnotationPropertyModel(null, "\"" + capitalize(eventTypeQName) + "\"")));
      extAsClass.setSuperclass("net.jangaroo.ext.FlExtEvent");

      MethodModel constructorModel = extAsClass.createConstructor();
      constructorModel.addParam(new ParamModel("type", "String"));
      constructorModel.addParam(new ParamModel("arguments", "Array"));
      constructorModel.setBody("super(type, arguments);");

      StringBuilder parameterSequence = new StringBuilder("[");
      for (Var param : event.items) {
        if (!(param instanceof Param)) {
          continue;
        }
        String parameterName = convertName(param.name);

        // add parameter to sequence constant:
        parameterSequence.append(CompilerUtils.quote(parameterName)).append(", ");

        // add getter method:
        MethodModel property = new MethodModel(MethodType.GET, parameterName, convertType(param.type));
        property.addAnnotation(createArrayElementTypeAnnotation(param.type));
        property.setAsdoc(toAsDoc(param, eventClientClassQName, thisJsClassName));
        extAsClass.addMember(property);
      }
      parameterSequence.append(CompilerUtils.quote("eOpts")).append("]");

      FieldModel parameterSequenceConstant = new FieldModel("__PARAMETER_SEQUENCE__", "Array", parameterSequence.toString());
      parameterSequenceConstant.setStatic(true);
      parameterSequenceConstant.setConst(true);
      extAsClass.addMember(parameterSequenceConstant);
    }

    FieldModel eventNameConstant = new FieldModel(toConstantName(event.name), "String", CompilerUtils.quote("on" + eventName));
    eventNameConstant.setStatic(true);
    eventNameConstant.setConst(true);
    eventNameConstant.setAsdoc(String.format("%s%n@see %s%n@eventType %s", toAsDoc(event, eventClientClassQName, thisJsClassName), eventClientClass.getQName(), "on" + eventName));
    eventType.getClassModel().addMember(eventNameConstant);

    return eventTypeQName;
  }

  private static String toCamelCase(String eventName) {
    if (!eventName.toLowerCase().equals(eventName)) {
      // already CamelCase:
      return eventName;
    }
    StringBuilder camelCaseName = new StringBuilder();
    for (String word : splitIntoWords(eventName)) {
      camelCaseName.append(capitalize(word));
    }
    assert camelCaseName.toString().toLowerCase().equals(eventName);
    return camelCaseName.toString();
  }

  private static String toConstantName(String eventName) {
    StringBuilder constantName = new StringBuilder();
    for (String word : splitIntoWords(eventName.toLowerCase())) {
      constantName.append(word.toUpperCase()).append('_');
    }
    constantName.setLength(constantName.length() - 1); // cut last '_'
    return constantName.toString();
  }

  private static List<String> splitIntoWords(String mergedWords) {
    List<String> words = new ArrayList<String>();
    String remaining = mergedWords;
    while (!remaining.isEmpty()) {
      String candidate = "";
      for (Object keyObject : eventWordsProperties.keySet()) {
        String key = (String) keyObject;
        if (key.length() > candidate.length() && remaining.startsWith(key)) {
          candidate = key;
        }
      }
      if (candidate.isEmpty()) {
        System.err.printf("No word found in dictionary for %s's suffix '%s'.%n", mergedWords, remaining);
        candidate = remaining;
      }
      words.add(candidate);
      remaining = remaining.substring(candidate.length());
    } return words;
  }

  private static void addFields(ClassModel classModel, List<? extends Member> fields, ExtClass extClass) {
    for (Member member : fields) {
      PropertyModel fieldModel = new PropertyModel(convertName(member.name), convertType(member.type));
      setVisibility(fieldModel, member);
      setStatic(fieldModel, member);
      fieldModel.addGetter().setAsdoc(toAsDoc(member, extClass.singleton ? classModel.getName() : "", extClass.name));
      if (!isConst(member)) {
        fieldModel.addSetter().setAsdoc("@private");
      }
      fieldModel.addAnnotation(createArrayElementTypeAnnotation(member.type));
      addDeprecation(member.deprecatedMessage, member.deprecatedVersion, fieldModel);
      classModel.addMember(fieldModel);
    }
  }

  private static void addProperties(ClassModel classModel, ExtClass extClass, List<? extends Member> properties, boolean isConfig) {
    for (Member member : properties) {
      boolean isStatic = !extClass.singleton && extJsApi.isStatic(member);
      String name = convertName(member.name);
      String type = convertType(member.type);
      String asDoc = toAsDoc(member, extClass.singleton ? classModel.getName() : "", extClass.name);
      if (type == null || "*".equals(type) || "Object".equals(type)) {
        // try to deduce a more specific type from the property name:
        type = "cls".equals(member.name) ? "String"
                : "useBodyElement".equals(member.name) ? "Boolean"
                : "items".equals(member.name) || "plugins".equals(member.name) ? "Array"
                : type;
      }
      MemberModel priorMember = classModel.getMember(isStatic, name);
      if (priorMember != null) {
        String priorMemberType;
        if (priorMember.isMethod() && !priorMember.isAccessor()) {
          priorMemberType = "Function";
        } else {
          priorMemberType = priorMember.getType();
        }
        if (!priorMemberType.equals(type)){
          System.err.println("Duplicate member " + member.name + (isConfig ? " (config)" : "")
                  + " in class " + classModel.getName()
                  + " with deviating type " + type + " instead of " + priorMemberType + ".");
        }
        if ("Array".equals(type) && priorMemberType.contains("Collection")) {
          String newName = (name.endsWith("s") ? name.substring(0, name.length() - 1) : name) + "Collection";
          System.out.println("Renaming member " + priorMember.getName() + " to " + newName + " in class " + classModel.getName()
                  + " to avoid name clash with config.");
          priorMember.setName(newName);
        } else if ("Function".equals(priorMemberType)) {
          name += "_";
          System.out.println("Renaming config " + member.name + " to " + name + " in class " + classModel.getName()
                  + " to avoid name clash with method.");
        } else {
          type = priorMemberType;
          asDoc = priorMember.isProperty() ? ((PropertyModel)priorMember).getGetter().getAsdoc() : priorMember.getAsdoc();
          System.out.println("Merging member " + priorMember.getName() + " and config " + member.name + " in class " + classModel.getName()
                  + " to avoid name clash.");
          classModel.removeMember(priorMember);
        }
      }

      PropertyModel propertyModel = new PropertyModel(name, type);
      if (generateForMxml && "items".equals(member.name)) {
        propertyModel.addAnnotation(new AnnotationModel(MxmlUtils.MXML_DEFAULT_PROPERTY_ANNOTATION));
      }
      AnnotationModel arrayElementTypeAnnotation = createArrayElementTypeAnnotation(member.type);
      propertyModel.addAnnotation(arrayElementTypeAnnotation);
      propertyModel.setAsdoc(asDoc);
      addDeprecation(member.deprecatedMessage, member.deprecatedVersion, propertyModel);
      setVisibility(propertyModel, member);
      propertyModel.setStatic(isStatic);
      MethodModel getter = propertyModel.addGetter();
      AnnotationModel extConfigAnnotation = null;
      boolean isAccessor = Boolean.TRUE.equals(member.accessor);
      if (isConfig) {
        extConfigAnnotation = new AnnotationModel(Jooc.EXT_CONFIG_ANNOTATION_NAME);
        if (!name.equals(member.name)) {
          extConfigAnnotation.addProperty(new AnnotationPropertyModel(null, CompilerUtils.quote(member.name)));
        }
        getter.addAnnotation(extConfigAnnotation);
        if (isAccessor) {
          getter.addAnnotation(new AnnotationModel(Jooc.BINDABLE_ANNOTATION_NAME));
        }
      }
      if (isAccessor) {
        MethodModel getMethod = new MethodModel("get" + capitalize(member.name), type); // use original name for get method!
        getMethod.addAnnotation(arrayElementTypeAnnotation);
        getMethod.setAsdoc("Returns the value of <code>" + name + "</code>.\n@see #" + name);
        addDeprecation(member.deprecatedMessage, member.deprecatedVersion, getMethod);
        classModel.addMember(getMethod);
      }
      if (!extJsApi.isReadOnly(member)) {
        MethodModel setter = propertyModel.addSetter();
        if (classModel.isInterface()) {
          // do not add @private to ASDoc in interfaces, or IDEA will completely ignore the declaration!
          setter.setAsdoc(null);
        }
        boolean isWriteOnlyAccessor = isAccessor || "w".equals(member.accessor);
        if (extConfigAnnotation != null) {
          setter.addAnnotation(extConfigAnnotation);
          if (isWriteOnlyAccessor) {
            setter.addAnnotation(new AnnotationModel(Jooc.BINDABLE_ANNOTATION_NAME));
          }
        }
        if (isWriteOnlyAccessor) {
          ParamModel setMethodParam = new ParamModel(name, type);
          MethodModel setMethod = new MethodModel("set" + capitalize(member.name), "void", setMethodParam);
          setMethod.setAsdoc("Sets the value of <code>" + name + "</code>.\n@see #" + name);
          setMethodParam.setAsdoc("The new value.");
          addDeprecation(member.deprecatedMessage, member.deprecatedVersion, setMethod);
          classModel.addMember(setMethod);
        }
      }
      classModel.addMember(propertyModel);
    }
  }

  private static void addMethods(ClassModel classModel, ExtClass extClass, List<Method> methods) {
    for (Method method : methods) {
      String methodName = method.name;
      if (methodName == null || methodName.length() == 0) {
        System.err.printf("methods name missing for method #%d in class %s", methods.indexOf(method) + 1, classModel.getName());
        continue;
      }
      if (classModel.getMember(methodName) == null) {
        boolean isConstructor = methodName.equals("constructor");

        String thisClassName = getThisClassName(classModel, extClass);
        method = getDelegateTag(method, thisClassName);

        List<Var> methodItems = method.items;
        List<Return> returns = filterByType(methodItems, Return.class);
        Return return_ = returns.isEmpty() ? null : returns.get(0);
        MethodModel methodModel = isConstructor
                ? new MethodModel(classModel.getName(), null)
                : new MethodModel(convertName(methodName), return_ == null ? "void" : convertType(return_.type));
        if (return_ != null) {
          methodModel.addAnnotation(createArrayElementTypeAnnotation(return_.type));
        }
        methodModel.setAsdoc(toAsDoc(method, thisClassName, extClass.name));
        if (return_ != null) {
          methodModel.getReturnModel().setAsdoc(toAsDoc(return_, thisClassName, extClass.name));
        }
        setVisibility(methodModel, method);
        if (!extClass.singleton) {
          setStatic(methodModel, method);
        }
        addDeprecation(method.deprecatedMessage, method.deprecatedVersion, methodModel);
        List<Param> params = filterByType(methodItems, Param.class);
        makeUndocumentedTrailingParametersOptional(params);
        for (Param param : params) {
          if ("private".equals(param.access)) {
            continue;
          }
          String paramName = param.name == null ? "param" + (methodItems.indexOf(param) + 1) : convertName(param.name);
          ParamModel paramModel = new ParamModel(paramName, convertType(param.type));
          paramModel.setAsdoc(toAsDoc(param, false, thisClassName, extClass.name));
          setDefaultValue(paramModel, param);
          paramModel.setRest(param == params.get(params.size() - 1) && param.type.contains("..."));
          methodModel.addParam(paramModel);
        }
        try {
          classModel.addMember(methodModel);
        } catch (IllegalArgumentException e) {
          System.err.println("while adding method " + methodModel + ": " + e);
        }
      }
    }
  }

  // fix for undocumented trailing parameters: make them optional!
  private static void makeUndocumentedTrailingParametersOptional(List<Param> params) {
    if (params.size() > 1) {
      // only apply heuristic if first parameter is actually documented:
      Param firstParam = params.get(0);
      if (!isUndocumented(firstParam)) {
        for (int i = params.size() - 1; i > 0; i--) {
          Param param = params.get(i);
          if (isUndocumented(param)) {
            param.optional = true;
          } else {
            break;
          }
        }
        
      }
    }
  }

  private static boolean isUndocumented(Param param) {
    return param.value == null && !param.optional && param.text.isEmpty() && param.type.isEmpty();
  }

  private static <S, T> List<S> filterByType(List<T> methodItems, Class<S> filterType) {
    return methodItems.stream().filter(filterType::isInstance).map(filterType::cast).collect(Collectors.toList());
  }

  private static String getThisClassName(ClassModel classModel, ExtClass extClass) {
    return extClass.singleton ? classModel.getName()
            : classModel.isInterface() ? getActionScriptName(extClass) // use mixin implementation name to fix links to protected methods!
            : "";
  }

  private static void setVisibility(MemberModel memberModel, Member member) {
    memberModel.setNamespace(extJsApi.isProtected(member) ? NamespacedModel.PROTECTED : NamespacedModel.PUBLIC);
  }

  private static void setStatic(MemberModel memberModel, Member member) {
    memberModel.setStatic(extJsApi.isStatic(member));
  }

  private static <T extends Tag> T getDelegateTag(T tag, String thisClassName) {
    if (tag.text.isEmpty() && tag.inheritdoc != null && !"true".equals(tag.inheritdoc)) {
      // dispatch generating ASDoc to given source
      String reference = tag.inheritdoc;
      @SuppressWarnings("unchecked")
      T delegateTag = extJsApi.resolve(reference, thisClassName, (Class<T>) tag.getClass());
      if (delegateTag != null) {
        System.out.println("+*+*+*+* Found 'inheritdoc' in " + thisClassName + "#" + tag.name + ": Dispatching to #" + delegateTag.name);
        return delegateTag;
      } else {
        System.out.println("+*+*+*+* Could not resolve 'inheritdoc' " + tag.inheritdoc + ", ignoring.");
      }
    }
    return tag;
  }

  private static String toAsDoc(Tag tag, String thisClassName, String thisJsClassName) {
    return toAsDoc(tag, true, thisClassName, thisJsClassName);
  }

  private static String toAsDoc(Tag tag, boolean isTopLevelProperty, String thisClassName, String thisJsClassName) {
    StringBuilder asDoc = new StringBuilder();
    if (tag instanceof Method && ((Method) tag).template) {
      asDoc.append("\n<p class=\"template-method\"><i>This is a template method, a hook into the functionality of this class. Feel free to override it in child classes.</i></p>");
    }
    if (tag instanceof Param || isTopLevelProperty && tag instanceof Var) {
      String value = getDefaultValue((Var) tag);
      if (value != null) {
        asDoc.append("\n@default ")
                .append(value.replace("*", "&#42;"));    // escape '*' to not break ASDoc
      }
    }
    if (tag instanceof Member && ((Member)tag).since != null) {
      asDoc.append("\n@since ").append(((Member)tag).since);
    }
    if (tag instanceof Var && !(tag instanceof Method)) { // methods handle their parameters themselves
      List<Var> subParams = ((Var)tag).items;
      if (!subParams.isEmpty()) {
        asDoc.append("\n<ul>");
        for (Var property : subParams) {
          asDoc.append("\n<li>");
          asDoc.append("<code>").append(property.name);
          String propertyType = convertType(property.type);
          if (propertyType != null && !"*".equals(propertyType)) {
            asDoc.append(":").append(propertyType);
          }
          asDoc.append("</code>");
          String defaultValue = getDefaultValue(property);
          if (defaultValue != null) {
            asDoc.append(" (default = <code>").append(defaultValue).append("</code>)");
          } else if (property instanceof Property && !((Property) property).required) {
            asDoc.append(" (optional)");
          }
          String propertyAsDoc = toAsDoc(property, false, thisClassName, thisJsClassName);
          if (!propertyAsDoc.trim().isEmpty()) {
            asDoc.append(" —\n").append(propertyAsDoc).append("\n");
          }
          asDoc.append("</li>");
        }
        asDoc.append("\n</ul>");
      }
    }

    if (tag instanceof ExtClass || (tag instanceof Member && isTopLevelProperty)) {
      String jsReference = thisJsClassName + ".html";
      if (tag instanceof Member) {
        jsReference += String.format("#%s%s-%s", ((Member) tag).static_ ? "static-" : "", tag.$type, tag.name);
      }
      asDoc.append("\n@see " + getSenchaDocsBaseUrl()).append(jsReference)
              .append(" Original Ext JS documentation of '").append(tag.name).append("'");
    }

    String result = asDoc.toString();
    if (tag instanceof Param) {
      // suppress multiple new lines in nested ASDoc, or IDEA will treat everything following as top-level ASDoc:
      result = result.replaceAll("\n+", "\n");
    }
    String mainAsDoc = tag.text;
    if (isTopLevelProperty && tag instanceof Property && ((Property) tag).required
            && !mainAsDoc.startsWith(REQUIRED_CONFIG_TEXT)) {
      mainAsDoc = REQUIRED_CONFIG_TEXT + mainAsDoc;
    } else if (tag instanceof ExtClass && "private".equals(tag.access)) {
      mainAsDoc = PRIVATE_CLASS_TEXT + mainAsDoc;
    }
    mainAsDoc = toAsDoc(mainAsDoc, thisClassName, thisJsClassName);

    boolean isSubTag = !isTopLevelProperty || tag instanceof Param || tag instanceof Return;
    // fix missing full-stops for the ASDoc summary table, but not if this is a sub-tag
    if (!isSubTag) {
      mainAsDoc = fixMissingFullStop(mainAsDoc);
    }
    return mainAsDoc + result;
  }

  private static String getDefaultValue(Var var) {
    String value = var.value;
    if (value != null) {
      value = value.replaceAll("(^|\\s)`([^`]+)`", "$1$2");
      String defaultValue = AS3Type.getDefaultValue(convertType(var.type));
      // Leave out @default only if it equals the type's default null or undefined.
      // We could leave out all default values that equal the type's default, but that would exclude many 
      // booleans with @default false, which is redundant but still adds the value that the author definitely
      // did not just forget to document the default.
      if (("null".equals(defaultValue) || "undefined".equals(defaultValue)) && defaultValue.equals(value)) {
        value = null;
      }
    }
    return value;
  }

  private static String getSenchaDocsBaseUrl() {
    return "https://docs.sencha.com/extjs/" + extJsApi.getVersion() + "/classic/";
  }

  private static String replaceTagSequence(String tagSequence, String replacementSequence, String doc) {
    String[] openAndCloseTags = openAndCloseTags(tagSequence.split(","));
    String[] openAndCloseTagsReplacement = openAndCloseTags(replacementSequence.split(","));
    return doc.replace(openAndCloseTags[0], openAndCloseTagsReplacement[0])
            .replace(openAndCloseTags[1], openAndCloseTagsReplacement[1]);
  }

  private static String[] openAndCloseTags(String[] tagSequence) {
    StringBuilder openTags = new StringBuilder();
    StringBuilder closeTags = new StringBuilder();
    for (String tag : tagSequence) {
      //noinspection StringConcatenationInsideStringBufferAppend
      openTags.append("<" + tag + ">");
      closeTags.insert(0, "</" + tag + ">");
    }
    return new String[]{ openTags.toString(), closeTags.toString() };
  }

  private static String toAsDoc(String doc, String thisClassName, String thisJsClassName) {
    // prevent {@link\n#foo} from being interpreted as a headline:
    doc = doc.replaceAll("\\{@link\n(#.*})", "{@link $1\n");
    // left-align "@example" (it is not part of the code!):
    doc = doc.replaceAll(" *@example( preview)?\n", "@example\n\n");
    // insert missing semicolon to terminate numeric entity:
    doc = doc.replaceAll("(&#[0-9]+)([^0-9;])", "$1;$2");
    // convert markdown to HTML:
    doc = markdownToHtml(doc);
    // escape '*' for ASDoc tool:
    doc = doc.replace("*", "&#42;");
    // undo &quot; escaping to increase readability:
    doc = doc.replace("&quot;", "\"");
    // remove <code> nested into <pre> (otherwise, ASDoc inserts non-ignorable white-space):
    doc = doc.replace("<pre><code>", "<pre>\n").replace("</code></pre>", "</pre>");
    // replace @example doc tags, as there is no syntax that works in both IDEA and ASDoc:
    doc = doc.replace("<p>@example</p>", "<p><b>Example:</b></p>");
    // resolve relative URLs in <a href>:
    doc = resolveRelativeLinks(doc);
    // replace <h1> ... <h4>, since neither IDEA nor asdoc can handle them:
    doc = replaceTagSequence("h1", "p,b", doc);
    doc = replaceTagSequence("h2", "p,b,i", doc);
    doc = replaceTagSequence("h3", "p,i", doc);
    doc = replaceTagSequence("h4", "p,i", doc);

    // process {@link} doc tags:
    doc = processLinkTags(doc, thisClassName, thisJsClassName);

    // remove <locale> and </locale>:
    String asDoc = doc.replaceAll("</?locale>", "");
    asDoc = asDoc.trim();
    if (asDoc.startsWith("<p>")) {
      // remove <p>...</p> around first paragraph:
      asDoc = asDoc.substring(3);
      int endTagPos = asDoc.indexOf("</p>");
      if (endTagPos != -1) {
        asDoc = asDoc.substring(0, endTagPos) + asDoc.substring(endTagPos + 4);
      }
    }
    if (asDoc.startsWith("{")) {
      int closingBracePos = asDoc.indexOf("} ");
      if (closingBracePos != -1) {
        asDoc = asDoc.substring(closingBracePos + 2);
      }
    }
    // add closing "/" on <img> elements:
    asDoc = asDoc.replaceAll("(<img[^>]*[^/])>", "$1/>");
    return asDoc;
  }

  private static String resolveRelativeLinks(String doc) {
    Matcher aHrefMatcher = Pattern.compile("(<a href=\")([^\"]+)").matcher(doc);
    StringBuffer result = new StringBuffer();
    String senchaDocsBaseUrl = getSenchaDocsBaseUrl();
    while (aHrefMatcher.find()) {
      String replacement = "$1$2";
      String url = aHrefMatcher.group(2);
      if (!url.startsWith("http")) {
        String href;
        try {
          href = URI.create(senchaDocsBaseUrl).resolve(url).toString();
        } catch (IllegalArgumentException e) {
          // ignore, use naive approach instead:
          href = senchaDocsBaseUrl + url;
        }
        replacement = "$1" + href;
      }
      aHrefMatcher.appendReplacement(result, replacement);
    }
    aHrefMatcher.appendTail(result);
    return result.toString();
  }

  private static String fixMissingFullStop(String doc) {
    if (!doc.isEmpty()) {
      // ASDoc needs a full-stop at the end of the first sentence. Even colon and semicolon do not work. :-(
      Matcher paragraphEndMatcher = Pattern.compile("\\.|\n<pre|\n<p|\n<ul|\n<ol|\n<table").matcher(doc);
      if (paragraphEndMatcher.find(1)) {
        int start = paragraphEndMatcher.start();
        String match = paragraphEndMatcher.group();
        if (!match.equals(".")) {
          Matcher semicolonMatcher = Pattern.compile(";(\\s).").matcher(doc.substring(0, start));
          if (semicolonMatcher.find()) {
            int semicolonPos = semicolonMatcher.start();
            // upper-case the first character of the word following the semicolon
            return doc.substring(0, semicolonPos) + "." + semicolonMatcher.group(1) + Character.toUpperCase(doc.charAt(semicolonPos + 2)) + doc.substring(semicolonPos + 3);
          }
          int beforeStart = start - 1;
          boolean isColon = doc.charAt(beforeStart) == ':';
          return doc.substring(0, isColon ? beforeStart : start) + (isColon || !"\n<p".equals(match) ? "..." : ".") + doc.substring(start);
        }
      }
    }
    return doc;
  }

  // process {@link} doc tags
  private static String processLinkTags(String doc, String thisClassName, String thisJsClassName) {
    Matcher linkMatcher = INLINE_TAG_OR_LINK_PATTERN.matcher(doc);
    StringBuffer newDoc = new StringBuffer();
    LinkedHashSet<String> sees = new LinkedHashSet<>();
    boolean insideCode = false;
    boolean insideEmphasised = false;
    while (linkMatcher.find()) {
      String codeTag = linkMatcher.group(2);
      if (codeTag != null) {
        boolean startTag = linkMatcher.group(1).isEmpty();
        switch (codeTag) {
          case "code":
            insideCode = startTag;
            break;
          case "i":
            insideEmphasised = startTag;
            break;
        }
        linkMatcher.appendReplacement(newDoc, linkMatcher.group());
        continue;
      }
      String link = linkMatcher.group(4);
      String linkText = linkMatcher.group(5);
      if (linkText == null) {
        linkText = "";
      }

      // normalize link:
      JSDocReference jsDocReference = new JSDocReference(link);
      // report issues:
      if (jsDocReference.url != null) {
        if (!jsDocReference.url.startsWith("http")) {
          System.out.println("*** suspicious reference in {@link}: '" + jsDocReference.url + "'");
        }
      } else if (jsDocReference.getAsDocClassName("") == null) {
        // System.err.println("*** JSDoc class reference could not be resolved: " + jsClassName);
        invalidJsDocReferences.add(jsDocReference.jsClassName);
      }
      // try to normalize linkText (may be a code reference, too):
      JSDocReference jsDocReferenceFromText = new JSDocReference(linkText);
      // merge all gathered information (sometimes methods or events are only detected by analyzing linkText):
      jsDocReference.merge(jsDocReferenceFromText);

      // after merging all information, is there a member, but still no member type?
      if (!jsDocReference.memberName.isEmpty() && jsDocReference.memberType.isEmpty()) {
        Member resolvedMember = extJsApi.resolve(jsDocReference.toString(), thisJsClassName, Member.class);
        if (resolvedMember == null) {
          System.out.println("##### cannot resolve JSDoc reference " + jsDocReference.toString());
        } else {
          jsDocReference.memberType = resolvedMember.$type;
        }
      }

      String rewrittenLink = jsDocReference.toAsString(true);
      boolean addThisClass = "".equals(jsDocReference.jsClassName) && !"".equals(thisClassName);
      String realLink = jsDocReference.toAsString(false);
      String see = (addThisClass ? thisClassName : "") + realLink;
      if (!realLink.equals(rewrittenLink)) {
        see += " " + rewrittenLink;
      }
      sees.add(see);

      // in rendered link, replace '#' by '.' and suppress leading '#'/'.':
      String replacement = prettyPrintLinks(rewrittenLink);

      // wrap in <code> and prepend a unicode "right arrow" to indicate this is actually a hyperlink:
      replacement = "→" + (insideCode ? replacement : html("code", replacement));

      // many Ext @link-s contain obsolete link text that matches the link anyway. Get rid of such:
      boolean renderLinkText = !linkText.isEmpty() && (insideEmphasised || !jsDocReference.linkTextRephrasesLink(linkText));
      if (renderLinkText) {
        linkText = prettyPrintLinks(linkText);
        // wrap link text in <i>, add formatted link in parenthesis:
        replacement = (insideEmphasised || insideCode ? linkText : html("i", linkText)) + " (" + replacement + ")";
      }
      // prevent $ from being interpreted as RegExp group:
      replacement = replacement.replace("$", "\\$");
      linkMatcher.appendReplacement(newDoc, replacement);
    }
    linkMatcher.appendTail(newDoc);
    int lastIndex = newDoc.length() - 1;
    if (lastIndex >= 0 && newDoc.charAt(lastIndex) == '\n') {
      newDoc.setLength(lastIndex);
    }
    for (String see : sees) {
      newDoc.append("\n@see ").append(see);
    }
    doc = newDoc.toString();
    return doc;
  }

  private static String prettyPrintLinks(String link) {
    return link.replaceAll("(\\s|^)#", "$1").replace('#', '.');
  }

  private static String html(String tag, String text) {
    return MessageFormat.format("<{0}>{1}</{0}>", tag, text);
  }

  private static String markdownToHtml(String doc) {
    MutableDataSet options = new MutableDataSet();

    options.set(Parser.EXTENSIONS, Arrays.asList(TablesExtension.create()));

    options.set(HtmlRenderer.FORMAT_FLAGS, HtmlRenderer.FORMAT_ALL_OPTIONS); // TODO: seems to have no effect?!
    options.set(Parser.HEADING_NO_ATX_SPACE, true);
    options.set(HtmlRenderer.EMPHASIS_STYLE_HTML_OPEN, "<i>");
    options.set(HtmlRenderer.EMPHASIS_STYLE_HTML_CLOSE, "</i>");
    options.set(HtmlRenderer.STRONG_EMPHASIS_STYLE_HTML_OPEN, "<b>");
    options.set(HtmlRenderer.STRONG_EMPHASIS_STYLE_HTML_CLOSE, "</b>");

    Parser parser = Parser.builder(options).build();
    HtmlRenderer renderer = HtmlRenderer.builder(options).build();

    // We could re-use parser and renderer instances...
    Node document = parser.parse(doc);
    return renderer.render(document);
  }

  private static void setDefaultValue(ParamModel paramModel, Param param) {
    String defaultValue = param.value;
    if (defaultValue != null) {
      if (NON_COMPILE_TIME_CONSTANT_INITIALIZERS.contains(defaultValue)) {
        paramModel.setAsdoc("(Default " + defaultValue + ") " + paramModel.getAsdoc());
        defaultValue = null;
        param.optional = true; // only in case it is set inconsistently...
      }
    }
    if (defaultValue == null && param.optional) {
      defaultValue = AS3Type.getDefaultValue(paramModel.getType());
    }
    if (defaultValue != null && "String".equals(param.type) &&
            !(defaultValue.equals("null") || defaultValue.startsWith("'") || defaultValue.startsWith("\""))) {
      defaultValue = CompilerUtils.quote(defaultValue);
    }
    paramModel.setValue(defaultValue);
  }

  private static String convertName(String name) {
    name = replaceSeparatorByCamelCase(name, '-');
    return "is".equals(name) ? "matches" :
            "class".equals(name) ? "cls" :
                    "this".equals(name) ? "source" :
                            "new".equals(name) ? "new_" :
                                    "default".equals(name) ? "default_" :
                                            "catch".equals(name) ? "catch_" :
                                                    "override".equals(name) ? "override_" :
                                                            "include".equals(name) ? "include_" :
                                                                    name;
  }

  private static String replaceSeparatorByCamelCase(String string, char separator) {
    while (true) {
      int separatorPos = string.indexOf(separator);
      if (separatorPos == -1) {
        break;
      }
      string = string.substring(0, separatorPos) + string.substring(separatorPos + 1, separatorPos + 2).toUpperCase() + string.substring(separatorPos + 2);
    }
    return string;
  }

  private static String convertToInterface(String className) {
    if (className == null || className.isEmpty() || !interfaces.contains(className)) {
      return null;
    }
    String interfaceName = "I" + CompilerUtils.className(className);
    if (interfaceName.endsWith("Impl")) {
      interfaceName = interfaceName.substring(0, interfaceName.length() - 4);
    }
    return CompilerUtils.qName(CompilerUtils.packageName(className), interfaceName);
  }

  private static String convertType(String extType) {
    if (extType == null) {
      return null;
    }
    if ("undefined".equals(extType) || "null".equals(extType)) {
      return "void";
    }
    if ("number".equals(extType) || "boolean".equals(extType) || "string".equals(extType)) {
      return capitalize(extType);
    }
    if ("HTMLElement".equals(extType) || "Event".equals(extType) || "XMLHttpRequest".equals(extType)) {
      return "js." + extType;
    }
    if ("google.maps.Map".equals(extType) || "CSSStyleSheet".equals(extType) || "CSSStyleRule".equals(extType)) {
      return "Object"; // no AS3 type yet
    }
    // enums and ad-hoc enums:
    if (extType.startsWith("Ext.enums.") || extType.matches("(['\"].*['\"]/)*['\"].*['\"]")) {
      return "String";
    }
    // vararg syntax:
    if (extType.contains("...")) {
      return null; // "...args" works better in IDEA than "...args:Array"
    }
    // array syntax:
    if (TYPED_ARRAY_PATTERN.matcher(extType).matches()) {
      return "Array";
    }
    if (!extType.matches("[a-zA-Z0-9._$<>]+") || "Mixed".equals(extType)) {
      return "*"; // TODO: join types? rather use Object? simulate overloading by splitting into several methods?
    }
    if (JsCodeGenerator.PRIMITIVES.contains(extType)) {
      return extType;
    }
    ExtClass extClass = extJsApi.getExtClass(extType);
    if (extClass == null) {
      //throw new RuntimeException("Fatal: No Ext class '" + extType + "' found.");
      System.err.println("Warning: No Ext class '" + extType + "' found, falling back to Object");
      return "Object";
    }
    String qName = getActionScriptName(extClass);
    if (qName == null || qName.isEmpty()) {
      // try with super class:
      return convertType(extClass.extends_);
    }
    if (isSingleton(extClass)) {
      qName = CompilerUtils.qName(CompilerUtils.packageName(qName), "S" + CompilerUtils.className(qName));
    }
    return qName;
  }

  private static AnnotationModel createArrayElementTypeAnnotation(String extType) {
    if (extType != null) {
      Matcher matcher = TYPED_ARRAY_PATTERN.matcher(extType);
      if (matcher.matches()) {
        String arrayElementType = convertType(matcher.group(1));
        if (!"*".equals(arrayElementType) && !"Object".equals(arrayElementType)) {
          return new AnnotationModel(Jooc.ARRAY_ELEMENT_TYPE_ANNOTATION_NAME,
                  new AnnotationPropertyModel(null, CompilerUtils.quote(arrayElementType)));
        }
      }
    }
    return null;
  }
 
  // normalize / use alternate class name if it can be found in reference API:
  private static void removePrivateApiClasses() {
    // collect all non-public classes:
    Set<ExtClass> privateClasses = new HashSet<ExtClass>();

    for (ExtClass extClass: extClasses) {
      // correct wrong usage of Ext.util.Observable as a mixin:
      replaceMixin(extClass, "Ext.util.Observable", "Ext.mixin.Observable");
      // simplify "extends Base mixins Mixin-that-extends-Base" to "extends Mixin-that-extends-Base":
      replaceMixinByExtends(extClass, "Ext.dom.Element");

      // all classes that are mapped explicitly must remain part of the API:
      String actionScriptName = getActionScriptName(extClass);
      if (actionScriptName != null && !actionScriptName.isEmpty()) {
        continue;
      }

      // Classes to remove from public API:
      // explicitly marked private OR
      // a built-in type / class OR
      // Ext enums - they are just for documentation, so treat them as private API, too.
      if ("private".equals(extClass.access) || JsCodeGenerator.PRIMITIVES.contains(extClass.name) || extClass.name.startsWith("Ext.enums.")) {
        privateClasses.add(extClass);
      }
    }

    // all super classes of public classes must be public:
    for (ExtClass extClass : extClasses) {
      if (!privateClasses.contains(extClass)) {
        markPublic(privateClasses, extClass.name);
      }
    }

    extClasses.removeAll(privateClasses);

    List<String> missingMappings = new ArrayList<>();
    for (ExtClass extClass : extClasses) {
      if (getActionScriptName(extClass) == null) {
        missingMappings.add(extClass.name + " = " + extClass.name.substring(0, 1).toLowerCase() + extClass.name.substring(1) + (extClass.deprecated ? " DEPRECATED" : "") +  ("private".equals(extClass.access) ? " (private)" : "") + (extClass.alias != null ? " // " + extClass.alias : ""));
      }
    }
    Collections.sort(missingMappings);

    System.out.println("*****ADD TO JS-AS-NAME-MAPPING:");
    for (String missingMapping : missingMappings) {
      System.out.println(missingMapping);
      
    }
    System.out.println("*****END ADD TO JS-AS-NAME-MAPPING");
  }

  private static CompilationUnitModel getReferenceDeclaration(String jooClassName) {
    List<CompilationUnitModel> referenceDeclarations = getReferenceDeclarations(jooClassName);
    return referenceDeclarations.isEmpty() ? null : referenceDeclarations.get(0);
  }

  private static List<CompilationUnitModel> getReferenceDeclarations(String jooClassName) {
    return referenceApi.getCompilationUnitModels(jooClassName);
  }

  private static void replaceMixin(ExtClass extClass, String mixinImpl, String mixin) {
    // instead of implementing mixinImpl, a class has to implement its interface:
    int mixinImplIndex = extClass.mixins.indexOf(mixinImpl);
    if (mixinImplIndex != -1) {
      extClass.mixins.set(mixinImplIndex, mixin);
    }
    replaceMixinByExtends(extClass, mixin);
  }

  private static void replaceMixinByExtends(ExtClass extClass, String mixin) {
    // instead of extending Ext.Base and implementing the mixin, it is simpler to extend the mixin
    if ("Ext.Base".equals(extClass.extends_) && extClass.mixins.contains(mixin)) {
      extClass.mixins.remove(mixin);
      extClass.extends_ = mixin;
    }
  }

  private static void markPublic(Set<ExtClass> privateClasses, String extClassName) {
    ExtClass extClass = extJsApi.getExtClass(extClassName);
    if (extClass == null) {
      System.err.println("*** extends-reference to undeclared class " + extClassName);
      return;
    }
    //noinspection StatementWithEmptyBody
    if (privateClasses.remove(extClass)) {
      //System.err.println("*** marked public because it is a super class: " + extClass.name);
    }
    if (extClass.extends_ != null) {
      markPublic(privateClasses, extClass.extends_);
    }
    for (String mixin : extClass.mixins) {
      markPublic(privateClasses, mixin);
    }
  }

  // normalize / use alternate class name if it can be found in reference API:
  private static String getActionScriptName(ExtClass extClass) {
    return getActionScriptName(extClass.name);
  }

  // normalize / use alternate class name if it can be found in reference API:
  private static String getActionScriptName(String extClass) {
    return jsAsNameMappingProperties.getProperty(extClass);
  }


  private static void annotateBindableConfigProperties() {
    for (CompilationUnitModel compilationUnitModel : compilationUnitModelRegistry.getCompilationUnitModels()) {
      ClassModel classModel = compilationUnitModel.getClassModel();
      if (classModel != null) {
        annotateBindableConfigProperties(classModel);
      }
    }
  }

  private static void annotateBindableConfigProperties(ClassModel classModel) {
    List<MemberModel> members = classModel.getMembers();
    // two-pass to get the order of @see #get() and @see #set() right:
    // first, the getters:
    for (MemberModel member : members) {
      if (member.isGetter()) {
        annotateBindableConfigProperty(classModel, (MethodModel) member);
      }
    }
    // then, the setters:
    for (MemberModel member : members) {
      if (member.isSetter()) {
        annotateBindableConfigProperty(classModel, (MethodModel) member);
      }
    }
  }

  private static void annotateBindableConfigProperty(ClassModel classModel, MethodModel accessor) {
    List<AnnotationModel> annotations = accessor.getAnnotations(Jooc.EXT_CONFIG_ANNOTATION_NAME);
    if (annotations.isEmpty()) {
      return;
    }
    String prefix = accessor.getMethodType().toString();

    String propertyType = getMethodType(accessor, accessor.getMethodType());
    if (propertyType == null) {
      warnConfigProperty(prefix + " property accessor without type", classModel, accessor);
      return;
    }

    AnnotationPropertyModel annotationPropertyModel = annotations.get(0).getPropertiesByName().get(null);
    String propertyName = annotationPropertyModel == null ? accessor.getName() : annotationPropertyModel.getStringValue();
    String methodName = prefix + capitalize(propertyName);
    MethodModel method = compilationUnitModelRegistry.resolveMethod(classModel, null, methodName);
    if (method == null) {
      warnConfigProperty("no matching " + prefix + "ter method", classModel, accessor);
      return;
    }

    List<ParamModel> methodParams = method.getParams();
    if (accessor.isSetter() && methodParams.isEmpty()) {
      warnConfigProperty(String.format("matching setter method '%s' without parameters. "
              + "Still marking property as [Bindable] - assuming it's compatible at runtime.",
              method.getName()), classModel, accessor);
    } else {
      List<ParamModel> moreParams = accessor.isSetter() ? methodParams.subList(1, methodParams.size()) : methodParams;
      for (ParamModel param : moreParams) {
        if (!param.isOptional()) {
          warnConfigProperty(String.format("matching %ster method '%s' has additional non-optional parameter '%s'",
                  prefix, method.getName(), param.getName()), classModel, accessor);
          return;
        }
      }

      String methodType = getMethodType(method, accessor.getMethodType());
      if (!propertyType.equals(methodType)) {
        boolean probablyCompatible = "*".equals(propertyType) || "*".equals(methodType)
                || "Object".equals(propertyType) || "Object".equals(methodType);
        if (!probablyCompatible) {
          warnConfigProperty(String.format("type '%s' does not match method '%s' with type '%s'",
                  propertyType, method.getName(), methodType), classModel, accessor);
          return;
        }

        warnConfigProperty(String.format("type '%s' does not quite match method '%s' with type '%s'. "
                + "Still marking property as [Bindable] - assuming it's compatible at runtime.",
                propertyType, method.getName(), methodType), classModel, accessor);
      }
    }

    if (accessor.getAnnotations(Jooc.BINDABLE_ANNOTATION_NAME).isEmpty()) {
      // although not defined as a real 'config', add [Bindable] annotation, but mark it as style="methods":
      accessor.addAnnotation(new AnnotationModel(Jooc.BINDABLE_ANNOTATION_NAME,
              new AnnotationPropertyModel("style", "\"methods\"")));
    }
    MethodModel documentedMethod = null;
    if (accessor.isSetter()) {
      documentedMethod = classModel.getMethod(accessor.isStatic(), MethodType.GET, accessor.getName());
    }
    if (documentedMethod == null) {
      documentedMethod = accessor;
    }
    String asDoc = documentedMethod.getAsdoc();
    documentedMethod.setAsdoc((asDoc == null ? "" : asDoc) + "\n@see #" + methodName + "()");
  }

  private static String getMethodType(MethodModel method, MethodType methodType) {
    if (methodType == MethodType.GET) {
      return method.getType();
    }
    List<ParamModel> propertySetterParams = method.getParams();
    if (propertySetterParams.isEmpty()) {
      return null;
    }
    return propertySetterParams.get(0).getType();
  }

  private static void warnConfigProperty(String message, ClassModel classModel, MethodModel propertySetter) {
    System.err.format("!!! Config property %s#%s: %s\n", classModel.getName(), propertySetter.getName(), message);
  }

  private static class JSDocReference {
    private static final Pattern JSDOC_REF_PATTERN = Pattern.compile("^([A-Z][A-Za-z0-9_$.]*)?(?:#(?:(method|static-method|cfg|property|static-property|event|var|sass-mixin)[!-])?([a-zA-Z0-9_$-]+)(\\(\\))?)?$");

    String url = null;
    String actionScriptClassName = "";
    String jsClassName = "";
    String memberType = "";
    String memberName = "";
    boolean hasParentheses;

    private JSDocReference(String jsDocReference) {
      Matcher jsDocRefMatcher = JSDOC_REF_PATTERN.matcher(jsDocReference);
      if (jsDocRefMatcher.matches()) {
        jsClassName = nullToEmptyString(jsDocRefMatcher.group(1));
        memberType = nullToEmptyString(jsDocRefMatcher.group(2));
        memberName = nullToEmptyString(jsDocRefMatcher.group(3));
        hasParentheses = jsDocRefMatcher.group(4) != null;
      } else {
        url = jsDocReference;
      }
    }

    boolean linkTextRephrasesLink(String linkText) {
      StringBuilder regExpBuilder = new StringBuilder();
      String packageName = CompilerUtils.packageName(jsClassName);
      String className = CompilerUtils.className(jsClassName);
      String asDocQualifiedClassName = getAsDocClassName("");
      if (asDocQualifiedClassName == null) {
        asDocQualifiedClassName = jsClassName;
      }
      String asDocClassName = CompilerUtils.className(asDocQualifiedClassName);

      if (!className.isEmpty()) {
        regExpBuilder.append("(");
        if (!packageName.isEmpty()) {
          regExpBuilder.append("(").append(packageName.replace(".", "\\.")).append("\\.)?");
        }
        regExpBuilder.append("(");
        regExpBuilder.append(className);
        if (!asDocClassName.equals(className)) {
          regExpBuilder.append("|").append(asDocClassName); // for some weird cases, also allow AS class name...
        }
        regExpBuilder.append(")");
        if (!memberName.isEmpty()) {
          regExpBuilder.append("([#.])");
        }
        regExpBuilder.append(")?");
      } else {
        regExpBuilder.append("(#)?");
      }
      if (!memberName.isEmpty()) {
        if (!memberType.isEmpty()) {
          regExpBuilder.append("(").append(memberType).append("[-!])?");
        }
        regExpBuilder.append(memberName);
        if (isMethod()) {
          regExpBuilder.append("(\\(\\))?");
        }
      }
      Matcher matcher = Pattern.compile(regExpBuilder.toString()).matcher(linkText);

      return matcher.matches();
    }

    private String nullToEmptyString(String group) {
      return group == null ? "" : group;
    }

    boolean isMethod() {
      return hasParentheses || memberType.contains("method");
    }

    private boolean isEvent() {
      return memberType.equals("event");
    }

    void merge(JSDocReference other) {
      if (url != null || other.url != null) {
        return;
      }
      if (hasParentheses || other.hasParentheses) {
        hasParentheses = other.hasParentheses = true;
      }
      if (memberType.isEmpty()) {
        memberType = other.memberType;
      } else if (other.memberType.isEmpty()) {
        other.memberType = memberType;
      }
    }

    String toAsString(boolean asLinkText) {
      if (url != null) {
        return url;
      }
      ExtClass extClass = extJsApi.getExtClass(jsClassName);
      boolean singleton = extClass != null && extClass.singleton;
      String asDocClassName = getAsDocClassName(asLinkText ? "" : memberName.isEmpty() ? "#" : "S");
      if (actionScriptClassName == null) {
        return null;
      }
      StringBuilder builder = new StringBuilder();
      builder.append(asDocClassName);
      if (!memberName.isEmpty()) {
        builder.append(asLinkText && singleton ? "." : "#");
        if (isEvent()) {
          String flexEventName = "on" + toCamelCase(memberName);
          builder.append("event:").append(flexEventName);
        } else if (memberName.startsWith("$")) { // reference to SASS variable
          builder.append("style:").append(memberName);  // currently unsupported by IDEA, but who knows...
        } else {
          builder.append(convertName(memberName)); // might be "is" etc.
          if (isMethod()) {
            builder.append("()");
          }
        }
      }
      return builder.toString();
    }

    private String getAsDocClassName(String singletonPrefix) {
      if (jsClassName.isEmpty()) {
        return "";
      }
      ExtClass extClass = extJsApi.getExtClass(jsClassName);
      if (extClass != null) {
        String actionScriptName = getActionScriptName(extClass);
        if (actionScriptName != null && !actionScriptName.isEmpty()) {
          if (extClass.singleton) {
            return CompilerUtils.qName(CompilerUtils.packageName(actionScriptName),
                    singletonPrefix + CompilerUtils.className(actionScriptName));
          } else {
            return actionScriptName;
          }
        } else if (!jsClassName.contains(".")) {
          // top-level, built-in type:
          return jsClassName;
        }
      }
      return null;
    }

    @Override
    public String toString() {
      return url != null ? url : jsClassName +
              (memberName.isEmpty() ? "" : "#" +
                      (memberType.isEmpty() ? "" : memberType + "!") + memberName +
                      (isMethod() ? "()" : "")
              );
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof JSDocReference && toString().equals(obj.toString());
    }
  }

}
