/*
 * Copyright 2000-2010 JetBrains s.r.o.
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
package com.intellij.psi.impl.smartPointers;

import com.intellij.injected.editor.DocumentWindow;
import com.intellij.injected.editor.VirtualFileWindow;
import com.intellij.lang.Language;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.*;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.FreeThreadedFileViewProvider;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/**
* User: cdr
*/
class InjectedSelfElementInfo extends SelfElementInfo {
  private final SmartPsiFileRange myInjectedFileRangeInHostFile;
  private final Class<? extends PsiElement> anchorClass;
  private final Language anchorLanguage;

  InjectedSelfElementInfo(@NotNull Project project,
                          @NotNull PsiElement injectedElement,
                          @NotNull TextRange injectedRange,
                          @NotNull PsiFile containingFile,
                          @NotNull PsiElement hostContext) {
    super(project, hostContext);
    SmartPointerManager smartPointerManager = SmartPointerManager.getInstance(project);
    ProperTextRange.assertProperRange(injectedRange);
    TextRange hostRange = InjectedLanguageManager.getInstance(project).injectedToHost(injectedElement, injectedRange);
    PsiFile hostFile = hostContext.getContainingFile();
    assert !(hostFile.getViewProvider() instanceof FreeThreadedFileViewProvider) : "hostContext parameter must not be and injected element: "+hostContext;
    myInjectedFileRangeInHostFile = smartPointerManager.createSmartPsiFileRangePointer(hostFile, hostRange);
    anchorClass = containingFile.findElementAt(injectedRange.getStartOffset()).getClass();
    anchorLanguage = containingFile.getLanguage();
    assert containingFile.getViewProvider() instanceof FreeThreadedFileViewProvider : "element parameter must be an injected element: "+injectedElement+"; "+containingFile;
  }

  @Override
  public VirtualFile getVirtualFile() {
    PsiElement element = restoreElement();
    if (element == null) return null;
    return element.getContainingFile().getVirtualFile();
  }

  @Override
  public Segment getRange() {
    return getInjectedRange();
  }

  @Override
  public PsiElement restoreElement() {
    if (!mySyncMarkerIsValid) return null;
    PsiFile file = restoreFileFromVirtual(myVirtualFile, myProject, myLanguage);
    if (file == null || !file.isValid()) return null;

    PsiElement hostContext = restoreFromFile(file);
    if (hostContext == null) return null;

    Segment segment = myInjectedFileRangeInHostFile.getRange();
    if (segment == null) return null;
    final TextRange rangeInHostFile = TextRange.create(segment);
    final Ref<PsiElement> result = new Ref<PsiElement>();

    final InjectedLanguageManager manager = InjectedLanguageManager.getInstance(getProject());
    PsiFile hostFile = hostContext.getContainingFile();
    if (hostFile == null) return null;

    PsiLanguageInjectionHost.InjectedPsiVisitor visitor = new PsiLanguageInjectionHost.InjectedPsiVisitor() {
      @Override
      public void visit(@NotNull PsiFile injectedPsi, @NotNull List<PsiLanguageInjectionHost.Shred> places) {
        if (result.get() != null) return;
        TextRange hostRange = manager.injectedToHost(injectedPsi, new TextRange(0, injectedPsi.getTextLength()));
        Document document = PsiDocumentManager.getInstance(getProject()).getDocument(injectedPsi);
        if (hostRange.contains(rangeInHostFile) && document instanceof DocumentWindow) {
          int start = ((DocumentWindow)document).hostToInjected(rangeInHostFile.getStartOffset());
          int end = ((DocumentWindow)document).hostToInjected(rangeInHostFile.getEndOffset());
          PsiElement element = findElementInside(injectedPsi, start, end, anchorClass, anchorLanguage);
          result.set(element);
        }
      }
    };

    PsiDocumentManager documentManager = PsiDocumentManager.getInstance(getProject());
    Document document = documentManager.getDocument(hostFile);
    if (document != null && documentManager.isUncommited(document)) {
      for (DocumentWindow documentWindow : InjectedLanguageManager.getInstance(getProject()).getCachedInjectedDocuments(hostFile)) {
        PsiFile injected = documentManager.getPsiFile(documentWindow);
        if (injected != null) {
          visitor.visit(injected, Collections.<PsiLanguageInjectionHost.Shred>emptyList());
        }
      }
    }
    else {
      List<Pair<PsiElement,TextRange>> injected = InjectedLanguageManager.getInstance(getProject()).getInjectedPsiFiles(hostContext);
      if (injected != null) {
        for (Pair<PsiElement, TextRange> pair : injected) {
          PsiFile injectedFile = pair.first.getContainingFile();
          visitor.visit(injectedFile, ContainerUtil.<PsiLanguageInjectionHost.Shred>emptyList());
        }
      }
    }

    return result.get();
  }

  @Override
  public boolean pointsToTheSameElementAs(@NotNull SmartPointerElementInfo other) {
    if (getClass() != other.getClass()) return false;
    if (!super.pointsToTheSameElementAs(other)) return false;
    SmartPointerElementInfo myElementInfo = ((SmartPsiElementPointerImpl)myInjectedFileRangeInHostFile).getElementInfo();
    SmartPointerElementInfo oElementInfo = ((SmartPsiElementPointerImpl)((InjectedSelfElementInfo)other).myInjectedFileRangeInHostFile).getElementInfo();
    return myElementInfo.pointsToTheSameElementAs(oElementInfo);
  }

  @Override
  public PsiFile restoreFile() {
    PsiElement element = restoreElement();
    return element == null ? null : element.getContainingFile();
  }

  public ProperTextRange getInjectedRange() {
    Segment hostElementRange = myInjectedFileRangeInHostFile.getRange();
    if (hostElementRange == null) return null;

    PsiElement injectedElement = restoreElement();
    if (injectedElement == null) return null;
    PsiFile injectedFile = injectedElement.getContainingFile();
    if (injectedFile == null) return null;
    VirtualFile virtualFile = injectedFile.getVirtualFile();
    DocumentWindow documentWindow = virtualFile instanceof VirtualFileWindow ?  ((VirtualFileWindow)virtualFile).getDocumentWindow() : null;
    if (documentWindow==null) return null;
    int start = documentWindow.hostToInjected(hostElementRange.getStartOffset());
    int end = documentWindow.hostToInjected(hostElementRange.getEndOffset());
    return ProperTextRange.create(start, end);
  }

  @Override
  public void cleanup() {
    super.cleanup();
    SmartPointerManager.getInstance(getProject()).removePointer(myInjectedFileRangeInHostFile);
  }
}
