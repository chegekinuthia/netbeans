/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.netbeans.modules.php.editor.verification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.netbeans.api.annotations.common.NullAllowed;
import org.netbeans.modules.csl.api.Hint;
import org.netbeans.modules.csl.api.OffsetRange;
import org.netbeans.modules.csl.api.Rule;
import org.netbeans.modules.csl.spi.support.CancelSupport;
import org.netbeans.modules.php.api.PhpVersion;
import org.netbeans.modules.php.editor.CodeUtils;
import org.netbeans.modules.php.editor.api.QualifiedName;
import org.netbeans.modules.php.editor.model.Model;
import org.netbeans.modules.php.editor.model.ModelUtils;
import org.netbeans.modules.php.editor.model.NamespaceScope;
import org.netbeans.modules.php.editor.model.impl.Type;
import org.netbeans.modules.php.editor.model.impl.VariousUtils;
import org.netbeans.modules.php.editor.parser.PHPParseResult;
import org.netbeans.modules.php.editor.parser.astnodes.ASTNode;
import org.netbeans.modules.php.editor.parser.astnodes.ArrowFunctionDeclaration;
import org.netbeans.modules.php.editor.parser.astnodes.Expression;
import org.netbeans.modules.php.editor.parser.astnodes.FieldsDeclaration;
import org.netbeans.modules.php.editor.parser.astnodes.FormalParameter;
import org.netbeans.modules.php.editor.parser.astnodes.FunctionDeclaration;
import org.netbeans.modules.php.editor.parser.astnodes.Identifier;
import org.netbeans.modules.php.editor.parser.astnodes.LambdaFunctionDeclaration;
import org.netbeans.modules.php.editor.parser.astnodes.MethodDeclaration;
import org.netbeans.modules.php.editor.parser.astnodes.NamespaceName;
import org.netbeans.modules.php.editor.parser.astnodes.NullableType;
import org.netbeans.modules.php.editor.parser.astnodes.UnionType;
import org.netbeans.modules.php.editor.parser.astnodes.visitors.DefaultVisitor;
import org.openide.filesystems.FileObject;
import org.openide.util.NbBundle;
import org.openide.util.Pair;

/**
 * Handle unusable types as errors here to avoid complicating the grammar(cup
 * file).
 *
 */
public class UnusableTypesHintError extends HintErrorRule {

    private static final String TRAVERSABLE_TYPE = "Traversable"; // NOI18N
    private static final List<String> VALID_TYPES_WITH_OBJECT_TYPE = Arrays.asList(
            Type.ARRAY, Type.BOOL, Type.CALLABLE, Type.FALSE, Type.FLOAT,
            Type.INT, Type.ITERABLE, Type.NULL, Type.STRING, Type.VOID
    );

    @Override
    @NbBundle.Messages("UnusableTypesHintError.displayName=Unusable types.")
    public String getDisplayName() {
        return Bundle.UnusableTypesHintError_displayName();
    }

    @Override
    public void invoke(PHPRuleContext context, List<Hint> result) {
        PHPParseResult phpParseResult = (PHPParseResult) context.parserResult;
        if (phpParseResult.getProgram() != null) {
            FileObject fileObject = phpParseResult.getSnapshot().getSource().getFileObject();
            if (fileObject != null) {
                CheckVisitor checkVisitor = new CheckVisitor(this, fileObject, phpParseResult.getModel(), CodeUtils.getPhpVersion(fileObject));
                phpParseResult.getProgram().accept(checkVisitor);
                result.addAll(checkVisitor.getHints());
            }
        }
    }

    //~ Inner classes
    private static final class CheckVisitor extends DefaultVisitor {

        private final UnusableTypesHintError rule;
        private final List<Hint> hints = new ArrayList<>();
        private final FileObject fileObject;
        private final Model model;
        private final PhpVersion phpVersion;
        private boolean isInMethod;
        private boolean isInLambdaFunction;
        private boolean isInMethodBody;

        private CheckVisitor(UnusableTypesHintError rule, FileObject fileObject, Model model, PhpVersion phpVersion) {
            assert fileObject != null;
            this.rule = rule;
            this.fileObject = fileObject;
            this.model = model;
            this.phpVersion = phpVersion;
        }

        private List<Hint> getHints() {
            return Collections.unmodifiableList(hints);
        }

