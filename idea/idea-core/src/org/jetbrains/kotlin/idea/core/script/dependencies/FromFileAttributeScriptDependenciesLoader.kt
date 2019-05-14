/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.core.script.dependencies

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.idea.core.script.scriptDependencies
import org.jetbrains.kotlin.psi.KtFile

class FromFileAttributeScriptDependenciesLoader(project: Project) : ScriptDependenciesLoader(project) {

    override fun isApplicable(file: KtFile): Boolean {
        return file.virtualFile.scriptDependencies != null
    }

    override fun loadDependencies(file: KtFile) {
        val deserializedDependencies = file.virtualFile.scriptDependencies ?: return
        saveToCache(file, deserializedDependencies)
    }

    override fun shouldShowNotification(): Boolean = false
}