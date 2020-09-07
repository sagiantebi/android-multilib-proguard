/*
 * DependenciesHelper.groovy
 *
 * Copyright 2017 Sagi Antebi
 *
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.sagiantebi.multilib.util

import com.android.build.api.attributes.VariantAttr
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.AndroidSourceSet
import com.android.build.gradle.internal.scope.GlobalScope
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.attributes.Usage
import org.gradle.api.internal.artifacts.dependencies.DefaultSelfResolvingDependency
import org.gradle.api.model.ObjectFactory

import java.lang.reflect.Field

/**
 * Gathers utility methods for finding dependencies in a project
 */

public class DependenciesHelper {

    private static KEY_COMPILE_ONLY = "compileOnly"
    private static KEY_IMPL = "impl"
    private static KEY_API= "api"
    private static KEY_RUNTIME = "runtime"

    private static KEY_OLD_COMPILE = "compile"
    private static KEY_OLD_PROVIDED = "provided"

    private Project project;

    private Set<String> ignoredDependencies = new HashSet<>()

    public DependenciesHelper(Project project) {
        this.project = project
    }

    /**
     * Marks these notations as something that shouldn't be added to any of the return values.
     * @param notationsToIgnore
     */
    void removeDependenciesByNotation(List<String> notationsToIgnore) {
        ignoredDependencies.addAll(notationsToIgnore)
    }

    /**
     * Finds the android jar, so ProGuard can reference correctly.
     * @param collectedVariantDataList the list of collected variant data
     * @return a File referencing the Android jar (or bootpath)
     */
    public File findAndroidJar(List<VariantDataCollector.CollectedVariantData> collectedVariantDataList) {
        //we might have multiple paths, find the first and warn about different APIs
        HashSet<File> bootPaths = new HashSet<>()
        collectedVariantDataList.each { VariantDataCollector.CollectedVariantData info ->
            LibraryExtension extension = info.androidExtension
            List<File> bootClasspath = extension.getBootClasspath()
            bootPaths.addAll(bootClasspath)
        }
        if (bootPaths.size() > 1) {
            collectedVariantDataList.first().project.getLogger().warn("WARNING - multiple Android SDK Versions detected. It is recommended that all libraries target the same Android SDK version when using this plugin.")
        }
        File bootPath = bootPaths.size() == 0 ? null : bootPaths.find { f -> f.getName().equals("android.jar") }
        project.getLogger().debug("findAndroidJar returning bootPath {}", bootPath)
        return bootPath
    }

    /**
     * finds all the provided dependencies in an android project
     * @param collectedVariantData the target variant
     * @param localInclusiveModules the target projects
     * @return  a list of Files, referencing the provided libraries.
     */
    public List<File> findResolvedProvidedDependencies(VariantDataCollector.CollectedVariantData collectedVariantData, List<String> localInclusiveModules) {
        List<File> output = new ArrayList<>()
        output.addAll(findResolvedDependencies(collectedVariantData, localInclusiveModules, invokerFor(KEY_COMPILE_ONLY)));
        output.addAll(findResolvedDependencies(collectedVariantData, localInclusiveModules, invokerFor(KEY_RUNTIME)));
        output.addAll(findResolvedDependencies(collectedVariantData, localInclusiveModules, invokerFor(KEY_OLD_PROVIDED)));

        //there are also libraries added by android as 'provided' / 'runtime' during building, eg. org.apache.http.legacy
        List<File> extras = collectedVariantData.androidExtension.bootClasspath
        extras.each { f ->
            if (!f.name.equals("android.jar")) {
                project.logger.debug("adding ${f} from androidExtension.getBootClasspath()")
                output.add(f)
            }
        }
        
        return output;
    }

    /**
     * finds all the compiled dependencies in an android project
     * @param collectedVariantData the target variant
     * @param localInclusiveModules the target projects
     * @return  a list of Files, referencing the compiled libraries.
     */
    public List<File> findResolvedCompiledDependencies(VariantDataCollector.CollectedVariantData collectedVariantData, List<String> localInclusiveModules) {
        List<File> output = new ArrayList<>()
        output.addAll(findResolvedDependencies(collectedVariantData, localInclusiveModules, invokerFor(KEY_API)));
        output.addAll(findResolvedDependencies(collectedVariantData, localInclusiveModules, invokerFor(KEY_IMPL)));
        output.addAll(findResolvedDependencies(collectedVariantData, localInclusiveModules, invokerFor(KEY_OLD_COMPILE)));
        return output;
    }

