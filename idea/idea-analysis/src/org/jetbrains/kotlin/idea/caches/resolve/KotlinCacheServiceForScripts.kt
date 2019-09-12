/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.caches.resolve

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.containers.SLRUCache
import org.jetbrains.kotlin.analyzer.ResolverForProject
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.idea.caches.project.*
import org.jetbrains.kotlin.idea.caches.project.IdeaModuleInfo
import org.jetbrains.kotlin.idea.caches.resolve.util.contextWithCompositeExceptionTracker
import org.jetbrains.kotlin.idea.caches.trackers.KotlinCodeBlockModificationListener
import org.jetbrains.kotlin.idea.caches.trackers.outOfBlockModificationCount
import org.jetbrains.kotlin.idea.core.script.ScriptDependenciesModificationTracker
import org.jetbrains.kotlin.idea.core.script.dependencies.ScriptAdditionalIdeaDependenciesProvider
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.utils.addToStdlib.sumByLong

internal class KotlinCacheServiceForScripts(
    val project: Project,
    val facadeForSdk: (PlatformAnalysisSettings) -> ProjectResolutionFacade,
    val facadeForModules: (PlatformAnalysisSettings) -> ProjectResolutionFacade
) {

    fun getFacadeForScripts(files: List<KtFile>, moduleInfo: IdeaModuleInfo): ResolutionFacade? {
        return when {
            isRegularScript(moduleInfo, files) -> {
                val projectFacade = getFacadeForScripts(files.toSet())
                ModuleResolutionFacadeImpl(projectFacade, moduleInfo).createdFor(files, moduleInfo)
            }
            isScriptDependencies(moduleInfo) -> {
                val filesModificationTracker = ModificationTracker {
                    files.sumByLong { it.outOfBlockModificationCount + it.modificationStamp }
                }
                val projectFacade = wrapWitSyntheticFiles(
                    getOrBuildScriptsGlobalFacade().facadeForDependencies,
                    ResolverForProject.resolverForScriptDependenciesName,
                    files.toSet(),
                    listOf(filesModificationTracker)
                )
                ModuleResolutionFacadeImpl(projectFacade, moduleInfo).createdFor(files, moduleInfo)
            }
            else -> null
        }
    }

    fun getFacadeForScriptDependencies(moduleInfo: IdeaModuleInfo): ResolutionFacade? {
        val projectFacade = when (moduleInfo) {
            is ScriptDependenciesInfo.ForProject -> getOrBuildScriptsGlobalFacade().facadeForDependencies
            is ScriptDependenciesSourceInfo.ForProject -> getOrBuildScriptsGlobalFacade().facadeForSources
            is ScriptDependenciesInfo.ForFile -> createFacadeForScriptDependencies(moduleInfo)
            else -> return null
        }
        return ModuleResolutionFacadeImpl(projectFacade, moduleInfo)
    }

    private val dependenciesTrackers = listOf(
        LibraryModificationTracker.getInstance(project),
        ProjectRootModificationTracker.getInstance(project),
        ScriptDependenciesModificationTracker.getInstance(project)
    )

    @Synchronized
    private fun getOrBuildScriptsGlobalFacade(): ScriptsGlobalFacade {
        val sdk = ScriptDependenciesInfo.ForProject(project).sdk
        val platform = JvmPlatforms.defaultJvmPlatform // TODO: Js scripts?
        val settings = PlatformAnalysisSettings.create(
            project, platform, sdk, true,
            LanguageFeature.ReleaseCoroutines.defaultState == LanguageFeature.State.ENABLED
        )
        return globalScriptFacadesPerPlatformAndSdk[settings]
    }

    private val globalScriptFacadesPerPlatformAndSdk: SLRUCache<PlatformAnalysisSettings, ScriptsGlobalFacade> =
        object : SLRUCache<PlatformAnalysisSettings, ScriptsGlobalFacade>(2, 2) {
            override fun createValue(settings: PlatformAnalysisSettings): ScriptsGlobalFacade {
                return ScriptsGlobalFacade(settings)
            }
        }

    private inner class ScriptsGlobalFacade(settings: PlatformAnalysisSettings) {
        private val dependenciesContext = facadeForSdk(settings).globalContext
            .contextWithCompositeExceptionTracker(project, ResolverForProject.resolverForScriptDependenciesName)

        val facadeForDependencies = ProjectResolutionFacade(
            debugString = "facade for script dependencies",
            resolverDebugName = "${ResolverForProject.resolverForScriptDependenciesName} with settings=${settings}",
            project = project,
            globalContext = dependenciesContext,
            settings = settings,
            moduleFilter = { it is ScriptDependenciesInfo.ForProject },
            allModules = ScriptDependenciesInfo.ForProject(project).dependencies(),
            dependencies = dependenciesTrackers,
            invalidateOnOOCB = false,
            reuseDataFrom = facadeForSdk(settings)
        )

        private val sourcesContext = dependenciesContext
            .contextWithCompositeExceptionTracker(project, ResolverForProject.resolverForScriptDependenciesSourcesName)

        val facadeForSources = ProjectResolutionFacade(
            debugString = "facade for script dependencies sources",
            resolverDebugName = "${ResolverForProject.resolverForScriptDependenciesSourcesName} with settings=${settings}",
            project = project,
            globalContext = sourcesContext,
            settings = settings,
            reuseDataFrom = facadeForDependencies,
            moduleFilter = { it is ScriptDependenciesSourceInfo.ForProject },
            allModules = ScriptDependenciesSourceInfo.ForProject(project).dependencies(),
            invalidateOnOOCB = false,
            dependencies = dependenciesTrackers
        )
    }

    private fun createFacadeForScriptDependencies(moduleInfo: ScriptDependenciesInfo.ForFile): ProjectResolutionFacade {
        val sdk = moduleInfo.sdk
        val platform = JvmPlatforms.defaultJvmPlatform // TODO: Js scripts?
        val settings = PlatformAnalysisSettings.create(
            project, platform, sdk, true,
            LanguageFeature.ReleaseCoroutines.defaultState == LanguageFeature.State.ENABLED
        )

        val relatedModules = ScriptAdditionalIdeaDependenciesProvider.getRelatedModules(moduleInfo.scriptFile, project)
        val globalFacade =
            if (relatedModules.isNotEmpty()) {
                facadeForModules(settings)
            } else {
                facadeForSdk(settings)
            }

        val globalContext = globalFacade.globalContext.contextWithCompositeExceptionTracker(project, "facadeForScriptDependencies")
        return ProjectResolutionFacade(
            "facadeForScriptDependencies",
            ResolverForProject.resolverForScriptDependenciesName,
            project, globalContext, settings,
            reuseDataFrom = globalFacade,
            allModules = moduleInfo.dependencies(),
            //TODO: provide correct trackers
            dependencies = dependenciesTrackers,
            moduleFilter = { it == moduleInfo },
            invalidateOnOOCB = false
        )
    }

    private fun wrapWitSyntheticFiles(
        reuseDataFrom: ProjectResolutionFacade,
        debugNamePrefix: String,
        files: Set<KtFile>,
        dependencies: List<ModificationTracker>,
        moduleFilter: (IdeaModuleInfo) -> Boolean = reuseDataFrom.moduleFilter
    ): ProjectResolutionFacade {
        val debugName = debugNamePrefix + " with synthetic files ${files.joinToString { it.name }}"
        val globalContext = reuseDataFrom.globalContext.contextWithCompositeExceptionTracker(project, debugName)

        val dependenciesForSyntheticFileCache = dependencies +
                KotlinCodeBlockModificationListener.getInstance(project).kotlinOutOfCodeBlockTracker

        return ProjectResolutionFacade(
            debugString = "facade for $debugNamePrefix",
            resolverDebugName = debugName,
            project = project,
            globalContext = globalContext,
            settings = reuseDataFrom.settings,
            syntheticFiles = files,
            reuseDataFrom = reuseDataFrom,
            moduleFilter = moduleFilter,
            dependencies = dependenciesForSyntheticFileCache,
            invalidateOnOOCB = true
        )
    }

    private fun createFacadeForScripts(files: Set<KtFile>): ProjectResolutionFacade {
        // we assume that all files come from the same module
        val scriptModuleInfo = files.map(KtFile::getModuleInfo).toSet().single()
        val platform = scriptModuleInfo.platform
        val settings = scriptModuleInfo.platformSettings(project, platform)

        val filesModificationTracker = ModificationTracker {
            files.sumByLong { it.outOfBlockModificationCount }
        }

        if (scriptModuleInfo is ModuleSourceInfo) {
            val dependentModules = scriptModuleInfo.getDependentModules()
            return wrapWitSyntheticFiles(
                facadeForModules(settings),
                ResolverForProject.resolverForScriptsName,
                files,
                listOf(filesModificationTracker)
            ) {
                it in dependentModules
            }
        }

        check(scriptModuleInfo is ScriptModuleInfo) {
            "Unknown ModuleInfo for scripts ${scriptModuleInfo::class.java}"
        }

        val facadeForScriptDependencies = createFacadeForScriptDependencies(
            ScriptDependenciesInfo.ForFile(project, scriptModuleInfo.scriptFile, scriptModuleInfo.scriptDefinition)
        )

        return wrapWitSyntheticFiles(
            facadeForScriptDependencies,
            ResolverForProject.resolverForScriptsName,
            files,
            listOf(filesModificationTracker)
        ) {
            it == scriptModuleInfo
        }
    }

    private val scriptsCacheProvider = CachedValueProvider {
        CachedValueProvider.Result(
            object : SLRUCache<Set<KtFile>, ProjectResolutionFacade>(10, 5) {
                override fun createValue(files: Set<KtFile>) = createFacadeForScripts(files)
            },
            LibraryModificationTracker.getInstance(project),
            ProjectRootModificationTracker.getInstance(project),
            ScriptDependenciesModificationTracker.getInstance(project)
        )
    }

    private fun getFacadeForScripts(files: Set<KtFile>): ProjectResolutionFacade {
        val cachedValue: SLRUCache<Set<KtFile>, ProjectResolutionFacade> =
            CachedValuesManager.getManager(project).getCachedValue(project, scriptsCacheProvider)

        return synchronized(cachedValue) {
            cachedValue.get(files)
        }
    }

    private fun isScriptDependencies(moduleInfo: IdeaModuleInfo) =
        moduleInfo is ScriptDependenciesInfo || moduleInfo is ScriptDependenciesSourceInfo

    private fun isRegularScript(moduleInfo: IdeaModuleInfo, files: List<KtFile>): Boolean {
        // In some cases (for ex. during scratch compilation) we analyzing simple KtFile in context of KtScript,
        // so its moduleInfo will be ScriptModuleInfo, but it won't be a script it self
        if (moduleInfo is ScriptModuleInfo) return true
        if (moduleInfo is ModuleSourceInfo) {
            return files.filter { it.isScript() }.toSet().isNotEmpty()
        }
        return false
    }
}