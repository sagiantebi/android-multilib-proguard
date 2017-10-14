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

import com.sagiantebi.multilib.util.DependenciesHelper;
import com.sagiantebi.multilib.util.VariantDataCollector;

import org.gradle.api.Project;
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.file.collections.LazilyInitializedFileCollection
import org.gradle.api.internal.file.collections.SimpleFileCollection;
import org.gradle.api.tasks.Copy
import proguard.gradle.ProGuardTask

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

    public SimpleTasksCreator(Project project, File workingDirectory) {
        this.project = project
        this.workingDirectory = workingDirectory
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
            String aapt = "${sdkDir.absolutePath}/build-tools/${buildToolsVersion}/aapt" //should work on both *nix and win ?
            File targetDir = new File(workingDirectory, AAR_CONSUMED_CONFIGURATIONS)
            runAapt.doLast {
                if (!targetDir.exists()) {
                    targetDir.mkdir()
                }
                collectedData.each { VariantDataCollector.CollectedVariantData data ->
                    File targetFile = new File(targetDir, "${data.outputFile.getName().replace(".aar", ".pro")}")
                    project.exec {
                        commandLine aapt
                        args ("package",
                                "-f",
                                "--no-crunch",
                                "-I","${data.androidExtension.bootClasspath.first().absolutePath}",
                                "-M","${data.androidVariant.outputs.first().processManifest.manifestOutputFile.absolutePath}",
                                "-S","${data.androidVariant.outputs.first().processResources.resDir.absolutePath}",
                                "-G","${targetFile.absolutePath}")
                    }
                }
            }
        } else {
            project.getLogger().warn("could not detect any android build tools version or the android SDK directory, will not run aapt")
        }
        return runAapt
    }

    /**
     * Creates a {@link CollectProguardFilesTask} task, which finds and extracts consumer proguard files from AARs
     * @param collectedData
     * @return the collection task
     */
    public Task createCollectProguardFilesTask(List<VariantDataCollector.CollectedVariantData> collectedData) {
        CollectProguardFilesTask task = project.tasks.create("collectDependenciesProguardConfigs", CollectProguardFilesTask)

        Task dep = createGenerateAndroidAptProguard(collectedData)

        List<File> aars = DependenciesHelper.findCompiledAars(collectedData)
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
    public Task createProguardTask(String filePath, List<VariantDataCollector.CollectedVariantData> collectedVariantDatas) {
        ProGuardTask proGuardTask = project.tasks.create("proguardMultiLib", ProGuardTask)
        File output = new File(workingDirectory, PROGUARD_DEST_LIBS)
        configureProguardTaskOutput(proGuardTask, output);

        proGuardTask.description "Runs proguard on the input libraries and outputs obsfucated libraries to ${output}"

        proGuardTask.injars(filePath)
        proGuardTask.outjars(output.getAbsolutePath())

        proGuardTask.keep('class **.R')
        proGuardTask.keep('class **.R$*')

        configureProguardTaskLibraryJars(proGuardTask, collectedVariantDatas)
        configureProguardTaskConfigurationFiles(proGuardTask)
        configureProguardTaskOutputs(proGuardTask, new File(workingDirectory, PROGUARD_OUTPUTS_DEST))
        println "proguard task - ${proGuardTask}"
        println "proguard libjars - ${proGuardTask.getLibraryJarFiles()}"
        println "proguard injars - ${proGuardTask.getInJarFiles()}"
        println "proguard configs - ${proGuardTask.getConfigurationFiles()}"
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
                if (list == null) {
                    return null
                }
                return new SimpleFileCollection(list)
            }

            @Override
            String getDisplayName() {
                return "LazyProguardConfigurations"
            }
        }
        task.configuration(collection)

        if (this.project.androidMultilibProguard.getAndroidProguardFileName() != null) {
            com.android.build.gradle.ProguardFiles.extractBundledProguardFiles(project)
            File androidPro  = com.android.build.gradle.ProguardFiles.getDefaultProguardFile(this.project.androidMultilibProguard.getAndroidProguardFileName(), project)
            task.configuration(androidPro)
        }
    }

    private void configureProguardTaskLibraryJars(ProGuardTask task, List<VariantDataCollector.CollectedVariantData> collectedVariantDatas) {
        List<File> libraryJars = new ArrayList<>()
        libraryJars.addAll(DependenciesHelper.findLibraryJars(collectedVariantDatas))
        libraryJars.add(DependenciesHelper.findAndroidJar(collectedVariantDatas))
        libraryJars.addAll(DependenciesHelper.findCompiledAars(collectedVariantDatas))
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