    /**
     * Helper function to return RelevantConfigurationInvoker
     * @param name the configuration name we need.
     * @return
     */
    private RelevantConfigurationInvoker invokerFor(final String name) {
        RelevantConfigurationInvoker invoker = new RelevantConfigurationInvoker() {
            @Override
            Configuration getCorrectConfigurationName(Project project, AndroidSourceSet set) {
                String value = set.getImplementationConfigurationName()
                if (name.equals(KEY_API)) {
                    value = set.getApiConfigurationName()
                } else if (name.equals(KEY_RUNTIME)) {
                    value = set.getRuntimeOnlyConfigurationName()
                } else if (name.equals(KEY_COMPILE_ONLY)) {
                    value = set.getCompileOnlyConfigurationName()
                } else if (name.equals(KEY_OLD_PROVIDED)) {
                    value = set.providedConfigurationName
                } else if (name.equals(KEY_OLD_COMPILE)) {
                    value = set.compileConfigurationName
                } else if (!name.equals(KEY_IMPL)){
                    project.getLogger().warn("couldn't find configuration for ${name}, defaulting to 'imp.'")
                }
                return project.configurations.getByName(value)
            }
        }
        return invoker
    }

    /**
     * finds all dependencies given a list of projects (/data) matched using the supplied interface
     * @param variantInformation the variant in which we want to find the dependecies for
     * @param localInclusiveModules a list of modules which should not be included. this is to avoid cluttering the configuration.</br> the format for this list is group:name:version
     * @param configInvoker a utility interface to match the correct configuration. useful for reuse of this code.
     */
    public List<File> findResolvedDependencies(VariantDataCollector.CollectedVariantData collectedVariantData, List<String> localInclusiveModules, RelevantConfigurationInvoker configInvoker) {
        List<File> out = new ArrayList<>()

        //TODO modify this  to be more dynamic, eg. from project.compile.

        //get dependencies form the 'main' configuration
        List<AndroidSourceSet> relevantSourceSets = new ArrayList<>()
        NamedDomainObjectContainer<AndroidSourceSet> sourceSets = collectedVariantData.androidExtension.sourceSets
        AndroidSourceSet main = sourceSets.getByName("main")
        if (!collectedVariantData.androidVariant.buildType.name.equals("release")) {
            AndroidSourceSet debug = sourceSets.getByName("main")
            relevantSourceSets.add(debug)
        }
        relevantSourceSets.add(main)

        //flavor deps
        if (collectedVariantData.androidVariant.flavorName != null && !collectedVariantData.androidVariant.flavorName.isEmpty()) {
            AndroidSourceSet flavor = sourceSets.getByName(collectedVariantData.androidVariant.flavorName)
            relevantSourceSets.add(flavor)
        }
        //get the variant deps
        AndroidSourceSet variantSourceSet = sourceSets.getByName(collectedVariantData.androidVariant.name)
        relevantSourceSets.add(variantSourceSet)

        //iterate and find provided artifcats.
        relevantSourceSets.each { AndroidSourceSet set ->

            Configuration configuration = configInvoker.getCorrectConfigurationName(collectedVariantData.project, set)
            Set<ResolvedDependency> dependencies;
            if (configuration.canBeResolved) {
                dependencies = configuration.resolvedConfiguration.firstLevelModuleDependencies
            } else {
                def name = "${collectedVariantData.project.name}-${collectedVariantData.androidVariant.name}-${set.name}-${configuration.name}-depHelper"
                boolean hasConfig = true;
                try { collectedVariantData.project.configurations.getByName(name) } catch (UnknownConfigurationException ignored) {hasConfig = false}
                if (!hasConfig) {
                    def temp = collectedVariantData.project.configurations.create(name)
                    AttributeContainer container = temp.getAttributes()

                    ObjectFactory factory = project.getObjects();
                    VariantAttr variantNameAttr = factory.named(VariantAttr.class, collectedVariantData.androidVariant.name);
                    final Usage apiUsage = factory.named(Usage.class, Usage.JAVA_API);

                    container.attribute(VariantAttr.ATTRIBUTE, variantNameAttr);
                    container.attribute(Usage.USAGE_ATTRIBUTE, apiUsage);

                    configuration.allDependencies.each { d->
                        temp.dependencies.add(d)
                    }
                    temp.setDescription("Dependency helper for " + collectedVariantData.androidVariant.name);
                    temp.getResolutionStrategy().sortArtifacts(ResolutionStrategy.SortOrder.CONSUMER_FIRST);
                    dependencies = temp.resolvedConfiguration.firstLevelModuleDependencies
                    configuration.dependencies.each {d ->
                        project.logger.debug("found dependency - ${d}")
                        if (d instanceof DefaultSelfResolvingDependency) {
                            DefaultSelfResolvingDependency selfResolvingDependency = d;
                            project.logger.debug("got a self resolving dependecy - ${selfResolvingDependency.files.files}")
                            if (!ignoredDependencies.contains("${selfResolvingDependency.group}:${selfResolvingDependency.name}:${selfResolvingDependency.version}")) {
                                out.addAll(selfResolvingDependency.files.files)
                            }
                        }
                    }
                    collectedVariantData.project.configurations.remove(temp)
                }
            }
            dependencies.each { ResolvedDependency d ->
                if (!isInclusive(localInclusiveModules, d)) {
                    Set<ResolvedArtifact> artifacts = d.allModuleArtifacts
                    artifacts.each { ResolvedArtifact ar ->
                        if (!ignoredDependencies.contains("${d.getModuleGroup()}:${d.getModuleName()}:${d.getModuleVersion()}".toString())) {
                            out.add(ar.file)
                        }
                    }
                }
            }
        }
        return out
    }

