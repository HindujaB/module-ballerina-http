/*
 * Copyright (c) 2024, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.ballerina.stdlib.http.compiler.codemodifier;

import io.ballerina.compiler.syntax.tree.AbstractNodeFactory;
import io.ballerina.compiler.syntax.tree.AnnotationNode;
import io.ballerina.compiler.syntax.tree.MappingConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.MetadataNode;
import io.ballerina.compiler.syntax.tree.ModuleMemberDeclarationNode;
import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeList;
import io.ballerina.compiler.syntax.tree.ServiceDeclarationNode;
import io.ballerina.compiler.syntax.tree.SpecificFieldNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.compiler.syntax.tree.SyntaxTree;
import io.ballerina.compiler.syntax.tree.TypeDescriptorNode;
import io.ballerina.projects.Document;
import io.ballerina.projects.DocumentId;
import io.ballerina.projects.Module;
import io.ballerina.projects.ModuleId;
import io.ballerina.projects.Package;
import io.ballerina.projects.plugins.ModifierTask;
import io.ballerina.projects.plugins.SourceModifierContext;
import io.ballerina.tools.diagnostics.DiagnosticSeverity;
import io.ballerina.tools.text.TextDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.ballerina.compiler.syntax.tree.AbstractNodeFactory.createIdentifierToken;
import static io.ballerina.compiler.syntax.tree.AbstractNodeFactory.createNodeList;
import static io.ballerina.compiler.syntax.tree.AbstractNodeFactory.createSeparatedNodeList;
import static io.ballerina.compiler.syntax.tree.AbstractNodeFactory.createToken;
import static io.ballerina.compiler.syntax.tree.NodeFactory.createAnnotationNode;
import static io.ballerina.compiler.syntax.tree.NodeFactory.createMappingConstructorExpressionNode;
import static io.ballerina.compiler.syntax.tree.NodeFactory.createMetadataNode;
import static io.ballerina.compiler.syntax.tree.NodeFactory.createQualifiedNameReferenceNode;
import static io.ballerina.compiler.syntax.tree.NodeFactory.createSpecificFieldNode;
import static io.ballerina.compiler.syntax.tree.SyntaxKind.AT_TOKEN;
import static io.ballerina.compiler.syntax.tree.SyntaxKind.COLON_TOKEN;
import static io.ballerina.stdlib.http.compiler.Constants.COLON;
import static io.ballerina.stdlib.http.compiler.Constants.HTTP;
import static io.ballerina.stdlib.http.compiler.Constants.SERVICE_CONFIG_ANNOTATION;

/**
 * {@code ServiceTypeModifierTask} injects the `serviceType` field in the `http:ServiceConfig` annotation.
 *
 * @since 2.12.0
 */
public class ServiceTypeModifierTask implements ModifierTask<SourceModifierContext> {

    @Override
    public void modify(SourceModifierContext modifierContext) {
        boolean erroneousCompilation = modifierContext.compilation().diagnosticResult()
                .diagnostics().stream()
                .anyMatch(d -> DiagnosticSeverity.ERROR.equals(d.diagnosticInfo().severity()));
        if (erroneousCompilation) {
            return;
        }

        modifyServiceDeclarationNodes(modifierContext);
    }

    private void modifyServiceDeclarationNodes(SourceModifierContext modifierContext) {
        Package currentPackage = modifierContext.currentPackage();
        for (ModuleId moduleId : currentPackage.moduleIds()) {
            modifyServiceDeclarationsPerModule(modifierContext, moduleId, currentPackage);
        }
    }

    private void modifyServiceDeclarationsPerModule(SourceModifierContext modifierContext, ModuleId moduleId,
                                                    Package currentPackage) {
        Module currentModule = currentPackage.module(moduleId);
        for (DocumentId documentId : currentModule.documentIds()) {
            modifyServiceDeclarationsPerDocument(modifierContext, documentId, currentModule);
        }
    }

