/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package org.jetbrains.kotlin.idea.completion

import com.intellij.codeInsight.lookup.*
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.asJava.KotlinLightClass
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.JetDescriptorIconProvider
import org.jetbrains.kotlin.idea.codeInsight.DescriptorToSourceUtilsIde
import org.jetbrains.kotlin.idea.completion.handlers.BaseDeclarationInsertHandler
import org.jetbrains.kotlin.idea.completion.handlers.KotlinClassifierInsertHandler
import org.jetbrains.kotlin.idea.completion.handlers.KotlinFunctionInsertHandler
import org.jetbrains.kotlin.idea.core.completion.PackageLookupObject
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.synthetic.SamAdapterExtensionFunctionDescriptor
import org.jetbrains.kotlin.synthetic.SyntheticJavaPropertyDescriptor

class BasicLookupElementFactory(
        private val project: Project,
        private val insertHandlerProvider: InsertHandlerProvider
) {
    public fun createLookupElement(
            descriptor: DeclarationDescriptor,
            qualifyNestedClasses: Boolean = false,
            includeClassTypeArguments: Boolean = true
    ): LookupElement {
        val _descriptor = if (descriptor is CallableMemberDescriptor)
            DescriptorUtils.unwrapFakeOverride(descriptor)
        else
            descriptor
        val declaration = DescriptorToSourceUtilsIde.getAnyDeclaration(project, _descriptor)
        return createLookupElement(_descriptor, declaration, qualifyNestedClasses, includeClassTypeArguments)
    }

    public fun createLookupElementForJavaClass(psiClass: PsiClass, qualifyNestedClasses: Boolean = false, includeClassTypeArguments: Boolean = true): LookupElement {
        val lookupObject = object : DeclarationLookupObjectImpl(null, psiClass) {
            override fun getIcon(flags: Int) = psiClass.getIcon(flags)
        }
        var element = LookupElementBuilder.create(lookupObject, psiClass.getName()!!)
                .withInsertHandler(KotlinClassifierInsertHandler)

        val typeParams = psiClass.getTypeParameters()
        if (includeClassTypeArguments && typeParams.isNotEmpty()) {
            element = element.appendTailText(typeParams.map { it.getName() }.joinToString(", ", "<", ">"), true)
        }

        val qualifiedName = psiClass.getQualifiedName()!!
        var containerName = qualifiedName.substringBeforeLast('.', FqName.ROOT.toString())

        if (qualifyNestedClasses) {
            val nestLevel = psiClass.parents.takeWhile { it is PsiClass }.count()
            if (nestLevel > 0) {
                var itemText = psiClass.getName()
                for (i in 1..nestLevel) {
                    val outerClassName = containerName.substringAfterLast('.')
                    element = element.withLookupString(outerClassName)
                    itemText = outerClassName + "." + itemText
                    containerName = containerName.substringBeforeLast('.', FqName.ROOT.toString())
                }
                element = element.withPresentableText(itemText!!)
            }
        }

        element = element.appendTailText(" ($containerName)", true)

        if (lookupObject.isDeprecated) {
            element = element.withStrikeoutness(true)
        }

        return element.withIconFromLookupObject()
    }

    public fun createLookupElementForPackage(name: FqName): LookupElement {
        var element = LookupElementBuilder.create(PackageLookupObject(name), name.shortName().asString())

        element = element.withInsertHandler(BaseDeclarationInsertHandler())

        if (!name.parent().isRoot()) {
            element = element.appendTailText(" (${name.asString()})", true)
        }

        return element.withIconFromLookupObject()
    }

    private fun createLookupElement(
            descriptor: DeclarationDescriptor,
            declaration: PsiElement?,
            qualifyNestedClasses: Boolean,
            includeClassTypeArguments: Boolean
    ): LookupElement {
        if (descriptor is ClassifierDescriptor &&
            declaration is PsiClass &&
            declaration !is KotlinLightClass) {
            // for java classes we create special lookup elements
            // because they must be equal to ones created in TypesCompletion
            // otherwise we may have duplicates
            return createLookupElementForJavaClass(declaration, qualifyNestedClasses, includeClassTypeArguments)
        }

        if (descriptor is PackageViewDescriptor) {
            return createLookupElementForPackage(descriptor.fqName)
        }
        if (descriptor is PackageFragmentDescriptor) {
            return createLookupElementForPackage(descriptor.fqName)
        }

        // for constructor use name and icon of containing class
        val nameAndIconDescriptor: DeclarationDescriptor
        val iconDeclaration: PsiElement?
        if (descriptor is ConstructorDescriptor) {
            nameAndIconDescriptor = descriptor.getContainingDeclaration()
            iconDeclaration = DescriptorToSourceUtilsIde.getAnyDeclaration(project, nameAndIconDescriptor)
        }
        else {
            nameAndIconDescriptor = descriptor
            iconDeclaration = declaration
        }
        val name = nameAndIconDescriptor.getName().asString()

        val lookupObject = object : DeclarationLookupObjectImpl(descriptor, declaration) {
            override fun getIcon(flags: Int) = JetDescriptorIconProvider.getIcon(nameAndIconDescriptor, iconDeclaration, flags)
        }
        var element = LookupElementBuilder.create(lookupObject, name)

        val insertHandler = insertHandlerProvider.insertHandler(descriptor)
        element = element.withInsertHandler(insertHandler)

        when (descriptor) {
            is FunctionDescriptor -> {
                val returnType = descriptor.getReturnType()
                element = element.withTypeText(if (returnType != null) DescriptorRenderer.SHORT_NAMES_IN_TYPES.renderType(returnType) else "")

                val insertsLambda = (insertHandler as KotlinFunctionInsertHandler).lambdaInfo != null
                if (insertsLambda) {
                    element = element.appendTailText(" {...} ", false)
                }

                element = element.appendTailText(DescriptorRenderer.SHORT_NAMES_IN_TYPES.renderFunctionParameters(descriptor), insertsLambda)
            }

            is VariableDescriptor -> {
                element = element.withTypeText(DescriptorRenderer.SHORT_NAMES_IN_TYPES.renderType(descriptor.getType()))
            }

            is ClassDescriptor -> {
                val typeParams = descriptor.getTypeConstructor().getParameters()
                if (includeClassTypeArguments && typeParams.isNotEmpty()) {
                    element = element.appendTailText(typeParams.map { it.getName().asString() }.joinToString(", ", "<", ">"), true)
                }

                var container = descriptor.getContainingDeclaration()

                if (qualifyNestedClasses) {
                    element = element.withPresentableText(DescriptorRenderer.SHORT_NAMES_IN_TYPES.renderClassifierName(descriptor))

                    while (container is ClassDescriptor) {
                        element = element.withLookupString(container.name.asString())
                        container = container.getContainingDeclaration()
                    }
                }

                if (container is PackageFragmentDescriptor || container is ClassDescriptor) {
                    element = element.appendTailText(" (" + DescriptorUtils.getFqName(container) + ")", true)
                }
            }

            else -> {
                element = element.withTypeText(DescriptorRenderer.SHORT_NAMES_IN_TYPES.render(descriptor))
            }
        }

        if (descriptor is CallableDescriptor) {
            appendContainerAndReceiverInformation(descriptor) { element = element.appendTailText(it, true) }
        }

        if (descriptor is PropertyDescriptor) {
            val getterName = JvmAbi.getterName(name)
            if (getterName != name) {
                element = element.withLookupString(getterName)
            }
            if (descriptor.isVar) {
                element = element.withLookupString(JvmAbi.setterName(name))
            }
        }

        if (lookupObject.isDeprecated) {
            element = element.withStrikeoutness(true)
        }

        if (insertHandler is KotlinFunctionInsertHandler && insertHandler.lambdaInfo != null) {
            element.putUserData(KotlinCompletionCharFilter.ACCEPT_OPENING_BRACE, Unit)
        }

        return element.withIconFromLookupObject()
    }

    public fun appendContainerAndReceiverInformation(descriptor: CallableDescriptor, appendTailText: (String) -> Unit) {
        val extensionReceiver = descriptor.original.extensionReceiverParameter
        when {
            descriptor is SyntheticJavaPropertyDescriptor -> {
                var from = descriptor.getMethod.getName().asString() + "()"
                descriptor.setMethod?.let { from += "/" + it.getName().asString() + "()" }
                appendTailText(" (from $from)")
            }

        // no need to show them as extensions
            descriptor is SamAdapterExtensionFunctionDescriptor -> {
            }

            extensionReceiver != null -> {
                val receiverPresentation = DescriptorRenderer.SHORT_NAMES_IN_TYPES.renderType(extensionReceiver.type)
                appendTailText(" for $receiverPresentation")

                val container = descriptor.getContainingDeclaration()
                val containerPresentation = if (container is ClassDescriptor)
                    DescriptorUtils.getFqNameFromTopLevelClass(container).toString()
                else if (container is PackageFragmentDescriptor)
                    container.fqName.toString()
                else
                    null
                if (containerPresentation != null) {
                    appendTailText(" in $containerPresentation")
                }
            }

            else -> {
                val container = descriptor.getContainingDeclaration()
                if (container is PackageFragmentDescriptor) {
                    // we show container only for global functions and properties
                    //TODO: it would be probably better to show it also for static declarations which are not from the current class (imported)
                    appendTailText(" (${container.fqName})")
                }
            }
        }
    }

    private fun LookupElement.withIconFromLookupObject(): LookupElement {
        // add icon in renderElement only to pass presentation.isReal()
        return object : LookupElementDecorator<LookupElement>(this) {
            override fun renderElement(presentation: LookupElementPresentation) {
                super.renderElement(presentation)
                presentation.setIcon(DefaultLookupItemRenderer.getRawIcon(this@withIconFromLookupObject, presentation.isReal()))
            }
        }
    }
}