/*
 * Copyright 2011 Google Inc. All Rights Reserved.
 *
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

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.devtools.j2objc.ast.AnnotationTypeDeclaration;
import com.google.devtools.j2objc.ast.AnonymousClassDeclaration;
import com.google.devtools.j2objc.ast.Assignment;
import com.google.devtools.j2objc.ast.Block;
import com.google.devtools.j2objc.ast.BodyDeclaration;
import com.google.devtools.j2objc.ast.BreakStatement;
import com.google.devtools.j2objc.ast.CastExpression;
import com.google.devtools.j2objc.ast.ClassInstanceCreation;
import com.google.devtools.j2objc.ast.ContinueStatement;
import com.google.devtools.j2objc.ast.DoStatement;
import com.google.devtools.j2objc.ast.EmptyStatement;
import com.google.devtools.j2objc.ast.EnhancedForStatement;
import com.google.devtools.j2objc.ast.EnumDeclaration;
import com.google.devtools.j2objc.ast.Expression;
import com.google.devtools.j2objc.ast.ExpressionStatement;
import com.google.devtools.j2objc.ast.FieldAccess;
import com.google.devtools.j2objc.ast.FieldDeclaration;
import com.google.devtools.j2objc.ast.ForStatement;
import com.google.devtools.j2objc.ast.IfStatement;
import com.google.devtools.j2objc.ast.InfixExpression;
import com.google.devtools.j2objc.ast.InstanceofExpression;
import com.google.devtools.j2objc.ast.LabeledStatement;
import com.google.devtools.j2objc.ast.MethodDeclaration;
import com.google.devtools.j2objc.ast.MethodInvocation;
import com.google.devtools.j2objc.ast.Name;
import com.google.devtools.j2objc.ast.NullLiteral;
import com.google.devtools.j2objc.ast.ParenthesizedExpression;
import com.google.devtools.j2objc.ast.PrefixExpression;
import com.google.devtools.j2objc.ast.QualifiedName;
import com.google.devtools.j2objc.ast.ReturnStatement;
import com.google.devtools.j2objc.ast.SimpleName;
import com.google.devtools.j2objc.ast.SingleVariableDeclaration;
import com.google.devtools.j2objc.ast.Statement;
import com.google.devtools.j2objc.ast.SuperMethodInvocation;
import com.google.devtools.j2objc.ast.SwitchStatement;
import com.google.devtools.j2objc.ast.ThrowStatement;
import com.google.devtools.j2objc.ast.TreeUtil;
import com.google.devtools.j2objc.ast.TreeVisitor;
import com.google.devtools.j2objc.ast.Type;
import com.google.devtools.j2objc.ast.TypeDeclaration;
import com.google.devtools.j2objc.ast.VariableDeclarationExpression;
import com.google.devtools.j2objc.ast.VariableDeclarationFragment;
import com.google.devtools.j2objc.ast.VariableDeclarationStatement;
import com.google.devtools.j2objc.ast.WhileStatement;
import com.google.devtools.j2objc.types.GeneratedMethodBinding;
import com.google.devtools.j2objc.types.GeneratedTypeBinding;
import com.google.devtools.j2objc.types.GeneratedVariableBinding;
import com.google.devtools.j2objc.types.Types;
import com.google.devtools.j2objc.util.BindingUtil;
import com.google.devtools.j2objc.util.ErrorUtil;
import com.google.devtools.j2objc.util.NameTable;
import com.google.j2objc.annotations.AutoreleasePool;
import com.google.j2objc.annotations.RetainedLocalRef;

import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.IPackageBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Modifier;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Rewrites the Java AST to replace difficult to translate code with methods
 * that are more Objective C/iOS specific. For example, Objective C doesn't have
 * the concept of class variables, so they need to be replaced with static
 * accessor methods referencing private static data.
 *
 * @author Tom Ball
 */
public class Rewriter extends TreeVisitor {

  private Map<IVariableBinding, IVariableBinding> localRefs = Maps.newHashMap();

  /**
   * The list of Objective-C type qualifier keywords.
   */
  private static final List<String> typeQualifierKeywords = Lists.newArrayList("in", "out",
      "inout", "oneway", "bycopy", "byref");

  @Override
  public boolean visit(TypeDeclaration node) {
    return visitType(node.getTypeBinding(), node.getBodyDeclarations(), node.getModifiers());
  }

