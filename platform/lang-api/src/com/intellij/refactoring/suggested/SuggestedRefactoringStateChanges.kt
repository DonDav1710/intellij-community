// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.refactoring.suggested

import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.suggested.SuggestedRefactoringSupport.Signature

/**
 * A service transforming a sequence of declaration states into [SuggestedRefactoringState].
 */
abstract class SuggestedRefactoringStateChanges(protected val refactoringSupport: SuggestedRefactoringSupport) {
  /**
   * Extracts information from declaration and stores it in an instance of [Signature] class.
   *
   * For performance reasons, don't use any resolve in this method. More accurate information about changes can be obtained later
   * with use of [SuggestedRefactoringAvailability.refineSignaturesWithResolve].
   * @param declaration declaration in its current state.
   * Only PsiElement's that are classified as declarations by [SuggestedRefactoringSupport.isDeclaration] may be passed to this parameter.
   * @param prevState previous state of accumulated signature changes, or *null* if the user is just about to start editing the signature.
   * @return An instance of [Signature] class, representing the current state of the declaration,
   * or *null* if the declaration is in an incorrect state and no signature can be created.
   */
  abstract fun signature(declaration: PsiElement, prevState: SuggestedRefactoringState?): Signature?

  /**
   * Provides "marker ranges" for parameters in the declaration.
   *
   * Marker ranges are used to keep track of parameter identity when its name changes.
   * A good marker range must have high chances of staying the same while editing the signature (with help of a [RangeMarker], of course).
   * If the language has a fixed separator between parameter name and type such as ':'  - use it as a marker.
   * A whitespace between the type and the name is not so reliable because it may change its length or temporarily disappear.
   * Parameter type range is also a good marker because it's unlikely to change at the same time as the name changes.
   * @param declaration declaration in its current state.
   * Only PsiElement's that are classified as declarations by [SuggestedRefactoringSupport.isDeclaration] may be passed to this parameter.
   * @return a list containing a marker range for each parameter, or *null* if no marker can be provided for this parameter
   */
  abstract fun parameterMarkerRanges(declaration: PsiElement): List<TextRange?>

  open fun createInitialState(declaration: PsiElement): SuggestedRefactoringState? {
    val signature = signature(declaration, null) ?: return null
    val signatureRange = refactoringSupport.signatureRange(declaration) ?: return null
    val psiDocumentManager = PsiDocumentManager.getInstance(declaration.project)
    val file = declaration.containingFile
    val document = psiDocumentManager.getDocument(file)!!
    require(psiDocumentManager.isCommitted(document))
    return SuggestedRefactoringState(
      declaration,
      refactoringSupport,
      syntaxError = false,
      oldDeclarationText = document.getText(signatureRange),
      oldImportsText = refactoringSupport.importsRange(file)
        ?.extendWithWhitespace(document.charsSequence)
        ?.let { document.getText(it) },
      oldSignature = signature,
      newSignature = signature,
      parameterMarkers = parameterMarkers(declaration)
    )
  }

  open fun updateState(state: SuggestedRefactoringState, declaration: PsiElement): SuggestedRefactoringState {
    val newSignature = signature(declaration, state)
                       ?: return state.copy(declaration = declaration, syntaxError = true)

    val idsPresent = newSignature.parameters.map { it.id }.toSet()
    val disappearedParameters = state.disappearedParameters.entries
      .filter { (_, id) -> id !in idsPresent }
      .associate { it.key to it.value }
      .toMutableMap()
    for ((id, name) in state.newSignature.parameters) {
      if (id !in idsPresent && state.oldSignature.parameterById(id) != null) {
        disappearedParameters[name] = id // one more parameter disappeared
      }
    }

    return state.copy(
      declaration = declaration,
      newSignature = newSignature,
      parameterMarkers = parameterMarkers(declaration),
      syntaxError = refactoringSupport.hasSyntaxError(declaration),
      disappearedParameters = disappearedParameters
    )
  }

  protected fun matchParametersWithPrevState(signature: Signature,
                                             newDeclaration: PsiElement,
                                             prevState: SuggestedRefactoringState): Signature {
    // first match all parameters by names (in prevState or in the history of changes)
    val ids = signature.parameters.map { guessParameterIdByName(it, prevState) }.toMutableList()

    // now match those that we could not match by name via marker ranges
    val markerRanges = parameterMarkerRanges(newDeclaration)
    for (index in signature.parameters.indices) {
      val markerRange = markerRanges[index]
      if (ids[index] == null && markerRange != null) {
        val id = guessParameterIdByMarkers(markerRange, prevState)
        if (id != null && id !in ids) {
          ids[index] = id
        }
      }
    }

    val newParameters = signature.parameters.zip(ids) { parameter, id ->
      parameter.copy(id = id ?: Any()/*new id*/)
    }
    return Signature.create(signature.name, signature.type, newParameters, signature.additionalData)!!
  }

  protected fun guessParameterIdByName(parameter: SuggestedRefactoringSupport.Parameter, prevState: SuggestedRefactoringState): Any? {
    prevState.newSignature.parameterByName(parameter.name)
      ?.let { return it.id }

    prevState.disappearedParameters[parameter.name]
      ?.let { return it }

    return null
  }

  protected fun guessParameterIdByMarkers(markerRange: TextRange, prevState: SuggestedRefactoringState): Any? {
    val oldParamIndex = prevState.parameterMarkers.indexOfFirst { it?.range == markerRange }
    return if (oldParamIndex >= 0)
      prevState.newSignature.parameters[oldParamIndex].id
    else
      null
  }

  private fun parameterMarkers(declaration: PsiElement): List<RangeMarker?> {
    val document = PsiDocumentManager.getInstance(declaration.project).getDocument(declaration.containingFile)!!
    return parameterMarkerRanges(declaration).map { range ->
      range?.let { document.createRangeMarker(it) }
    }
  }

  /**
   * Use this implementation of [SuggestedRefactoringStateChanges], if only Rename refactoring is supported for the language.
   */
  class RenameOnly(refactoringSupport: SuggestedRefactoringSupport) : SuggestedRefactoringStateChanges(refactoringSupport) {
    override fun signature(declaration: PsiElement, prevState: SuggestedRefactoringState?): Signature? {
      val name = (declaration as? PsiNamedElement)?.name ?: return null
      return Signature.create(name, null, emptyList(), null)!!
    }

    override fun parameterMarkerRanges(declaration: PsiElement): List<TextRange?> {
      return emptyList()
    }
  }
}
