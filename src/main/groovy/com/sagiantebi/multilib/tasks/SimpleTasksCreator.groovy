/*
 * SimpleTasksCreator.groovy
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

package com.sagiantebi.multilib.tasks

import com.android.build.gradle.api.AndroidSourceSet
import com.android.build.gradle.internal.res.GenerateLibraryRFileTask
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.google.gson.Gson
import com.sagiantebi.multilib.AndroidMultiLibProguardExtension
import com.sagiantebi.multilib.util.DependenciesHelper
import com.sagiantebi.multilib.util.VariantDataCollector
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.file.collections.LazilyInitializedFileCollection
import org.gradle.api.tasks.Copy
import org.json.simple.JSONObject
import proguard.gradle.ProGuardTask

import static com.android.build.gradle.internal.scope.InternalArtifactType.LIBRARY_MANIFEST

/**
 * Utility, creates and configures all needed tasks for us,
 */

public class SimpleTasksCreator {

    /**
     * Directory name which holds the original-untouched libraries
     */
    static final String COPY_DEST = "libs"

    /**
     * Directory name which holds the proguard(ed) libraries
     */
    static final String PROGUARD_DEST_LIBS = "libs-proguard"

    /**
     * Directory name which holds the proguard configuration files (mapping, seeds, etc)
     */
    static final String PROGUARD_OUTPUTS_DEST = "proguard"

    /**
     * Directory name which holds the external proguard configuration files (from AARs, aapt)
     */
    static final String AAR_CONSUMED_CONFIGURATIONS = "external-proguard-files"

    private final Project project
    private final File workingDirectory
    private DependenciesHelper dependencyHelper

    public SimpleTasksCreator(Project project, File workingDirectory) {
        this.project = project
        this.workingDirectory = workingDirectory
        this.dependencyHelper = new DependenciesHelper(project)
    }

    /**
     * Creates the copy task which copies all the libraries onto a predefined directory
     * @param collectedVariantDataList
     * @return the copy task
     */
    public Task createCopyTask(List<VariantDataCollector.CollectedVariantData> collectedVariantDataList) {
        Copy copy = project.tasks.create("copyMultiLib", Copy)
        copy.description = "Copies all input projects' libraries (arr) into ${workingDirectory}/${COPY_DEST}"
        copy.into(new File(workingDirectory, COPY_DEST))
        collectedVariantDataList.each { VariantDataCollector.CollectedVariantData data ->
            copy.dependsOn data.assembleTask
            copy.from data.outputFile
        }
        return copy
    }

    /**
     * Creates a task which runs aapt on all input libraries, requesting a proguard configuration
     * @param collectedData
     * @return the appt task
     */
    private Task createGenerateAndroidAptProguard(List<VariantDataCollector.CollectedVariantData> collectedData) {
        Task runAapt = project.tasks.create("generateMultiLibProguardConfigs")
        runAapt.description "runs aapt on the input projects to determine the proguard keep rules for manifest or resource items"
        String buildToolsVersion = null
        File sdkDir = null
        collectedData.each { VariantDataCollector.CollectedVariantData data ->
            buildToolsVersion = data.androidExtension.buildToolsVersion
            sdkDir = data.androidExtension.sdkDirectory
        }

        if (buildToolsVersion != null && sdkDir != null) {
            String aapt = "${sdkDir.absolutePath}/build-tools/${buildToolsVersion}/aapt"
            //should work on both *nix and win ?
            File targetDir = new File(workingDirectory, AAR_CONSUMED_CONFIGURATIONS)

            if (!targetDir.exists()) {
                targetDir.mkdir()
            }
            collectedData.each { VariantDataCollector.CollectedVariantData data ->
                File targetFile = new File(targetDir, "${data.outputFile.getName().replace(".aar", ".pro")}")

                GenerateLibraryRFileTask task = data.androidVariant.outputs.first().processResourcesProvider.get()
                def manifest = task.getManifestFile()

                List<String> arguments = ["package",
                                          "-f",
                                          "--no-crunch",
                                          "-I", "${data.androidExtension.bootClasspath.first().absolutePath}",
                                          "-M", "${manifest}",
                                          "-S", "${task.inputResourcesDir.get().singleFile}",
                                          "-G", "${targetFile.absolutePath}"]
                addResourceDirectoriesToAaptArguments(arguments, data, runAapt)
                runAapt.doLast {
                    project.logger.debug("aapt invokation - aapt ${arguments.join(" ")}")
                    List<String> finalArgs = arguments;
                    if (!manifest.exists()) {
                        try {
                            def manifestDirectory = task.manifestFiles.get();
                            File jsonFile = new File(manifestDirectory.asFile, "output.json")
                            ArrayList<AndroidOutput> fff = new Gson().fromJson(new String(jsonFile.readBytes()), ArrayList.class)
                            File libraryManifest = new File(manifestDirectory.asFile, fff.first().path)
                            finalArgs = new ArrayList<>()
                            arguments.each { s -> finalArgs.add(s == "${manifest}" ? libraryManifest.absolutePath : s) }
                        } catch (Exception t) {
                            project.logger.warn("exception while trying to get the final manifest", t)
                        }
                    }

                    project.exec {
                        commandLine aapt
                        args finalArgs
                    }
                }
            }
        } else {
            project.getLogger().warn("could not detect any android build tools version or the android SDK directory, will not run aapt")
        }
        return runAapt
    }