  @Override
  public boolean visit(EnumDeclaration node) {
    return visitType(node.getTypeBinding(), node.getBodyDeclarations(), node.getModifiers());
  }

  @Override
  public boolean visit(AnonymousClassDeclaration node) {
    return visitType(node.getTypeBinding(), node.getBodyDeclarations(), Modifier.NONE);
  }

  @Override
  public boolean visit(AnnotationTypeDeclaration node) {
    return visitType(node.getTypeBinding(), node.getBodyDeclarations(), node.getModifiers());
  }

  private boolean visitType(
      ITypeBinding typeBinding, List<BodyDeclaration> members, int modifiers) {
    ITypeBinding[] interfaces = typeBinding.getInterfaces();
    if (interfaces.length > 0) {
      if (Modifier.isAbstract(modifiers) || typeBinding.isEnum()) {

        // Add any interface methods that aren't defined by this abstract type.
        // Obj-C needs these to verify that the generated class implements the
        // interface/protocol.
        for (ITypeBinding intrface : interfaces) {
          // Collect needed methods from this interface and all super-interfaces.
          Queue<ITypeBinding> interfaceQueue = new LinkedList<ITypeBinding>();
          Set<IMethodBinding> interfaceMethods = new LinkedHashSet<IMethodBinding>();
          interfaceQueue.add(intrface);
          while ((intrface = interfaceQueue.poll()) != null) {
            interfaceMethods.addAll(Arrays.asList(intrface.getDeclaredMethods()));
            interfaceQueue.addAll(Arrays.asList(intrface.getInterfaces()));
          }
          addMissingMethods(typeBinding, interfaceMethods, members);
        }
      } else if (!typeBinding.isInterface()) {
        // Check for methods that the type *explicitly implements* for cases
        // where a superclass provides the implementation.  For example, many
        // Java interfaces define equals(Object) to provide documentation, which
        // a class doesn't need to implement in Java, but does in Obj-C.  These
        // classes need a forwarding method to pass the Obj-C compiler.
        Set<IMethodBinding> interfaceMethods = new LinkedHashSet<IMethodBinding>();
        for (ITypeBinding intrface : interfaces) {
          interfaceMethods.addAll(Arrays.asList(intrface.getDeclaredMethods()));
        }
        addForwardingMethods(typeBinding, interfaceMethods, members);
      }
    }

    renameDuplicateMembers(typeBinding);
    return true;
  }

  private void addMissingMethods(
      ITypeBinding typeBinding, Set<IMethodBinding> interfaceMethods, List<BodyDeclaration> decls) {
    for (IMethodBinding interfaceMethod : interfaceMethods) {
      if (!isMethodImplemented(typeBinding, interfaceMethod, decls)) {
        addAbstractMethod(typeBinding, interfaceMethod, decls);
      }
    }
  }

  private void addForwardingMethods(
      ITypeBinding typeBinding, Set<IMethodBinding> interfaceMethods, List<BodyDeclaration> decls) {
    for (IMethodBinding interfaceMethod : interfaceMethods) {
      String methodName = interfaceMethod.getName();
      // These are the only java.lang.Object methods that are both overridable
      // and translated to Obj-C.
      if (methodName.matches("equals|hashCode|toString")) {
        if (!isMethodImplemented(typeBinding, interfaceMethod, decls)) {
          addForwardingMethod(typeBinding, interfaceMethod, decls);
        }
      }
    }
  }

  private boolean isMethodImplemented(
      ITypeBinding type, IMethodBinding interfaceMethod, List<BodyDeclaration> decls) {
    for (BodyDeclaration decl : decls) {
      if (decl instanceof MethodDeclaration
          && ((MethodDeclaration) decl).getMethodBinding().isSubsignature(interfaceMethod)) {
        return true;
      }
    }
    return isMethodImplemented(type.getSuperclass(), interfaceMethod);
  }

  private boolean isMethodImplemented(ITypeBinding type, IMethodBinding method) {
    if (type == null || type.getQualifiedName().equals("java.lang.Object")) {
      return false;
    }

    for (IMethodBinding m : type.getDeclaredMethods()) {
      if (method.isSubsignature(m)
          || (method.getName().equals(m.getName())
          && method.getReturnType().getErasure().isEqualTo(m.getReturnType().getErasure())
          && Arrays.equals(method.getParameterTypes(), m.getParameterTypes()))) {
        return true;
      }
    }

    return isMethodImplemented(type.getSuperclass(), method);
  }

