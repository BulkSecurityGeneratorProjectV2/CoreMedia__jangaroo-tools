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
import java.util.List;

/**
 * @author Andreas Gawecki
 */
public class ObjectField extends NodeImplBase implements ObjectFieldOrSpread {

  private AstNode label;
  private JooSymbol symColon;
  private Expr value;

  public ObjectField(AstNode label, JooSymbol symColon, Expr value) {
    this.label = label;
    this.symColon = symColon;
    this.value = value;
  }

  @Override
  public List<? extends AstNode> getChildren() {
    return makeChildren(super.getChildren(), label, value);
  }

  @Override
  public void visit(AstVisitor visitor) throws IOException {
    visitor.visitObjectField(this);
  }

  @Override
  public void scope(final Scope scope) {
    getLabel().scope(scope);
    getValue().scope(scope);
  }

  public void analyze(AstNode parentNode) {
    super.analyze(parentNode);
    // do not analyze label, it is only a string, not a real identifier and might be misinterpreted!
    getValue().analyze(this);
  }

  public boolean isStandAloneConstant() {
    return value.isStandAloneConstant();
  }

  public JooSymbol getSymbol() {
    return getLabel().getSymbol();
  }

  public AstNode getLabel() {
    return label;
  }

  public JooSymbol getSymColon() {
    return symColon;
  }

  public Expr getValue() {
    return value;
  }
}
