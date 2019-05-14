/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

package org.jetbrains.kotlin.idea.core.script

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.Alarm
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.core.script.dependencies.AsyncScriptDependenciesLoader
import org.jetbrains.kotlin.idea.core.script.dependencies.FromFileAttributeScriptDependenciesLoader
import org.jetbrains.kotlin.idea.core.script.dependencies.OutsiderFileDependenciesLoader
import org.jetbrains.kotlin.idea.core.script.dependencies.SyncScriptDependenciesLoader
import org.jetbrains.kotlin.idea.util.ProjectRootsUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.NotNullableUserDataProperty
import org.jetbrains.kotlin.scripting.definitions.KotlinScriptDefinition
import org.jetbrains.kotlin.scripting.definitions.scriptDefinition
import org.jetbrains.kotlin.scripting.resolve.LegacyResolverWrapper
import kotlin.script.experimental.dependencies.AsyncDependenciesResolver
import kotlin.script.experimental.dependencies.ScriptDependencies

class ScriptDependenciesUpdater(
    private val project: Project,
    private val cache: ScriptDependenciesCache
) {
    private val scriptsQueue = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)
    private val scriptChangesListenerDelay = 1400

    private val loaders = arrayListOf(
        FromFileAttributeScriptDependenciesLoader(project),
        OutsiderFileDependenciesLoader(project),
        AsyncScriptDependenciesLoader(project),
        SyncScriptDependenciesLoader(project)
    )

    init {
        listenForChangesInScripts()
    }

    fun getCurrentDependencies(file: KtFile): ScriptDependencies {
        cache[file]?.let { return it }

        updateDependencies(file)
        makeRootsChangeIfNeeded()

        return cache[file] ?: ScriptDependencies.Empty
    }

    fun updateDependenciesIfNeeded(virtualFiles: List<VirtualFile>): Boolean {
        if (!ScriptDefinitionsManager.getInstance(project).isReady()) return false

        var wasDependenciesUpdateStarted = false
        for (virtualFile in virtualFiles) {
            val file = getKtFileToUpdateDependencies(virtualFile) ?: continue
            if (!areDependenciesCached(file)) {
                wasDependenciesUpdateStarted = true
                updateDependencies(file)
            }
        }

        if (wasDependenciesUpdateStarted) {
            makeRootsChangeIfNeeded()
        }

        return wasDependenciesUpdateStarted
    }

    private fun updateDependencies(file: KtFile) {
        loaders.filter { it.isApplicable(file) }.forEach { it.updateDependencies(file) }
    }

    private fun makeRootsChangeIfNeeded() {
        loaders.firstOrNull {
            it.notifyRootsChanged()
        }
    }

    private fun listenForChangesInScripts() {
        project.messageBus.connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                runScriptDependenciesUpdateIfNeeded(file)
            }

            override fun selectionChanged(event: FileEditorManagerEvent) {
                event.newFile?.let { runScriptDependenciesUpdateIfNeeded(it) }
            }

            private fun runScriptDependenciesUpdateIfNeeded(virtualFile: VirtualFile) {
                val file = getKtFileToUpdateDependencies(virtualFile) ?: return

                updateDependencies(file)
                makeRootsChangeIfNeeded()
            }
        })

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {

                val document = event.document
                val virtualFile = FileDocumentManager.getInstance().getFile(document)?.takeIf { it.isInLocalFileSystem } ?: return
                val file = getKtFileToUpdateDependencies(virtualFile) ?: return
                if (!virtualFile.isValid) {
                    cache.delete(file)
                    return
                }

                // only update dependencies for scripts that were touched recently
                if (cache[file] == null) {
                    return
                }

                scriptsQueue.cancelAllRequests()

                scriptsQueue.addRequest(
                    {
                        FileDocumentManager.getInstance().saveDocument(document)
                        updateDependencies(file)
                        makeRootsChangeIfNeeded()
                    },
                    scriptChangesListenerDelay,
                    true
                )
            }
        }, project.messageBus.connect())
    }

    private fun getKtFileToUpdateDependencies(file: VirtualFile): KtFile? {
        if (project.isDisposed || !file.isValid || file.fileType != KotlinFileType.INSTANCE) {
            return null
        }

        if (
            ApplicationManager.getApplication().isUnitTestMode &&
            ApplicationManager.getApplication().isScriptDependenciesUpdaterDisabled == true
        ) {
            return null
        }

        return (PsiManager.getInstance(project).findFile(file) as? KtFile)?.takeIf {
            ProjectRootsUtil.isInProjectSource(it, includeScriptsOutsideSourceRoots = true)
        }
    }

    private fun areDependenciesCached(file: KtFile): Boolean {
        return cache[file] != null || file.virtualFile.scriptDependencies != null
    }

    fun isAsyncDependencyResolver(scriptDef: KotlinScriptDefinition): Boolean {
        val dependencyResolver = scriptDef.dependencyResolver
        return dependencyResolver is AsyncDependenciesResolver || dependencyResolver is LegacyResolverWrapper
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): ScriptDependenciesUpdater =
            ServiceManager.getService(project, ScriptDependenciesUpdater::class.java)

        fun areDependenciesCached(file: KtFile): Boolean {
            return getInstance(file.project).areDependenciesCached(file)
        }

        fun isAsyncDependencyResolver(file: KtFile): Boolean {
            val scriptDefinition = file.scriptDefinition() ?: return false
            return getInstance(file.project).isAsyncDependencyResolver(scriptDefinition)
        }
    }
}

@set: TestOnly
var Application.isScriptDependenciesUpdaterDisabled by NotNullableUserDataProperty(
    Key.create("SCRIPT_DEPENDENCIES_UPDATER_DISABLED"),
    false
)