  @Override
  public boolean visit(MethodDeclaration node) {
    IMethodBinding binding = node.getMethodBinding();

    if (BindingUtil.hasAnnotation(binding, AutoreleasePool.class)) {
      if (!binding.getReturnType().isPrimitive()) {
        ErrorUtil.warning(
            "Ignoring AutoreleasePool annotation on method with retainable return type");
      } else if (node.getBody() != null) {
        node.getBody().setHasAutoreleasePool(true);
      }
    }

    // change the names of any methods that conflict with NSObject messages
    String name = binding.getName();
    renameReservedNames(name, binding);

    handleCompareToMethod(node, binding);

    List<SingleVariableDeclaration> params = node.getParameters();
    for (int i = 0; i < params.size(); i++) {
      // Change the names of any parameters that are type qualifier keywords.
      SingleVariableDeclaration param = params.get(i);
      name = param.getName().getIdentifier();
      if (typeQualifierKeywords.contains(name)) {
        IVariableBinding varBinding = param.getVariableBinding();
        NameTable.rename(varBinding, name + "Arg");
      }
    }

    // Rename any labels that have the same names; legal in Java but not C.
    final Map<String, Integer> labelCounts = Maps.newHashMap();
    node.accept(new TreeVisitor() {
      @Override
      public void endVisit(LabeledStatement labeledStatement) {
        final String name = labeledStatement.getLabel().getIdentifier();
        int value = labelCounts.containsKey(name) ? labelCounts.get(name) + 1 : 1;
        labelCounts.put(name, value);
        if (value > 1) {
          final String newName = name + '_' + value;
          labeledStatement.setLabel(new SimpleName(newName));
          // Update references to this label.
          labeledStatement.accept(new TreeVisitor() {
            @Override
            public void endVisit(ContinueStatement node) {
              if (node.getLabel() != null && node.getLabel().getIdentifier().equals(name)) {
                node.setLabel(new SimpleName(newName));
              }
            }
            @Override
            public void endVisit(BreakStatement node) {
              if (node.getLabel() != null && node.getLabel().getIdentifier().equals(name)) {
                node.setLabel(new SimpleName(newName));
              }
            }
          });

        }
      }
    });
    return true;
  }

  /**
   * Adds an instanceof check to compareTo methods. This helps Comparable types
   * behave well in sorted collections which rely on Java's runtime type
   * checking.
   */
  private void handleCompareToMethod(MethodDeclaration node, IMethodBinding binding) {
    if (!binding.getName().equals("compareTo") || node.getBody() == null) {
      return;
    }
    ITypeBinding comparableType =
        BindingUtil.findInterface(binding.getDeclaringClass(), "java.lang.Comparable");
    if (comparableType == null) {
      return;
    }
    ITypeBinding[] typeArguments = comparableType.getTypeArguments();
    ITypeBinding[] parameterTypes = binding.getParameterTypes();
    if (typeArguments.length != 1 || parameterTypes.length != 1
        || !typeArguments[0].isEqualTo(parameterTypes[0])) {
      return;
    }

    IVariableBinding param = node.getParameters().get(0).getVariableBinding();

    Expression nullCheck = new InfixExpression(
        Types.resolveJavaType("boolean"), InfixExpression.Operator.NOT_EQUALS,
        new SimpleName(param), new NullLiteral());
    Expression instanceofExpr = new InstanceofExpression(new SimpleName(param), typeArguments[0]);
    instanceofExpr = new PrefixExpression(PrefixExpression.Operator.NOT, instanceofExpr);

    ITypeBinding cceType = GeneratedTypeBinding.newTypeBinding(
        "java.lang.ClassCastException", Types.resolveJavaType("java.lang.RuntimeException"), false);
    ClassInstanceCreation newCce = new ClassInstanceCreation(
        GeneratedMethodBinding.newConstructor(cceType, 0));

    ThrowStatement throwStmt = new ThrowStatement(newCce);

    Block ifBlock = new Block();
    ifBlock.getStatements().add(throwStmt);

    IfStatement ifStmt = new IfStatement();
    ifStmt.setExpression(new InfixExpression(
        Types.resolveJavaType("boolean"), InfixExpression.Operator.CONDITIONAL_AND, nullCheck,
        instanceofExpr));
    ifStmt.setThenStatement(ifBlock);

    node.getBody().getStatements().add(0, ifStmt);
  }