    private void modifyServiceDeclarationsPerDocument(SourceModifierContext modifierContext, DocumentId documentId,
                                                      Module currentModule) {
        Document currentDoc = currentModule.document(documentId);
        ModulePartNode rootNode = currentDoc.syntaxTree().rootNode();
        NodeList<ModuleMemberDeclarationNode> newMembers = updateMemberNodes(rootNode.members());
        ModulePartNode newModulePart = rootNode.modify(rootNode.imports(), newMembers, rootNode.eofToken());
        SyntaxTree updatedSyntaxTree = currentDoc.syntaxTree().modifyWith(newModulePart);
        TextDocument textDocument = updatedSyntaxTree.textDocument();
        if (currentModule.documentIds().contains(documentId)) {
            modifierContext.modifySourceFile(textDocument, documentId);
        } else {
            modifierContext.modifyTestSourceFile(textDocument, documentId);
        }
    }

    private NodeList<ModuleMemberDeclarationNode> updateMemberNodes(NodeList<ModuleMemberDeclarationNode> oldMembers) {
        List<ModuleMemberDeclarationNode> updatedMembers = new ArrayList<>();
        for (ModuleMemberDeclarationNode memberNode : oldMembers) {
            if (memberNode.kind().equals(SyntaxKind.SERVICE_DECLARATION)) {
                updatedMembers.add(updateServiceDeclarationNode((ServiceDeclarationNode) memberNode));
            } else {
                updatedMembers.add(memberNode);
            }
        }
        return AbstractNodeFactory.createNodeList(updatedMembers);
    }

    private ServiceDeclarationNode updateServiceDeclarationNode(ServiceDeclarationNode serviceDeclarationNode) {
        Optional<TypeDescriptorNode> serviceTypeDesc = serviceDeclarationNode.typeDescriptor();
        if (serviceTypeDesc.isEmpty()) {
            return serviceDeclarationNode;
        }

        Optional<MetadataNode> metadataNodeOptional = serviceDeclarationNode.metadata();
        if (metadataNodeOptional.isEmpty()) {
            return addServiceConfigAnnotation(serviceTypeDesc.get(), serviceDeclarationNode);
        }

        NodeList<AnnotationNode> annotations = metadataNodeOptional.get().annotations();
        for (AnnotationNode annotation : annotations) {
            Node annotReference = annotation.annotReference();
            String annotName = annotReference.toString();
            if (annotReference.kind() != SyntaxKind.QUALIFIED_NAME_REFERENCE) {
                continue;
            }
            String[] annotStrings = annotName.split(COLON);
            if (SERVICE_CONFIG_ANNOTATION.equals(annotStrings[annotStrings.length - 1].trim())
                    && HTTP.equals(annotStrings[0].trim())) {
                return serviceDeclarationNode;
            }
        }

        return addServiceConfigAnnotation(serviceTypeDesc.get(), serviceDeclarationNode);
    }

    private ServiceDeclarationNode addServiceConfigAnnotation(TypeDescriptorNode serviceTypeDesc,
                                                              ServiceDeclarationNode serviceDeclarationNode) {
        SpecificFieldNode serviceTypeField = createSpecificFieldNode(null, createIdentifierToken("serviceType"),
                createToken(COLON_TOKEN), serviceTypeDesc);
        MappingConstructorExpressionNode serviceConfigConstruct = createMappingConstructorExpressionNode(
                createToken(SyntaxKind.OPEN_BRACE_TOKEN), createSeparatedNodeList(serviceTypeField),
                createToken(SyntaxKind.CLOSE_BRACE_TOKEN));
        AnnotationNode serviceConfigAnnotation = createAnnotationNode(createToken(AT_TOKEN),
                createQualifiedNameReferenceNode(createIdentifierToken(HTTP), createToken(COLON_TOKEN),
                createIdentifierToken(SERVICE_CONFIG_ANNOTATION)), serviceConfigConstruct);
        Optional<MetadataNode> metadata = serviceDeclarationNode.metadata();
        MetadataNode metadataNode;
        if (metadata.isEmpty()) {
            metadataNode = createMetadataNode(null, createNodeList(serviceConfigAnnotation));
        } else {
            NodeList<AnnotationNode> annotations = metadata.get().annotations().add(serviceConfigAnnotation);
            metadataNode = metadata.get().modify().withAnnotations(annotations).apply();
        }
        return serviceDeclarationNode.modify().withMetadata(metadataNode).apply();
    }
}
