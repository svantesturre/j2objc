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
import com.google.devtools.j2objc.ast.Block;
import com.google.devtools.j2objc.ast.ClassInstanceCreation;
import com.google.devtools.j2objc.ast.ConstructorInvocation;
import com.google.devtools.j2objc.ast.EnumConstantDeclaration;
import com.google.devtools.j2objc.ast.EnumDeclaration;
import com.google.devtools.j2objc.ast.ExpressionStatement;
import com.google.devtools.j2objc.ast.MethodDeclaration;
import com.google.devtools.j2objc.ast.NativeDeclaration;
import com.google.devtools.j2objc.ast.NumberLiteral;
import com.google.devtools.j2objc.ast.SimpleName;
import com.google.devtools.j2objc.ast.SingleVariableDeclaration;
import com.google.devtools.j2objc.ast.Statement;
import com.google.devtools.j2objc.ast.StringLiteral;
import com.google.devtools.j2objc.ast.SuperConstructorInvocation;
import com.google.devtools.j2objc.ast.TreeUtil;
import com.google.devtools.j2objc.ast.TreeVisitor;
import com.google.devtools.j2objc.types.GeneratedMethodBinding;
import com.google.devtools.j2objc.types.GeneratedVariableBinding;
import com.google.devtools.j2objc.types.Types;
import com.google.devtools.j2objc.util.BindingUtil;
import com.google.devtools.j2objc.util.NameTable;

import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Modifier;

import java.util.List;

/**
 * Modifies enum types for Objective C.
 *
 * @author Keith Stanger
 */
public class EnumRewriter extends TreeVisitor {

  private GeneratedVariableBinding nameVar = null;
  private GeneratedVariableBinding ordinalVar = null;

  private final ITypeBinding stringType = Types.resolveIOSType("NSString");
  private final ITypeBinding intType = Types.resolveJavaType("int");

  private IMethodBinding addEnumConstructorParams(IMethodBinding method) {
    GeneratedMethodBinding newMethod = new GeneratedMethodBinding(method);
    newMethod.addParameter(stringType);
    newMethod.addParameter(intType);
    return newMethod;
  }

  @Override
  public void endVisit(EnumDeclaration node) {
    MethodDeclaration initMethod = getOrCreateStaticInit(node);
    List<Statement> stmts = initMethod.getBody().getStatements().subList(0, 0);
    int i = 0;
    for (EnumConstantDeclaration constant : node.getEnumConstants()) {
      IMethodBinding binding =
          addEnumConstructorParams(constant.getMethodBinding().getMethodDeclaration());
      ClassInstanceCreation creation = new ClassInstanceCreation(binding);
      TreeUtil.copyList(constant.getArguments(), creation.getArguments());
      String name = NameTable.getName(constant.getName().getBinding());
      creation.getArguments().add(new StringLiteral(name));
      creation.getArguments().add(new NumberLiteral(i++));
      creation.setHasRetainedResult(true);
      stmts.add(new ExpressionStatement(new Assignment(
          new SimpleName(constant.getVariableBinding()), creation)));
    }

    addExtraNativeDecls(node);
  }

  @Override
  public boolean visit(MethodDeclaration node) {
    assert nameVar == null && ordinalVar == null;
    IMethodBinding binding = node.getMethodBinding();
    ITypeBinding declaringClass = binding.getDeclaringClass();
    if (!binding.isConstructor() || !declaringClass.isEnum()) {
      return false;
    }
    IMethodBinding newBinding = addEnumConstructorParams(node.getMethodBinding());
    node.setMethodBinding(newBinding);
    nameVar = new GeneratedVariableBinding(
        "__name", 0, stringType, false, true, declaringClass, newBinding);
    ordinalVar = new GeneratedVariableBinding(
        "__ordinal", 0, intType, false, true, declaringClass, newBinding);
    node.getParameters().add(new SingleVariableDeclaration(nameVar));
    node.getParameters().add(new SingleVariableDeclaration(ordinalVar));
    return true;
  }

  @Override
  public void endVisit(MethodDeclaration node) {
    nameVar = ordinalVar = null;
  }

  @Override
  public void endVisit(ConstructorInvocation node) {
    assert nameVar != null && ordinalVar != null;
    node.setMethodBinding(addEnumConstructorParams(node.getMethodBinding()));
    node.getArguments().add(new SimpleName(nameVar));
    node.getArguments().add(new SimpleName(ordinalVar));
  }

  @Override
  public void endVisit(SuperConstructorInvocation node) {
    assert nameVar != null && ordinalVar != null;
    node.setMethodBinding(addEnumConstructorParams(node.getMethodBinding()));
    node.getArguments().add(new SimpleName(nameVar));
    node.getArguments().add(new SimpleName(ordinalVar));
  }

  private static MethodDeclaration getOrCreateStaticInit(EnumDeclaration enumType) {
    for (MethodDeclaration method : TreeUtil.getMethodDeclarations(enumType)) {
      if (BindingUtil.isInitializeMethod(method.getMethodBinding())) {
        return method;
      }
    }
    GeneratedMethodBinding binding = GeneratedMethodBinding.newMethod(
        NameTable.CLINIT_NAME, Modifier.PUBLIC | Modifier.STATIC, Types.resolveJavaType("void"),
        enumType.getTypeBinding());
    MethodDeclaration newMethod = new MethodDeclaration(binding);
    newMethod.setBody(new Block());
    enumType.getBodyDeclarations().add(newMethod);
    return newMethod;
  }

  private static void addExtraNativeDecls(EnumDeclaration node) {
    String typeName = NameTable.getFullName(node.getTypeBinding());
    int numConstants = node.getEnumConstants().size();

    String header = String.format(
        "+ (IOSObjectArray *)values;\n\n"
        + "+ (%s *)valueOfWithNSString:(NSString *)name;\n\n"
        + "- (id)copyWithZone:(NSZone *)zone;\n", typeName);

    StringBuilder sb = new StringBuilder();
    sb.append(String.format(
        "+ (IOSObjectArray *)values {\n"
        + "  return [IOSObjectArray arrayWithObjects:%s_values count:%s type:"
        + "[IOSClass classWithClass:[%s class]]];\n"
        + "}\n\n", typeName, numConstants, typeName));

    sb.append(String.format(
        "+ (%s *)valueOfWithNSString:(NSString *)name {\n"
        + "  for (int i = 0; i < %s; i++) {\n"
        + "    %s *e = %s_values[i];\n"
        + "    if ([name isEqual:[e name]]) {\n"
        + "      return e;\n"
        + "    }\n"
        + "  }\n", typeName, numConstants, typeName, typeName));
    if (Options.useReferenceCounting()) {
      sb.append(
          "  @throw [[[JavaLangIllegalArgumentException alloc] initWithNSString:name]"
          + " autorelease];\n");
    } else {
      sb.append("  @throw [[JavaLangIllegalArgumentException alloc] initWithNSString:name];\n");
    }
    sb.append("  return nil;\n}\n\n");

    // Enum constants needs to implement NSCopying.  Being singletons, they
    // can just return self, as long the retain count is incremented.
    String selfString = Options.useReferenceCounting() ? "[self retain]" : "self";
    sb.append(String.format("- (id)copyWithZone:(NSZone *)zone {\n  return %s;\n}\n", selfString));

    node.getBodyDeclarations().add(new NativeDeclaration(header, sb.toString()));
  }
}