  @Override
  public boolean visit(MethodInvocation node) {
    IMethodBinding binding = node.getMethodBinding();
    String name = binding.getName();
    renameReservedNames(name, binding);
    return true;
  }

  @Override
  public boolean visit(SuperMethodInvocation node) {
    renameReservedNames(node.getName().getIdentifier(), node.getMethodBinding());
    return true;
  }

  private void renameReservedNames(String name, IMethodBinding binding) {
    if (NameTable.isReservedName(name)) {
      NameTable.rename(binding, name + "__");
    }
  }

  private static Statement getLoopBody(Statement s) {
    if (s instanceof DoStatement) {
      return ((DoStatement) s).getBody();
    } else if (s instanceof EnhancedForStatement) {
      return ((EnhancedForStatement) s).getBody();
    } else if (s instanceof ForStatement) {
      return ((ForStatement) s).getBody();
    } else if (s instanceof WhileStatement) {
      return ((WhileStatement) s).getBody();
    }
    return null;
  }

  @Override
  public void endVisit(LabeledStatement node) {
    Statement loopBody = getLoopBody(node.getBody());

    final String labelIdentifier = node.getLabel().getIdentifier();

    final boolean[] hasContinue = new boolean[1];
    final boolean[] hasBreak = new boolean[1];
    node.accept(new TreeVisitor() {
      @Override
      public void endVisit(ContinueStatement node) {
        if (node.getLabel() != null && node.getLabel().getIdentifier().equals(labelIdentifier)) {
          hasContinue[0] = true;
          node.setLabel(new SimpleName("continue_" + labelIdentifier));
        }
      }
      @Override
      public void endVisit(BreakStatement node) {
        if (node.getLabel() != null && node.getLabel().getIdentifier().equals(labelIdentifier)) {
          hasBreak[0] = true;
          node.setLabel(new SimpleName("break_" + labelIdentifier));
        }
      }
    });

    if (hasContinue[0]) {
      assert loopBody != null : "Continue statements must be inside a loop.";
      LabeledStatement newLabelStmt = new LabeledStatement("continue_" + labelIdentifier);
      newLabelStmt.setBody(new EmptyStatement());
      // Put the loop body into an inner block so the continue label is outside
      // the scope of any variable initializations.
      Block newBlock = new Block();
      loopBody.replaceWith(newBlock);
      newBlock.getStatements().add(loopBody);
      newBlock.getStatements().add(newLabelStmt);
    }
    if (hasBreak[0]) {
      LabeledStatement newLabelStmt = new LabeledStatement("break_" + labelIdentifier);
      newLabelStmt.setBody(new EmptyStatement());
      TreeUtil.insertAfter(node, newLabelStmt);
    }

    if (hasContinue[0] || hasBreak[0]) {
      // Replace this node with its statement, thus deleting the label.
      node.replaceWith(TreeUtil.remove(node.getBody()));
    }
  }

  @Override
  public void endVisit(ForStatement node) {
    // It should not be possible to have multiple VariableDeclarationExpression
    // nodes in the initializers.
    if (node.getInitializers().size() == 1) {
      Object initializer = node.getInitializers().get(0);
      if (initializer instanceof VariableDeclarationExpression) {
        List<VariableDeclarationFragment> fragments =
            ((VariableDeclarationExpression) initializer).getFragments();
        for (VariableDeclarationFragment fragment : fragments) {
          if (BindingUtil.hasAnnotation(fragment.getVariableBinding(), AutoreleasePool.class)) {
            Statement loopBody = node.getBody();
            if (!(loopBody instanceof Block)) {
              Block block = new Block();
              node.setBody(block);
              block.getStatements().add(loopBody);
            }
            ((Block) node.getBody()).setHasAutoreleasePool(true);
          }
        }
      }
    }
  }