    //simple model to use with gson. note - it seems that gson is transitive. check in the future what's up with that.
    class AndroidOutput {
        public String path;
    }


    /**
     * Add additional resource directories from dependencies. currently only finds projects.
     * @param args the aapt arguments we can modify
     * @param data the variant collected data
     * @param runAapt the task itself, so we can add task dependencies
     */

    private static void addResourceDirectoriesToAaptArguments(List<String> args, VariantDataCollector.CollectedVariantData data, Task runAapt) {
        List<String> depResDirs = new ArrayList<>()
        List<Task> depResProcessTasks = new ArrayList<>()

        List<AndroidSourceSet> relevantSourceSets = new ArrayList<>()
        NamedDomainObjectContainer<AndroidSourceSet> sourceSets = data.androidExtension.sourceSets
        AndroidSourceSet main = sourceSets.getByName("main")
        if (!data.androidVariant.buildType.name.equals("release")) {
            AndroidSourceSet debug = sourceSets.getByName("main")
            relevantSourceSets.add(debug)
        }
        relevantSourceSets.add(main)

        //flavor deps
        if (data.androidVariant.flavorName != null && !data.androidVariant.flavorName.isEmpty()) {
            AndroidSourceSet flavor = sourceSets.getByName(data.androidVariant.flavorName)
            relevantSourceSets.add(flavor)
        }
        //get the variant deps
        AndroidSourceSet variantSourceSet = sourceSets.getByName(data.androidVariant.name)
        relevantSourceSets.add(variantSourceSet)

        relevantSourceSets.each { AndroidSourceSet set ->
            Collection<String> possibleConfigurations = new ArrayList<>()
            possibleConfigurations.add(set.implementationConfigurationName)
            possibleConfigurations.add(set.compileConfigurationName)
            possibleConfigurations.add(set.providedConfigurationName)
            possibleConfigurations.add(set.apiConfigurationName)
            possibleConfigurations.add(set.runtimeOnlyConfigurationName)
            possibleConfigurations.add(set.compileOnlyConfigurationName)

            possibleConfigurations.each { configName ->
                def configuration = data.project.configurations.getByName(configName)
                configuration.dependencies.each { d ->
                    if (d instanceof ProjectDependency) {
                        ProjectDependency projectDependency = d;
                        def otherProject = projectDependency.getDependencyProject();
                        AndroidMultiLibProguardExtension.WrappedProject wp = new AndroidMultiLibProguardExtension.WrappedProject(otherProject, new AndroidMultiLibProguardExtension.ProjectOptions())
                        def otherDep = VariantDataCollector.resolveAllTargets(Collections.singletonList(wp))
                        if (otherDep.size() > 0) {
                            GenerateLibraryRFileTask resTask = otherDep.first().androidVariant.outputs.first().processResourcesProvider.get()
                            def resDir = resTask.getInputResourcesDir().get().getSingleFile().absolutePath
                            depResDirs.add(resDir)
                            depResProcessTasks.add(resTask)
                        }
                    }
                }
            }
        }

        data.androidVariant.allRawAndroidResources.files.each {
            if (it.exists()) {
                depResDirs.add(it.path)
            }
        }

        if (depResDirs.size() > 0) {
            depResDirs.each { s->
                args.add("-S")
                args.add(s)
            }
            args.add("--auto-add-overlay")
            runAapt.dependsOn(depResProcessTasks)
        }
    }

    /**
     * Creates a {@link CollectProguardFilesTask} task, which finds and extracts consumer proguard files from AARs
     * @param collectedData
     * @return the collection task
     */
    public Task createCollectProguardFilesTask(List<VariantDataCollector.CollectedVariantData> collectedData) {
        CollectProguardFilesTask task = project.tasks.create("collectDependenciesProguardConfigs", CollectProguardFilesTask)

        Task dep = createGenerateAndroidAptProguard(collectedData)

        List<File> aars = dependencyHelper.findCompiledAars(collectedData)
        task.inputAARs.addAll(aars)
        task.workingDirectory = new File(workingDirectory, AAR_CONSUMED_CONFIGURATIONS)
        task.description "Collects all the proguard configuration files from compiled AARs"
        task.dependsOn dep
        return task
    }