        @Override
        public void visit(FieldsDeclaration node) {
            if (CancelSupport.getDefault().isCancelled()) {
                return;
            }
            Expression fieldType = node.getFieldType();
            if (fieldType != null) {
                checkFieldType(fieldType, false);
            }
            super.visit(node);
        }

        @Override
        public void visit(FormalParameter node) {
            if (CancelSupport.getDefault().isCancelled()) {
                return;
            }
            Expression parameterType = node.getParameterType();
            if (parameterType != null) {
                checkParameterType(parameterType, false);
            }
            super.visit(node);
        }

        @Override
        public void visit(ArrowFunctionDeclaration node) {
            if (CancelSupport.getDefault().isCancelled()) {
                return;
            }
            // PHP 7.4
            // fn(): void => $y;
            // arrow function returns something, so we would get the following
            // "Fatal error: A void function must not return a value"
            Expression returnType = node.getReturnType();
            if (returnType != null) {
                checkArrowFunctionReturnType(returnType, false);
            }
            super.visit(node);
        }

        @Override
        public void visit(LambdaFunctionDeclaration node) {
            if (CancelSupport.getDefault().isCancelled()) {
                return;
            }
            isInLambdaFunction = true;
            checkReturnType(node.getReturnType(), false);
            super.visit(node);
            isInLambdaFunction = false;
        }

        @Override
        public void visit(FunctionDeclaration node) {
            if (CancelSupport.getDefault().isCancelled()) {
                return;
            }
            checkReturnType(node.getReturnType(), false);
            super.visit(node);
        }

        @Override
        public void visit(MethodDeclaration node) {
            if (CancelSupport.getDefault().isCancelled()) {
                return;
            }
            for (FormalParameter parameter : node.getFunction().getFormalParameters()) {
                if (CancelSupport.getDefault().isCancelled()) {
                    return;
                }
                FieldsDeclaration fieldsDeclaration = FieldsDeclaration.create(parameter);
                if (fieldsDeclaration != null) {
                    scan(fieldsDeclaration);
                }
            }
            isInMethod = true;
            FunctionDeclaration function = node.getFunction();
            scan(function.getFunctionName());
            scan(function.getFormalParameters());
            checkReturnType(function.getReturnType(), false);
            scan(function.getReturnType());
            isInMethodBody = true;
            scan(function.getBody());
            isInMethodBody = false;
            isInMethod = false;
        }

        @Override
        public void visit(UnionType node) {
            if (CancelSupport.getDefault().isCancelled()) {
                return;
            }
            checkUnionType(node);
            super.visit(node);
        }

        @Override
        public void visit(NullableType nullableType) {
            if (CancelSupport.getDefault().isCancelled()) {
                return;
            }
            Expression type = nullableType.getType();
            if (phpVersion.hasMixedType() && type instanceof NamespaceName && isMixedType((NamespaceName) type)) {
                createError(type, Type.MIXED, UnusableType.Context.Nullable);
            }
            super.visit(nullableType);
        }

        private void checkFieldType(@NullAllowed Expression fieldType, boolean isInUnionType) {
            // unusable types: void and callable PHP 7.4
            Expression type = fieldType;
            if (fieldType instanceof NullableType) {
                type = ((NullableType) fieldType).getType();
            }
            if (type == null) {
                return;
            }
            if (type instanceof Identifier) {
                if (isCallableType((Identifier) type)) {
                    createError(type, Type.CALLABLE, UnusableType.Context.Property);
                }
            } else if (type instanceof NamespaceName) {
                if (isVoidType((NamespaceName) type)) {
                    createError(type, Type.VOID, UnusableType.Context.Property);
                } else if (isNeverType((NamespaceName) type)) {
                    createError(type, Type.NEVER, UnusableType.Context.Property);
                }
                if (!isInUnionType) {
                    checkFalseAndNullTypes((NamespaceName) type);
                }
            } else if (type instanceof UnionType) {
                ((UnionType) type).getTypes().forEach(unionType -> checkFieldType(unionType, true));
            }
        }

        private void checkParameterType(Expression parameterType, boolean isInUnionType) {
            // unusable type: void, never
            if (parameterType instanceof NamespaceName) {
                if (isVoidType((NamespaceName) parameterType)) {
                    createError(parameterType, Type.VOID, UnusableType.Context.Parameter);
                } else if (isNeverType((NamespaceName) parameterType)) {
                    createError(parameterType, Type.NEVER, UnusableType.Context.Parameter);
                }
                if (!isInUnionType) {
                    checkFalseAndNullTypes((NamespaceName) parameterType);
                }
            } else if (parameterType instanceof UnionType) {
                ((UnionType) parameterType).getTypes().forEach(type -> checkParameterType(type, true));
            }
        }