  @Override
  public void endVisit(InfixExpression node) {
    InfixExpression.Operator op = node.getOperator();
    ITypeBinding type = node.getTypeBinding();
    ITypeBinding lhsType = node.getLeftOperand().getTypeBinding();
    ITypeBinding rhsType = node.getRightOperand().getTypeBinding();
    if (Types.isJavaStringType(type) && op == InfixExpression.Operator.PLUS
        && !Types.isJavaStringType(lhsType) && !Types.isJavaStringType(rhsType)) {
      // String concatenation where the first two operands are not strings.
      // We move all the preceding non-string operands into a sub-expression.
      ITypeBinding nonStringExprType = getAdditionType(lhsType, rhsType);
      InfixExpression nonStringExpr = new InfixExpression(
          nonStringExprType, InfixExpression.Operator.PLUS, TreeUtil.remove(node.getLeftOperand()),
          TreeUtil.remove(node.getRightOperand()));
      InfixExpression stringExpr = new InfixExpression(
          Types.resolveJavaType("java.lang.String"), InfixExpression.Operator.PLUS, nonStringExpr,
          null);
      List<Expression> extendedOperands = node.getExtendedOperands();
      List<Expression> nonStringOperands = nonStringExpr.getExtendedOperands();
      List<Expression> stringOperands = stringExpr.getExtendedOperands();
      boolean foundStringType = false;
      for (Expression expr : extendedOperands) {
        Expression copiedExpr = expr.copy();
        ITypeBinding exprType = expr.getTypeBinding();
        if (foundStringType || Types.isJavaStringType(exprType)) {
          if (foundStringType) {
            stringOperands.add(copiedExpr);
          } else {
            stringExpr.setRightOperand(copiedExpr);
          }
          foundStringType = true;
        } else {
          nonStringOperands.add(copiedExpr);
          nonStringExprType = getAdditionType(nonStringExprType, exprType);
        }
      }
      nonStringExpr.setTypeBinding(nonStringExprType);
      node.replaceWith(stringExpr);
    } else if (op == InfixExpression.Operator.CONDITIONAL_AND) {
      // Avoid logical-op-parentheses compiler warnings.
      if (node.getParent() instanceof InfixExpression) {
        InfixExpression parent = (InfixExpression) node.getParent();
        if (parent.getOperator() == InfixExpression.Operator.CONDITIONAL_OR) {
          ParenthesizedExpression.parenthesizeAndReplace(node);
        }
      }
    } else if (op == InfixExpression.Operator.AND) {
      // Avoid bitwise-op-parentheses compiler warnings.
      if (node.getParent() instanceof InfixExpression
          && ((InfixExpression) node.getParent()).getOperator() == InfixExpression.Operator.OR) {
        ParenthesizedExpression.parenthesizeAndReplace(node);
      }
    }

    // Avoid lower precedence compiler warnings.
    if (op == InfixExpression.Operator.AND || op == InfixExpression.Operator.OR) {
      if (node.getLeftOperand() instanceof InfixExpression) {
        ParenthesizedExpression.parenthesizeAndReplace(node.getLeftOperand());
      }
      if (node.getRightOperand() instanceof InfixExpression) {
        ParenthesizedExpression.parenthesizeAndReplace(node.getRightOperand());
      }
    }
  }

  private ITypeBinding getAdditionType(ITypeBinding aType, ITypeBinding bType) {
    ITypeBinding doubleType = Types.resolveJavaType("double");
    ITypeBinding boxedDoubleType = Types.resolveJavaType("java.lang.Double");
    if (aType == doubleType || bType == doubleType
        || aType == boxedDoubleType || bType == boxedDoubleType) {
      return doubleType;
    }
    ITypeBinding floatType = Types.resolveJavaType("float");
    ITypeBinding boxedFloatType = Types.resolveJavaType("java.lang.Float");
    if (aType == floatType || bType == floatType
        || aType == boxedFloatType || bType == boxedFloatType) {
      return floatType;
    }
    ITypeBinding longType = Types.resolveJavaType("long");
    ITypeBinding boxedLongType = Types.resolveJavaType("java.lang.Long");
    if (aType == longType || bType == longType
        || aType == boxedLongType || bType == boxedLongType) {
      return longType;
    }
    return Types.resolveJavaType("int");
  }

