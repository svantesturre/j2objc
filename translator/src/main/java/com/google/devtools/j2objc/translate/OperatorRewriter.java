/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.j2objc.translate;

import com.google.devtools.j2objc.Options;
import com.google.devtools.j2objc.ast.Assignment;
import com.google.devtools.j2objc.ast.Expression;
import com.google.devtools.j2objc.ast.FieldAccess;
import com.google.devtools.j2objc.ast.FunctionInvocation;
import com.google.devtools.j2objc.ast.InfixExpression;
import com.google.devtools.j2objc.ast.NullLiteral;
import com.google.devtools.j2objc.ast.PrefixExpression;
import com.google.devtools.j2objc.ast.QualifiedName;
import com.google.devtools.j2objc.ast.SimpleName;
import com.google.devtools.j2objc.ast.ThisExpression;
import com.google.devtools.j2objc.ast.TreeUtil;
import com.google.devtools.j2objc.ast.TreeVisitor;
import com.google.devtools.j2objc.types.Types;
import com.google.devtools.j2objc.util.BindingUtil;
import com.google.devtools.j2objc.util.NameTable;

import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;

import java.util.List;

/**
 * Rewrites certain operators, such as object assignment, into appropriate
 * method calls.
 *
 * @author Keith Stanger
 */
public class OperatorRewriter extends TreeVisitor {

  private static Expression getTarget(Expression node, IVariableBinding var) {
    if (node instanceof QualifiedName) {
      return ((QualifiedName) node).getQualifier();
    } else if (node instanceof FieldAccess) {
      return ((FieldAccess) node).getExpression();
    }
    return new ThisExpression(var.getDeclaringClass());
  }

  @Override
  public void endVisit(Assignment node) {
    Assignment.Operator op = node.getOperator();
    Expression lhs = node.getLeftHandSide();
    Expression rhs = node.getRightHandSide();
    ITypeBinding lhsType = lhs.getTypeBinding();
    ITypeBinding rhsType = rhs.getTypeBinding();
    if (op == Assignment.Operator.ASSIGN) {
      IVariableBinding var = TreeUtil.getVariableBinding(lhs);
      if (var == null || var.getType().isPrimitive() || !Options.useReferenceCounting()) {
        return;
      }
      if (BindingUtil.isStatic(var)) {
        node.replaceWith(newStaticAssignInvocation(var, rhs));
      } else if (var.isField() && !BindingUtil.isWeakReference(var)) {
        Expression target = getTarget(lhs, var);
        node.replaceWith(newFieldSetterInvocation(var, target, rhs));
      }
    } else if (op == Assignment.Operator.RIGHT_SHIFT_UNSIGNED_ASSIGN) {
      if (!lhsType.getName().equals("char")) {
        node.replaceWith(newUnsignedRightShift(lhsType, lhs, rhs));
      }
    } else if (op == Assignment.Operator.REMAINDER_ASSIGN) {
      if (isFloatingPoint(lhsType) || isFloatingPoint(rhsType)) {
        node.replaceWith(newModAssign(lhsType, lhs, rhs));
      }
    }
  }

  public void endVisit(InfixExpression node) {
    InfixExpression.Operator op = node.getOperator();
    ITypeBinding nodeType = node.getTypeBinding();
    if (op == InfixExpression.Operator.REMAINDER && isFloatingPoint(nodeType)) {
      String funcName = nodeType.getName().equals("float") ? "fmodf" : "fmod";
      FunctionInvocation invocation = new FunctionInvocation(funcName, nodeType, nodeType, null);
      List<Expression> args = invocation.getArguments();
      args.add(TreeUtil.remove(node.getLeftOperand()));
      args.add(TreeUtil.remove(node.getRightOperand()));
      node.replaceWith(invocation);
    }
  }

  private boolean isFloatingPoint(ITypeBinding type) {
    return type.getName().equals("double") || type.getName().equals("float");
  }

  private FunctionInvocation newStaticAssignInvocation(IVariableBinding var, Expression value) {
    FunctionInvocation invocation = new FunctionInvocation(
        "JreOperatorRetainedAssign", value.getTypeBinding(), Types.resolveIOSType("id"), null);
    List<Expression> args = invocation.getArguments();
    args.add(new PrefixExpression(PrefixExpression.Operator.ADDRESS_OF, new SimpleName(var)));
    args.add(new NullLiteral());
    args.add(value.copy());
    return invocation;
  }

  private static FunctionInvocation newFieldSetterInvocation(
      IVariableBinding var, Expression instance, Expression value) {
    ITypeBinding varType = var.getType();
    ITypeBinding declaringType = var.getDeclaringClass().getTypeDeclaration();
    String setterName = String.format("%s_set_%s", NameTable.getFullName(declaringType),
        NameTable.javaFieldToObjC(NameTable.getName(var)));
    FunctionInvocation invocation = new FunctionInvocation(
        setterName, varType, varType, declaringType);
    invocation.getArguments().add(instance.copy());
    invocation.getArguments().add(value.copy());
    return invocation;
  }

  private static FunctionInvocation newUnsignedRightShift(
      ITypeBinding assignType, Expression lhs, Expression rhs) {
    String funcName = "URShiftAssign" + NameTable.capitalize(assignType.getName());
    FunctionInvocation invocation = new FunctionInvocation(funcName, assignType, assignType, null);
    List<Expression> args = invocation.getArguments();
    args.add(new PrefixExpression(PrefixExpression.Operator.ADDRESS_OF, lhs.copy()));
    args.add(rhs.copy());
    return invocation;
  }

  private static FunctionInvocation newModAssign(
      ITypeBinding lhsType, Expression lhs, Expression rhs) {
    String funcName = "ModAssign" + NameTable.capitalize(lhsType.getName());
    FunctionInvocation invocation = new FunctionInvocation(funcName, lhsType, lhsType, null);
    List<Expression> args = invocation.getArguments();
    args.add(new PrefixExpression(PrefixExpression.Operator.ADDRESS_OF, lhs.copy()));
    args.add(rhs.copy());
    return invocation;
  }
}