        private void checkArrowFunctionReturnType(Expression returnType, boolean isInUnionType) {
            // unusable type: void, never
            if (returnType instanceof NamespaceName) {
                if (isVoidType((NamespaceName) returnType)) {
                    createError(returnType, Type.VOID, UnusableType.Context.ArrowFunctionReturn);
                } else if (isNeverType((NamespaceName) returnType)) {
                    createError(returnType, Type.NEVER, UnusableType.Context.ArrowFunctionReturn);
                }
                if (!isInUnionType) {
                    checkFalseAndNullTypes((NamespaceName) returnType);
                }
            } else if (returnType instanceof UnionType) {
                ((UnionType) returnType).getTypes().forEach(type -> checkArrowFunctionReturnType(type, true));
            }
        }

        private void checkReturnType(@NullAllowed Expression returnType, boolean isInUnionType) {
            if (returnType == null) {
                return;
            }
            Expression type = returnType;
            if (returnType instanceof NullableType) {
                type = ((NullableType) returnType).getType();
            }

            if (type instanceof NamespaceName) {
                if (!isInUnionType) {
                    checkFalseAndNullTypes((NamespaceName) type);
                } else {
                    // "void" can't be part of a union type
                    if (isVoidType((NamespaceName) type)) {
                        createError(type, Type.VOID, UnusableType.Context.Union);
                    } else if (isNeverType((NamespaceName) type)) {
                        createError(type, Type.NEVER, UnusableType.Context.Union);
                    }
                }
            } else if (type instanceof UnionType) {
                ((UnionType) type).getTypes().forEach(unionType -> checkReturnType(unionType, true));
            } else if (type instanceof Identifier) {
                // method, lambda function, and arrow function can use static return type
                // e.g. $closure = function(): static {return new static};, $af = fn(): static => new static; no errors
                // function cannot use static return type e.g. function a(): static {return new static;} error
                if (((Identifier) type).getName().equals(Type.STATIC)) {
                    if ((!isInMethod && !isInLambdaFunction)
                            || (!isInLambdaFunction && isInMethodBody)) { // nested function
                        createError(type, Type.STATIC, UnusableType.Context.Return);
                    }
                }
            }
        }

        private void checkFalseAndNullTypes(NamespaceName type) {
            if (isFalseType(type)) {
                createError(type, Type.FALSE, UnusableType.Context.Standalone);
            } else if (isNullType(type)) {
                createError(type, Type.NULL, UnusableType.Context.Standalone);
            }
        }

        private void checkUnionType(UnionType unionType) {
            checkDuplicateType(unionType);
            checkRedundantTypeCombination(unionType);
        }

        private void checkDuplicateType(UnionType unionType) {
            HashSet<String> types = new HashSet<>();
            for (Expression type : unionType.getTypes()) {
                QualifiedName qualifiedName = QualifiedName.create(type);
                assert qualifiedName != null;
                String name = qualifiedName.toString().toLowerCase(Locale.ENGLISH);
                if (Type.FALSE.equals(name)) {
                    // check bool|false
                    name = Type.BOOL;
                }
                if (types.contains(name)) {
                    createDuplicateTypeError(type, qualifiedName.toString());
                    return;
                }
                types.add(name);
            }
        }

        private void checkRedundantTypeCombination(UnionType unionType) {
            checkRedundantTypeCombinationWithObject(unionType);
            checkRedundantMixedType(unionType);
            checkRedundantTypeCombinationWithIterable(unionType);
        }

        private void checkRedundantTypeCombinationWithObject(UnionType unionType) {
            boolean hasObjectType = false;
            for (Expression type : unionType.getTypes()) {
                if (type instanceof NamespaceName && isObjectType((NamespaceName) type)) {
                    hasObjectType = true;
                }
            }
            // e.g. object|self, object|parent, object|static object|\Foo\Bar
            if (hasObjectType) {
                for (Expression type : unionType.getTypes()) {
                    if (type instanceof NamespaceName && !isObjectType((NamespaceName) type)) {
                        String typeName = CodeUtils.extractUnqualifiedName((NamespaceName) type);
                        if (!VALID_TYPES_WITH_OBJECT_TYPE.contains(typeName)) {
                            createRedundantTypeCombinationError(type, unionType, Type.OBJECT, CodeUtils.extractQualifiedName((NamespaceName) type));
                        }
                    } else if (type instanceof Identifier) {
                        if (!VALID_TYPES_WITH_OBJECT_TYPE.contains(((Identifier)type).getName())) {
                            createRedundantTypeCombinationError(type, unionType, Type.OBJECT, ((Identifier)type).getName());
                        }
                    }
                }
            }
        }