  /**
   * Moves all variable declarations above the first case statement.
   */
  @Override
  public void endVisit(SwitchStatement node) {
    List<Statement> statements = node.getStatements();
    int insertIdx = 0;
    Block block = new Block();
    List<Statement> blockStmts = block.getStatements();
    for (int i = 0; i < statements.size(); i++) {
      Statement stmt = statements.get(i);
      if (stmt instanceof VariableDeclarationStatement) {
        VariableDeclarationStatement declStmt = (VariableDeclarationStatement) stmt;
        statements.remove(i--);
        List<VariableDeclarationFragment> fragments = declStmt.getFragments();
        for (VariableDeclarationFragment decl : fragments) {
          Expression initializer = decl.getInitializer();
          if (initializer != null) {
            Assignment assignment = new Assignment(decl.getName().copy(), initializer.copy());
            statements.add(++i, new ExpressionStatement(assignment));
            decl.setInitializer(null);
          }
        }
        blockStmts.add(insertIdx++, declStmt.copy());
      }
    }
    if (blockStmts.size() > 0) {
      // There is at least one variable declaration, so copy this switch
      // statement into the new block and replace it in the parent list.
      node.replaceWith(block);
      blockStmts.add(node);
    }
  }

  /**
   * Add an abstract method to the given type that implements the given
   * interface method binding.
   */
  private void addAbstractMethod(
      ITypeBinding typeBinding, IMethodBinding interfaceMethod, List<BodyDeclaration> decls) {
    MethodDeclaration method = createInterfaceMethodBody(
        typeBinding, interfaceMethod, interfaceMethod.getModifiers());

    method.addModifiers(Modifier.ABSTRACT);

    decls.add(method);
  }

  /**
   * Java interfaces that redeclare java.lang.Object's equals, hashCode, or
   * toString methods need a forwarding method if the implementing class
   * relies on java.lang.Object's implementation.  This is because NSObject
   * is declared as adhering to the NSObject protocol, but doesn't explicitly
   * declare these method in its interface.  This prevents gcc from finding
   * an implementation, so it issues a warning.
   */
  private void addForwardingMethod(
      ITypeBinding typeBinding, IMethodBinding interfaceMethod, List<BodyDeclaration> decls) {
    Logger.getAnonymousLogger().fine(String.format("adding %s to %s",
        interfaceMethod.getName(), typeBinding.getQualifiedName()));
    MethodDeclaration method =
        createInterfaceMethodBody(typeBinding, interfaceMethod, Modifier.PUBLIC);

    // Add method body with single "super.method(parameters);" statement.
    Block body = new Block();
    method.setBody(body);
    SuperMethodInvocation superInvocation = new SuperMethodInvocation(method.getMethodBinding());

    for (SingleVariableDeclaration param : method.getParameters()) {
      Expression arg = param.getName().copy();
      superInvocation.getArguments().add(arg);
    }
    body.getStatements().add(new ReturnStatement(superInvocation));

    decls.add(method);
  }

  private MethodDeclaration createInterfaceMethodBody(
      ITypeBinding typeBinding, IMethodBinding interfaceMethod, int modifiers) {
    GeneratedMethodBinding methodBinding =
        GeneratedMethodBinding.newOverridingMethod(interfaceMethod, typeBinding, modifiers);
    MethodDeclaration method = new MethodDeclaration(methodBinding);

    ITypeBinding[] parameterTypes = interfaceMethod.getParameterTypes();
    for (int i = 0; i < parameterTypes.length; i++) {
      ITypeBinding paramType = parameterTypes[i];
      IVariableBinding paramBinding = new GeneratedVariableBinding(
          "param" + i, 0, paramType, false, true, typeBinding, methodBinding);
      method.getParameters().add(new SingleVariableDeclaration(paramBinding));
      methodBinding.addParameter(paramType);
    }
    return method;
  }

  /**
   * If a field and method have the same name, or if a field hides a visible
   * superclass field, rename the field.  This is necessary to avoid a name
   * clash when the fields are declared as properties.
   */
  private void renameDuplicateMembers(ITypeBinding typeBinding) {
    Map<String, IVariableBinding> fields = Maps.newHashMap();

    // Check all superclass(es) fields with declared fields.
    ITypeBinding superclass = typeBinding.getSuperclass();
    if (superclass != null) {
      addFields(superclass, true, true, fields);
      for (IVariableBinding var : typeBinding.getDeclaredFields()) {
        String name = var.getName();
        IVariableBinding field = fields.get(name);
        if (field != null) {
          name += '_' + typeBinding.getName();
          NameTable.rename(var, name);
          fields.put(name, var);
        }
      }
    }
  }

