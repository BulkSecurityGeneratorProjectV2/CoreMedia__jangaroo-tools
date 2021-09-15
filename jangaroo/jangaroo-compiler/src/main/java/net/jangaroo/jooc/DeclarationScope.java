/*
 * Copyright 2010 CoreMedia AG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package net.jangaroo.jooc;

import net.jangaroo.jooc.ast.AstNode;
import net.jangaroo.jooc.ast.ClassDeclaration;
import net.jangaroo.jooc.ast.CompilationUnit;
import net.jangaroo.jooc.ast.FunctionDeclaration;
import net.jangaroo.jooc.ast.FunctionExpr;
import net.jangaroo.jooc.ast.Ide;
import net.jangaroo.jooc.ast.IdeDeclaration;
import net.jangaroo.jooc.ast.ImportDirective;
import net.jangaroo.jooc.ast.PackageDeclaration;
import net.jangaroo.jooc.ast.QualifiedIde;
import net.jangaroo.jooc.ast.TypeDeclaration;
import net.jangaroo.jooc.ast.VariableDeclaration;
import net.jangaroo.utils.AS3Type;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @author Andreas Gawecki
 */
public class DeclarationScope extends AbstractScope {

  private final JangarooParser compiler;

  private AstNode definingNode;
  private Set<String> packages = new HashSet<String>();
  private Map<String, IdeDeclaration> ides = new HashMap<String, IdeDeclaration>();
  private Map<String, List<ImportDirective>> importsByName = new HashMap<String, List<ImportDirective>>();
  private Map<String, ImportDirective> importsByQualifiedName = new HashMap<String, ImportDirective>();
  private boolean isInstanceScope = false;

  public boolean isPackage(String fullyQualifiedName) {
    return packages.contains(fullyQualifiedName) || super.isPackage(fullyQualifiedName);
  }

  public DeclarationScope(AstNode definingNode, Scope parent, JangarooParser compiler) {
    super(parent);
    this.definingNode = definingNode;
    this.compiler = compiler;
  }

  @Override
  public JangarooParser getCompiler() {
    return compiler;
  }

  @Override
  public AstNode getDefiningNode() {
    return definingNode;
  }

  @Override
  public void addImport(final ImportDirective importDirective) {
    if (!(getDefiningNode() instanceof CompilationUnit)) {
      // Jangaroo can only handle top-level imports, so collect them at the compilation unit:
      getParentScope().addImport(importDirective);
      return;
    }
    Ide ide = importDirective.getIde();
    String name = ide.getName();
    Ide packageIde = ide.getQualifier();
    String packageName = "";
    if (packageIde != null) {
      packageName = packageIde.getQualifiedNameStr();
      packages.add(packageName);
    }
    if (AS3Type.ANY.toString().equals(name)) {
      final List<String> packageIdes = getCompiler().getPackageIdes(packageName);
      for (String typeToImport : packageIdes) {
        ImportDirective implicitImport = new ImportDirective(packageIde, typeToImport);
        implicitImport.scope(this);
      }
    } else {
      if (importsByName.containsKey(name)) {
        final List<ImportDirective> directiveList = importsByName.get(name);
        if (isImportAlreadyAdded(directiveList, importDirective)) {
          return;
        }
        directiveList.add(importDirective);
      } else {
        List<ImportDirective> list = new LinkedList<ImportDirective>();
        list.add(importDirective);
        importsByName.put(name, list);
      }
      if (ides.containsKey(name)) {
        // name clash with value ide - error according to adobe
        throw new CompilerError(importDirective.getIde().getSymbol(), "attempt to redefine identifier " + name + " by import");
      }
      // define the fully qualified name if not (might be the same string for top level imports):
      final String qualifiedName = ide.getQualifiedNameStr();
      importsByQualifiedName.put(qualifiedName, importDirective);
    }
  }

  private boolean isImportAlreadyAdded(final List<ImportDirective> directiveList, final ImportDirective importDirective) {
    final String qname = importDirective.getQualifiedName();
    for (ImportDirective directive : directiveList) {
      if (directive.getQualifiedName().equals(qname)) {
        return true;
      }
    }
    return false;
  }

  public void addDependencyFromJooGetOrCreatePackage(CompilationUnit compilationUnitFromJooGetOrCreatePackage, JooSymbol referenceSymbol) {
    Ide localNameIde = compilationUnitFromJooGetOrCreatePackage.getPrimaryDeclaration().getIde();
    IdeDeclaration ideDeclaration = lookupDeclaration(localNameIde);
    if (ideDeclaration != null && equals(ideDeclaration.getIde().getScope())) {
      getCompiler().getLog().error(referenceSymbol,
              String.format("Name clash of implicit import through joo.getOrCreatePackage() and local variable %s.", localNameIde.getName()));
    }
    getCompilationUnit().addDependency(compilationUnitFromJooGetOrCreatePackage, false);
  }

  @Override
  public IdeDeclaration declareIde(IdeDeclaration decl) {
    final Ide ide = decl.getIde();
    final String name = ide.getName();
    if (importsByName.containsKey(name)) {
      throw new CompilerError(ide.getSymbol(), "attempt to redefine an imported identifier " + name);
    }
    return ides.put(name, decl);
  }