        private void checkRedundantMixedType(UnionType unionType) {
            // mixed can only be used as a standalone type
            // e.g. mixed|null, mixed|object, mixed|void, and so on are errors
            for (Expression type : unionType.getTypes()) {
                if (type instanceof NamespaceName && isMixedType((NamespaceName) type)) {
                    createError(type, Type.MIXED, UnusableType.Context.Union);
                    break;
                }
            }
        }

        private void checkRedundantTypeCombinationWithIterable(UnionType unionType) {
            // Iterable: https://www.php.net/manual/en/language.types.iterable.php
            // Iterable accepts any array or object implementing the Traversable interface.
            boolean hasIterable = false;
            boolean hasTraversable = false;
            boolean hasArray = false;
            for (Expression type : unionType.getTypes()) {
                if (type instanceof NamespaceName && isIterableType((NamespaceName) type)) {
                    hasIterable = true;
                    break;
                }
            }

            if (hasIterable) {
                for (Expression type : unionType.getTypes()) {
                    if (type instanceof Identifier) {
                        if (isArrayType((Identifier) type)) {
                            hasArray = true;
                        }
                    } else if (type instanceof NamespaceName) {
                        if (isTraversableType((NamespaceName) type)) {
                            NamespaceName name = (NamespaceName) type;
                            QualifiedName qualifiedName = QualifiedName.create(name);
                            NamespaceScope namespaceScope = ModelUtils.getNamespaceScope(model.getFileScope(), type.getStartOffset());
                            QualifiedName fullyQualifiedName = VariousUtils.getFullyQualifiedName(qualifiedName, type.getStartOffset(), namespaceScope);
                            if (("\\" + TRAVERSABLE_TYPE).equals(fullyQualifiedName.toString())) { // NOI18N
                                hasTraversable = true;
                            }
                        }
                    }
                }
            }
            if (hasIterable && hasArray) {
                createIterableRedundantTypeCombinationError(unionType, IterableRedundantTypeCombination.RedundantType.Array);
            }
            if (hasIterable && hasTraversable) {
                createIterableRedundantTypeCombinationError(unionType, IterableRedundantTypeCombination.RedundantType.Traversable);
            }
        }

        private void createError(ASTNode node, String type, UnusableType.Context context) {
            createError(node.getStartOffset(), node.getEndOffset(), type, context);
        }

        private void createError(int startOffset, int endOffset, String type, UnusableType.Context context) {
            hints.add(new UnusableType(rule, fileObject, startOffset, endOffset, type, context));
        }

        private void createDuplicateTypeError(ASTNode node, String type) {
            hints.add(new DuplicateType(rule, fileObject, node.getStartOffset(), node.getEndOffset(), type));
        }

        private void createRedundantTypeCombinationError(ASTNode node, UnionType unionType, String type, String redundantType) {
            hints.add(new RedundantTypeCombination(rule, fileObject, node.getStartOffset(), node.getEndOffset(), unionType, Pair.of(type, redundantType)));
        }

        private void createIterableRedundantTypeCombinationError(UnionType unionType, IterableRedundantTypeCombination.RedundantType redundantType) {
            hints.add(new IterableRedundantTypeCombination(rule, fileObject, unionType.getStartOffset(), unionType.getEndOffset(), unionType, redundantType));
        }

        private static boolean isArrayType(Identifier identifier) {
            return !identifier.isKeyword()
                    && Type.ARRAY.equals(identifier.getName().toLowerCase(Locale.ENGLISH));
        }

        private static boolean isCallableType(Identifier identifier) {
            return !identifier.isKeyword()
                    && Type.CALLABLE.equals(identifier.getName().toLowerCase(Locale.ENGLISH));
        }

        private static boolean isVoidType(NamespaceName namespaceName) {
            return Type.VOID.equals(CodeUtils.extractUnqualifiedName(namespaceName));
        }

        private static boolean isNeverType(NamespaceName namespaceName) {
            return Type.NEVER.equals(CodeUtils.extractUnqualifiedName(namespaceName));
        }