  private void addFields(ITypeBinding type, boolean includePrivate, boolean includeSuperclasses,
      Map<String, IVariableBinding> fields) {
    for (IVariableBinding field : type.getDeclaredFields()) {
      if (!fields.containsValue(field)) { // if not already renamed
        int mods = field.getModifiers();
        if (!Modifier.isStatic(mods)) {
          if (includePrivate) {
            fields.put(field.getName(), field);
          } else if (Modifier.isPublic(mods) || Modifier.isProtected(mods)) {
            fields.put(field.getName(), field);
          } else {
            IPackageBinding typePackage = type.getPackage();
            IPackageBinding fieldPackage = field.getDeclaringClass().getPackage();
            if (typePackage.isEqualTo(fieldPackage)) {
              fields.put(field.getName(), field);
            }
          }
        }
      }
    }
    ITypeBinding superclass = type.getSuperclass();
    if (includeSuperclasses && superclass != null) {
      addFields(superclass, false, true, fields);
    }
  }

  @Override
  public void endVisit(SingleVariableDeclaration node) {
    if (node.getExtraDimensions() > 0) {
      node.setType(Type.newType(node.getVariableBinding().getType()));
      node.setExtraDimensions(0);
    }
  }

  @Override
  public void endVisit(VariableDeclarationStatement node) {
    LinkedListMultimap<Integer, VariableDeclarationFragment> newDeclarations =
        rewriteExtraDimensions(node.getType(), node.getFragments());
    if (newDeclarations != null) {
      List<Statement> statements = ((Block) node.getParent()).getStatements();
      int location = 0;
      while (location < statements.size() && !node.equals(statements.get(location))) {
        location++;
      }
      for (Integer dimensions : newDeclarations.keySet()) {
        List<VariableDeclarationFragment> fragments = newDeclarations.get(dimensions);
        VariableDeclarationStatement newDecl = new VariableDeclarationStatement(fragments.get(0));
        newDecl.getFragments().addAll(fragments.subList(1, fragments.size()));
        statements.add(++location, newDecl);
      }
    }
    // Scan modifiers since variable declarations don't have variable bindings.
    if (TreeUtil.hasAnnotation(RetainedLocalRef.class, node.getAnnotations())) {
      ITypeBinding localRefType = Types.getLocalRefType();
      node.setType(Type.newType(localRefType));

      // Convert fragments to retained local refs.
      for (VariableDeclarationFragment fragment : node.getFragments()) {
        IVariableBinding var = fragment.getVariableBinding();
        GeneratedVariableBinding newVar = new GeneratedVariableBinding(
            var.getName(), var.getModifiers(), localRefType, false, false,
            var.getDeclaringClass(), var.getDeclaringMethod());
        localRefs.put(var, newVar);

        Expression initializer = fragment.getInitializer();
        if (localRefs.containsKey(TreeUtil.getVariableBinding(initializer))) {
          initializer.accept(this);
        } else {
          // Create a constructor for a ScopedLocalRef for this fragment.
          IMethodBinding constructor = null;
          for (IMethodBinding m : localRefType.getDeclaredMethods()) {
            if (m.isConstructor()) {
              constructor = m;
              break;
            }
          }
          assert constructor != null : "failed finding ScopedLocalRef(var)";
          ClassInstanceCreation newInvocation = new ClassInstanceCreation(constructor);
          newInvocation.getArguments().add(initializer.copy());
          fragment.setInitializer(newInvocation);
          fragment.setVariableBinding(newVar);
        }
      }
    }
  }

  @Override
  public void endVisit(FieldDeclaration node) {
    LinkedListMultimap<Integer, VariableDeclarationFragment> newDeclarations =
        rewriteExtraDimensions(node.getType(), node.getFragments());
    if (newDeclarations != null) {
      List<BodyDeclaration> bodyDecls = TreeUtil.getBodyDeclarations(node.getParent());
      int location = 0;
      while (location < bodyDecls.size() && !node.equals(bodyDecls.get(location))) {
        location++;
      }
      for (Integer dimensions : newDeclarations.keySet()) {
        List<VariableDeclarationFragment> fragments = newDeclarations.get(dimensions);
        FieldDeclaration newDecl = new FieldDeclaration(fragments.get(0));
        newDecl.getFragments().addAll(fragments.subList(1, fragments.size()));
        bodyDecls.add(++location, newDecl);
      }
    }
  }

