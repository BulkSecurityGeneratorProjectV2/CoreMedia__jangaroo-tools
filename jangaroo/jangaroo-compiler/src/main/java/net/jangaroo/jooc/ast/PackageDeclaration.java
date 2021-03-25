/*
 * Copyright 2008 CoreMedia AG
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

package net.jangaroo.jooc.ast;

import net.jangaroo.jooc.JooSymbol;
import net.jangaroo.jooc.Scope;

import java.io.IOException;

/**
 * @author Andreas Gawecki
 * @author Frank Wienberg
 */
public class PackageDeclaration extends IdeDeclaration {

  private JooSymbol symPackage;

  public PackageDeclaration(JooSymbol symPackage, Ide ide) {
    super(ide);
    this.symPackage = symPackage;
  }

  @Override
  public PackageDeclaration getPackageDeclaration() {
    return this;
  }

  @Override
  public void visit(AstVisitor visitor) throws IOException {
    visitor.visitPackageDeclaration(this);
  }

  @Override
  public void scope(Scope scope) {
    Ide oldIde = getIde();
    setIde(null);
    super.scope(scope);
    setIde(oldIde);
  }

  @Override
  public JooSymbol getDeclarationSymbol() {
    return getSymPackage();
  }

  public boolean isTopLevel() {
    return getIde() == null;
  }

  public JooSymbol getSymPackage() {
    return symPackage;
  }

}