        private static boolean isFalseType(NamespaceName namespaceName) {
            return Type.FALSE.equals(CodeUtils.extractUnqualifiedName(namespaceName));
        }

        private static boolean isNullType(NamespaceName namespaceName) {
            return Type.NULL.equals(CodeUtils.extractUnqualifiedName(namespaceName));
        }

        private static boolean isObjectType(NamespaceName namespaceName) {
            return Type.OBJECT.equals(CodeUtils.extractUnqualifiedName(namespaceName));
        }

        private static boolean isMixedType(NamespaceName namespaceName) {
            return Type.MIXED.equals(CodeUtils.extractUnqualifiedName(namespaceName));
        }

        private static boolean isIterableType(NamespaceName namespaceName) {
            return Type.ITERABLE.equals(CodeUtils.extractUnqualifiedName(namespaceName));
        }

        private static boolean isTraversableType(NamespaceName namespaceName) {
            return TRAVERSABLE_TYPE.equals(CodeUtils.extractUnqualifiedName(namespaceName));
        }

    }

    @NbBundle.Messages({
        "UnusableType.Context.parameter=a parameter",
        "UnusableType.Context.return=a return",
        "UnusableType.Context.arrowFunctionReturn=an arrow function return",
        "UnusableType.Context.property=a property",
        "UnusableType.Context.standalone=a standalone",
        "UnusableType.Context.union=a union",
        "UnusableType.Context.nullable=a nullable",
        "# {0} - type",
        "# {1} - context",
        "UnusableType.description=\"{0}\" cannot be used as {1} type.",
    })
    private static final class UnusableType extends Hint {

        enum Context {
            Parameter(Bundle.UnusableType_Context_parameter()),
            Return(Bundle.UnusableType_Context_return()),
            ArrowFunctionReturn(Bundle.UnusableType_Context_arrowFunctionReturn()),
            Property(Bundle.UnusableType_Context_property()),
            Standalone(Bundle.UnusableType_Context_standalone()),
            Union(Bundle.UnusableType_Context_union()),
            Nullable(Bundle.UnusableType_Context_nullable()),
            ;
            private final String context;

            private Context(String context) {
                this.context = context;
            }

            public String getContext() {
                return context;
            }
        }

        private UnusableType(Rule rule, FileObject fileObject, int startOffset, int endOffset, String type, Context context) {
            super(rule, Bundle.UnusableType_description(type, context.getContext()), fileObject, new OffsetRange(startOffset, endOffset), Collections.emptyList(), 500);
        }
    }

    @NbBundle.Messages({
        "# {0} - type",
        "DuplicateType.description=Type \"{0}\" is duplicated.",
    })
    private static final class DuplicateType extends Hint {

        private DuplicateType(Rule rule, FileObject fileObject, int startOffset, int endOffset, String type) {
            super(rule, Bundle.DuplicateType_description(type), fileObject, new OffsetRange(startOffset, endOffset), Collections.emptyList(), 500);
        }
    }

    @NbBundle.Messages({
        "# {0} - union type",
        "# {1} - type",
        "# {2} - redundant type",
        "RedundantTypeCombination.description=Redundant combination: \"{0}\" contains both \"{1}\" and \"{2}\".",
    })
    private static class RedundantTypeCombination extends Hint {

        public RedundantTypeCombination(Rule rule, FileObject fileObject, int startOffset, int endOffset, UnionType unionType, Pair<String, String> types) {
            super(rule, Bundle.RedundantTypeCombination_description(VariousUtils.getUnionType(unionType), types.first(), types.second()), fileObject, new OffsetRange(startOffset, endOffset), Collections.emptyList(), 500);
        }

    }

    /**
     * Iterable accepts any array or object implementing the Traversable
     * interface.
     *
     * @see https://www.php.net/manual/en/language.types.iterable.php
     */
    private static final class IterableRedundantTypeCombination extends RedundantTypeCombination {

        enum RedundantType {
            Array(Type.ARRAY),
            Traversable(TRAVERSABLE_TYPE);

            private final String type;

            private RedundantType(String type) {
                this.type = type;
            }

            public String getType() {
                return type;
            }
        }

        public IterableRedundantTypeCombination(Rule rule, FileObject fileObject, int startOffset, int endOffset, UnionType unionType, RedundantType redundantType) {
            super(rule, fileObject, startOffset, endOffset, unionType, Pair.of(Type.ITERABLE, redundantType.getType()));
        }

    }
}