  @Override
  public boolean visit(QualifiedName node) {
    // Check for ScopedLocalRefs.
    IBinding var = node.getBinding();
    if (var instanceof IVariableBinding) {
      IVariableBinding localRef = localRefs.get(node.getQualifier().getBinding());
      if (localRef != null) {
        IVariableBinding localRefFieldBinding = Types.getLocalRefType().getDeclaredFields()[0];
        SimpleName localRefField = new SimpleName(localRefFieldBinding);
        Name newQualifier = node.getQualifier().copy();
        newQualifier.setBinding(localRef);
        FieldAccess localRefAccess = new FieldAccess(localRefFieldBinding, newQualifier);
        CastExpression newCast = new CastExpression(
            node.getQualifier().getTypeBinding(), localRefAccess);
        ParenthesizedExpression newParens = ParenthesizedExpression.parenthesize(newCast);
        FieldAccess access = new FieldAccess((IVariableBinding) var, newParens);
        node.replaceWith(access);
        return false;
      }
    }
    return true;
  }

  @Override
  public void endVisit(SimpleName node) {
    // Check for enum fields with reserved names.
    IVariableBinding var = TreeUtil.getVariableBinding(node);
    if (var != null) {
      var = var.getVariableDeclaration();
      ITypeBinding type = var.getDeclaringClass();
      if (type != null && !type.isArray()) {
        String fieldName = NameTable.getName(var);
        while ((type = type.getSuperclass()) != null) {
          for (IVariableBinding superField : type.getDeclaredFields()) {
            if (superField.getName().equals(fieldName)) {
              fieldName += '_' + NameTable.getName(var.getDeclaringClass());
              NameTable.rename(var, fieldName);
              return;
            }
          }
        }
      }
    }

    // Check for ScopedLocalRefs.
    IVariableBinding localRef = localRefs.get(node.getBinding());
    if (localRef != null) {
      FieldAccess access = new FieldAccess(
          Types.getLocalRefType().getDeclaredFields()[0], new SimpleName(localRef));
      CastExpression newCast = new CastExpression(node.getTypeBinding(), access);
      ParenthesizedExpression newParens = ParenthesizedExpression.parenthesize(newCast);
      node.replaceWith(newParens);
    }
  }

  private LinkedListMultimap<Integer, VariableDeclarationFragment> rewriteExtraDimensions(
      Type typeNode, List<VariableDeclarationFragment> fragments) {
    // Removes extra dimensions on variable declaration fragments and creates extra field
    // declaration nodes if necessary.
    // eg. "int i1, i2[], i3[][];" becomes "int i1; int[] i2; int[][] i3".
    LinkedListMultimap<Integer, VariableDeclarationFragment> newDeclarations = null;
    int masterDimensions = -1;
    Iterator<VariableDeclarationFragment> iter = fragments.iterator();
    while (iter.hasNext()) {
      VariableDeclarationFragment frag = iter.next();
      int dimensions = frag.getExtraDimensions();
      ITypeBinding binding = frag.getVariableBinding().getType();
      if (masterDimensions == -1) {
        masterDimensions = dimensions;
        if (dimensions != 0) {
          typeNode.replaceWith(Type.newType(binding));
        }
      } else if (dimensions != masterDimensions) {
        if (newDeclarations == null) {
          newDeclarations = LinkedListMultimap.create();
        }
        VariableDeclarationFragment newFrag = new VariableDeclarationFragment(
            frag.getVariableBinding(), TreeUtil.remove(frag.getInitializer()));
        newDeclarations.put(dimensions, newFrag);
        iter.remove();
      } else {
        frag.setExtraDimensions(0);
      }
    }
    return newDeclarations;
  }

  @Override
  public void endVisit(Assignment node) {
    Assignment.Operator op = node.getOperator();
    Expression lhs = node.getLeftHandSide();
    Expression rhs = node.getRightHandSide();
    ITypeBinding lhsType = lhs.getTypeBinding();
    if (op == Assignment.Operator.PLUS_ASSIGN && Types.isJavaStringType(lhsType)) {
      // Change "str1 += str2" to "str1 = str1 + str2".
      node.setOperator(Assignment.Operator.ASSIGN);
      node.setRightHandSide(new InfixExpression(
          lhsType, InfixExpression.Operator.PLUS, lhs.copy(), rhs.copy()));
    }
  }
}