    /**
     * Finds provided libraries.
     * @param variantCollectedData the collected data for this project.
     * @return a list of Files, referencing the provided libraries.
     */
    public List<File> findLibraryJars(List<VariantDataCollector.CollectedVariantData> variantCollectedData) {
        final List<String> includedProjects = new ArrayList<>()
        variantCollectedData.each {info ->
            def str = "${info.project.group}:${info.project.name}:${info.project.version}"
            includedProjects.add(str)
        }
        List<File> out = new ArrayList<>()
        variantCollectedData.each {VariantDataCollector.CollectedVariantData info ->
            List<File> files = findResolvedProvidedDependencies(info, includedProjects)
            out.addAll(files)
        }
        return out
    }

    /**
     * Finds artifacts which are compiled into the variants.
     * @param variantCollectedData  the collected data for the projects
     * @return a list of Files, referencing the compiled libraries.
     */
    public List<File> findCompiledAars(List<VariantDataCollector.CollectedVariantData> variantCollectedData) {
        final List<String> includedProjects = new ArrayList<>()
        variantCollectedData.each {info ->
            def str = "${info.project.group}:${info.project.name}:${info.project.version}"
            includedProjects.add(str)
        }
        List<File> out = new ArrayList<>()
        variantCollectedData.each {VariantDataCollector.CollectedVariantData info ->
            List<File> files = findResolvedCompiledDependencies(info, includedProjects)
            out.addAll(files)
        }
        Set<File> aars = new HashSet<>()
        out.each { File f ->
            if (f.name.endsWith("aar")) {
                aars.add(f)
            }
        }
        return new ArrayList<File>(aars)
    }

    /**
     * returns wheter the dependecy is another project we already know about.<br/>
     * This is to avoid providing Proguard any of the target projects as a libraryjar
     * @param inclusives a formatted list of strings telling us which projects are already in use
     * @param resolvedDependency the depedency we might want to avoid
     * @return true if this ResolvedDependency is a target project, false otherwise
     */
    private boolean isInclusive(List<String> inclusives, ResolvedDependency resolvedDependency) {
        return inclusives.contains("${resolvedDependency.moduleGroup}:${resolvedDependency.moduleName}:${resolvedDependency.moduleVersion}")
    }

    /**
     * Helper interface to provide relevant configurations given an AndroidSourceSet
     */
    private interface RelevantConfigurationInvoker {
        /**
         * returns the relevant configuration
         * @param project the project to find the configuration from
         * @param set the source set we currently process
         * @return a configuration.
         */
        Configuration getCorrectConfigurationName(Project project, AndroidSourceSet set)
    }

}