  @Override
  public IdeDeclaration lookupDeclaration(Ide ide, boolean failOnAmbigousImport) {
    IdeDeclaration decl = null;
    if (ide instanceof QualifiedIde) {
      String qname = ide.getQualifiedNameStr();
      if (importsByQualifiedName.containsKey(qname)) {
        return resolveImport(importsByQualifiedName.get(qname));
      }
      if (ide.isQualifiedByThis()) {
        return getClassDeclaration().resolvePropertyDeclaration(ide.getName());
      }
      if (ide.isQualifiedBySuper()) {
        final TypeDeclaration superTypeDeclaration = getClassDeclaration().getSuperTypeDeclaration();
        return superTypeDeclaration == null ? null : superTypeDeclaration.resolvePropertyDeclaration(ide.getName());
      }
    } else {
      final String name = ide.getName();
      final List<ImportDirective> importsOfThisIde = importsByName.get(name);
      if (importsOfThisIde != null) {
        if (failOnAmbigousImport && importsOfThisIde.size() > 1) {
          ambigousImport(ide, importsOfThisIde);
        }
        try {
          return resolveImport(importsOfThisIde.get(0));
        } catch (CompilerError e) {
          getCompiler().getLog().error(ide.getIde(), e.getMessage());
        }
      }
      decl = ides.get(ide.getName());
      if (decl == null && getDefiningNode() != null && getClassDeclaration() == getDefiningNode()) {
        if (isInstanceScope) {
          decl = getClassDeclaration().resolvePropertyDeclaration(ide.getName(), false);
        }
        if (decl == null) {
          decl = getClassDeclaration().resolvePropertyDeclaration(ide.getName(), true);
        }
      }
    }
    return decl != null ? decl : super.lookupDeclaration(ide, failOnAmbigousImport);
  }


  private IdeDeclaration resolveImport(final ImportDirective importDirective) {
    return getCompiler().resolveImport(importDirective);
  }

  private void ambigousImport(Ide ide, Collection<ImportDirective> importsOfThisIde) {
    boolean isFirst = true;
    StringBuilder msg = new StringBuilder();
    msg.append("Can not resolve a multiname reference unambiguously: ");
    for (ImportDirective importDirective : importsOfThisIde) {
      if (!isFirst) {
        msg.append(" and ");
      }
      isFirst = false;
      msg.append(importDirective.getQualifiedName());
      JooSymbol importedIdeSymbol = resolveImport(importDirective).getSymbol();
      msg.append("(").append(importedIdeSymbol.getFileName()).append(":").append(importedIdeSymbol.getLine()).append(",").append(importedIdeSymbol.getColumn());
    }
    msg.append(" are available.");
    throw new CompilerError(ide.getSymbol(), msg.toString());
  }

  public boolean isDeclared(Ide ide) {
    return ides.containsKey(ide.getQualifiedNameStr()) || super.isDeclared(ide);
  }

  @Override
  public Ide findFreeAuxVar(String preferredName) {
    String auxVarNamePrefix = null != preferredName ? preferredName + "_$" : "$";
    for (int i = 1; ; ++i) {
      String auxVarName = auxVarNamePrefix + i;
      if (!ides.containsKey(auxVarName)) {
        return new Ide(new JooSymbol(auxVarName));
      }
    }
  }

  @Override
  public Ide createAuxVar(String preferredName) {
    Ide auxVar = findFreeAuxVar(preferredName);
    new VariableDeclaration(null, auxVar, null).scope(this);
    return auxVar;
  }

  @Override
  public CompilationUnit getCompilationUnit() {
    if (definingNode instanceof CompilationUnit) {
      return (CompilationUnit) definingNode;
    }
    return super.getCompilationUnit();
  }

  @Override
  public PackageDeclaration getPackageDeclaration() {
    if (definingNode instanceof PackageDeclaration) {
      return (PackageDeclaration) definingNode;
    }
    return super.getPackageDeclaration();
  }

  @Override
  public ClassDeclaration getClassDeclaration() {
    if (definingNode instanceof ClassDeclaration) {
      return (ClassDeclaration) definingNode;
    }
    return super.getClassDeclaration();
  }

  @Override
  public DeclarationScope getPackageDeclarationScope() {
    return definingNode instanceof PackageDeclaration ? this : super.getPackageDeclarationScope();
  }

  @Override
  public FunctionDeclaration getMethodDeclaration() {
    if (definingNode instanceof FunctionDeclaration) {
      final FunctionDeclaration functionDeclaration = (FunctionDeclaration) definingNode;
      if (functionDeclaration.isClassMember()) {
        return functionDeclaration;
      }
    }
    return super.getMethodDeclaration();
  }

  @Override
  public FunctionExpr getFunctionExpr() {
    if (definingNode instanceof FunctionExpr) {
      return (FunctionExpr) definingNode;
    }
    return super.getFunctionExpr();
  }

  public void setIsInstanceScope(boolean b) {
    isInstanceScope = true;
  }
}
