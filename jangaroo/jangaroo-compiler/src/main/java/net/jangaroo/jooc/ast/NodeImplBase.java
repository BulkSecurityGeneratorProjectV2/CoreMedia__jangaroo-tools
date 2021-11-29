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

import net.jangaroo.jooc.DeclarationScope;
import net.jangaroo.jooc.Scope;
import net.jangaroo.jooc.sym;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author Andreas Gawecki
 */
public abstract class NodeImplBase implements AstNode {

  private AstNode parentNode;
  protected boolean usesInstanceThis;

  @Override
  public void notifyInstanceThisUsed() {
    usesInstanceThis = true;
    if (propagateInstanceThisUsed()) {
      if (parentNode != null) {
        parentNode.notifyInstanceThisUsed();
      } else {
        System.err.println("*** Cannot propagate instance this usage of node of type " +getClass().getName() + ", because parentNode is null.");
      }
    }
  }

  protected boolean propagateInstanceThisUsed() {
    return true;
  }

  @Override
  public boolean usesInstanceThis() {
    return usesInstanceThis;
  }

  public List<? extends AstNode> getChildren() {
    return Collections.emptyList();
  }

  public List<AstNode> makeChildren(Object  ... objects) {
    List<AstNode> result = new ArrayList<AstNode>();
    for (Object object : objects) {
      if (object instanceof AstNode) {
        result.add((AstNode) object);
      } else if (object instanceof Collection) {
        Collection<?> subobjects = (Collection<?>) object;
        for (Object subobject : subobjects) {
          if (subobject instanceof AstNode) {
            result.add((AstNode) subobject);
          }
        }
      }
    }
    return result;
  }

  public void analyze(AstNode parentNode) {
    this.parentNode = parentNode;
  }

  public AstNode getParentNode() {
    return parentNode;
  }

  public <N extends AstNode> void scope(List<N> nodes, Scope scope) {
    for (AstNode node : nodes) {
      node.scope(scope);
    }
  }
  public <N extends AstNode> void analyze(AstNode parent, List<N> nodes) {
    for (AstNode node : nodes) {
      node.analyze(parent);
    }
  }
  public interface Scoped {

    void run(Scope scope);

  }

  public void withNewDeclarationScope(final AstNode definingNode, final Scope scope, final Scoped scoped) {
    scoped.run(new DeclarationScope(definingNode, scope, scope.getCompiler()));
  }

  public void withNewLabelScope(final Statement statement, final Scope scope, final Scoped scoped) {
    scoped.run(new LabelScope(statement, scope));
  }

  public boolean isAssignmentLHS() {
    AstNode containingExpr = getParentNode();
    if (containingExpr instanceof AssignmentOpExpr) {
      return isMe(((AssignmentOpExpr) containingExpr).getArg1());
    } else if (containingExpr instanceof UnaryOpExpr) {
      // modifying unary operators also act as an assignment!
      int op = ((UnaryOpExpr) containingExpr).getOp().sym;
      return op == sym.PLUSPLUS || op == sym.PREFIX_PLUSPLUS ||
              op == sym.MINUSMINUS || op == sym.PREFIX_MINUSMINUS;
    }
    return false;
  }

  boolean isMe(Expr expr) {
    return expr == this;
  }

}