    /**
     * Creates the proguard task
     * @param filePath
     * @param collectedVariantDatas
     * @return
     */
    public ProGuardTask createProguardTask(String filePath, List<VariantDataCollector.CollectedVariantData> collectedVariantDatas, File out, List<String> dependencyNotationsToIgnore = new ArrayList<>()) {
        ProGuardTask proGuardTask = project.tasks.create("proguardMultiLib", ProGuardTask)
        File output = out == null ? new File(workingDirectory, PROGUARD_DEST_LIBS) : out
        configureProguardTaskOutput(proGuardTask, output);

        proGuardTask.description "Runs proguard on the input libraries and outputs obsfucated libraries to ${output}"

        proGuardTask.injars(filePath)
        proGuardTask.outjars(output.getAbsolutePath())

        configureProguardTaskLibraryJars(proGuardTask, collectedVariantDatas, dependencyNotationsToIgnore)
        configureProguardTaskConfigurationFiles(proGuardTask)
        configureProguardTaskOutputs(proGuardTask, new File(workingDirectory, PROGUARD_OUTPUTS_DEST))
        project.logger.debug("proguard task - ${proGuardTask}")
        project.logger.debug("proguard libjars - ${proGuardTask.getLibraryJarFiles()}")
        project.logger.debug("proguard injars - ${proGuardTask.getInJarFiles()}")
        project.logger.debug("proguard configs - ${proGuardTask.getConfigurationFiles()}")
        proGuardTask.outputs.files.asFileTree
        return proGuardTask
    }

    private void configureProguardTaskOutput(Task parent, File dir) {
        if (dir.exists()) {
            Task deleteProguard = project.tasks.create("removeProguardMultiLibOutputDirectory")
            deleteProguard.description "removes the proguard-libraries' output directory before running android-multilib-proguard"
            parent.dependsOn deleteProguard
            deleteProguard.doLast {
                dir.deleteDir()
            }
        }
    }

    private void configureProguardTaskConfigurationFiles(ProGuardTask task) {
        task.configuration(project.files(project.androidMultilibProguard.getProguardConfigurationFiles()))
        //since the proguard configuraion files do not exist yet (and proguard task doesn't accept directories)
        //we create a lazy file collection which returns the files only when these are in existence
        LazilyInitializedFileCollection collection = new LazilyInitializedFileCollection() {
            @Override
            FileCollection createDelegate() {
                File f = new File(workingDirectory, AAR_CONSUMED_CONFIGURATIONS);
                File[] list = f.listFiles()
                if (list == null || list.length == 0) {
                    return null
                }
                project.logger.debug("configureProguardTaskConfigurationFiles, returning the following list of files - ${list}")
                return project.files(list)
            }

            @Override
            String getDisplayName() {
                return "LazyProguardConfigurations"
            }
        }
        task.configuration(collection)

        if (this.project.androidMultilibProguard.getAndroidProguardFileName() != null) {
            def internalandroidProguardFile = new File(workingDirectory, "proguard-android-optimize.txt");
            if (!internalandroidProguardFile.exists()) {
                com.android.build.gradle.ProguardFiles.createProguardFile(this.project.androidMultilibProguard.getAndroidProguardFileName(), internalandroidProguardFile)
            }
            task.configuration(internalandroidProguardFile)
        }
    }

    private void configureProguardTaskLibraryJars(ProGuardTask task, List<VariantDataCollector.CollectedVariantData> collectedVariantDatas, List<String> dependencyNotationsToIgnore) {
        List<File> libraryJars = new ArrayList<>()
        dependencyHelper.removeDependenciesByNotation(dependencyNotationsToIgnore)
        libraryJars.addAll(dependencyHelper.findLibraryJars(collectedVariantDatas))
        libraryJars.add(dependencyHelper.findAndroidJar(collectedVariantDatas))
        libraryJars.addAll(dependencyHelper.findCompiledAars(collectedVariantDatas))
        task.libraryjars(libraryJars)
    }


    private void configureProguardTaskOutputs(ProGuardTask task, File directory) {
        //mkdir in configuration stage, better be safe.
        if (!directory.exists()) {
            directory.mkdirs()
        }
        task.printmapping(new File(directory, "mapping.txt"))
        task.printconfiguration(new File(directory, "config.txt"))
        task.printseeds(new File(directory, "seeds.txt"))
        task.printusage(new File(directory, "usage.txt"))
    }



}
