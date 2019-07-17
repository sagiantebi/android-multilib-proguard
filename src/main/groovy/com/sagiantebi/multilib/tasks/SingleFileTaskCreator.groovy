/*
 * SingleFileTaskCreator.groovy
 *
 * Copyright 2019 Sagi Antebi
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

import com.android.build.gradle.tasks.InvokeManifestMerger
import com.android.ide.common.xml.AndroidManifestParser
import com.sagiantebi.multilib.AndroidMultiLibProguardExtension
import com.sagiantebi.multilib.util.VariantDataCollector.CollectedVariantData
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.file.collections.LazilyInitializedFileCollection
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Zip
import org.pantsbuild.jarjar.Main
import org.w3c.dom.Node
import proguard.gradle.ProGuardTask

import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Helper class to manage and create the tasks required for a single aar creation.
 */
class SingleFileTaskCreator {

    final Project project
    final File workingDir
    final String packageName
    private final File destLibrary

    private File dirToExtractThingsInto = new File(workingDir, "single-temp")

    private List<Task> mainTaskDeps = new ArrayList<>()
    private List<File> rFiles = new ArrayList<>()
    private List<File> proguardFiles = new ArrayList<>()
    private List<File> sourceFolders = new ArrayList<>()
    private List<String> sourceFoldersPackageNames = new ArrayList<>()

    private File mainLibrarySourceFolder = null

    private Map<AndroidMultiLibProguardExtension.WrappedDependency, File> externalDeps = new HashMap<>()

    private File mainManifestFile
    private List<File> secondaryManifestFiles = new ArrayList<>()
    private File dest = new File(dirToExtractThingsInto, "staging")

    private List<File> lateFoundPackages = new ArrayList<>()

    SingleFileTaskCreator(Project project, File workingDir, String packageName) {
        this.project = project
        this.workingDir = workingDir
        this.packageName = packageName
        this.destLibrary = new File(workingDir, "single-aar")
    }

    Task generateProguardTask(SimpleTasksCreator creator, List<CollectedVariantData> collectedData, List<AndroidMultiLibProguardExtension.WrappedDependency> dependencies) {

        Task mkdirs = this.project.tasks.create("proguardMultiLibCreateDirectories")
        mkdirs.doLast {
            destLibrary.mkdirs()
            dest.mkdirs()
            dirToExtractThingsInto.mkdirs()
        }

        populateInternalDependencies(dependencies)

        collectedData.each { data ->
            collectLibrariesInformation(mkdirs, data.assembleTask, data.androidVariant.applicationId, data.outputFile)
        }

        externalDeps.each {wd, file ->
            if (file.name.endsWith("aar")) {
                collectLibrariesInformation(mkdirs, null, wd.dependencyNotation, file)
            } else {
                //create a task to copy the Jar to the proguard staging dir.
                if (wd.options != null && wd.options.renameFrom != null && wd.options.renameTo != null) {
                    Task task = generateJarJarTask("${wd.dependencyNotation.replace(":", "").replace("@", "")}", null, file, dirToExtractThingsInto, dest, null);
                    mainTaskDeps.add(task)
                } else {
                    Task task = project.tasks.create("proguardMultiLibCopyDependencyJar${appendedTaskNameFromPackageName(wd.dependencyNotation)}", Copy)
                    task.from file
                    task.into dest
                    mainTaskDeps.add(task)
                }


            }
        }

        if (mainManifestFile == null || mainLibrarySourceFolder == null) {
            throw new RuntimeException("could not find the target AndroidManifest.xml for the package ${this.packageName}")
        }

        Task annotationMerger = generateAnnotationMergerTask(sourceFolders)
        annotationMerger.description = "Merges all the libraries' annotation.zip files into a single Zip"
        annotationMerger.dependsOn(mainTaskDeps)

        Task r = generateFileMergerTask("proguardMultiLibMergeR", rFiles, new File(destLibrary, "R.txt"))
        r.description = "Merges all the libraries' R.txt files into a single R.txt"
        r.dependsOn(mainTaskDeps)

        Task proguardMerger = generateFileMergerTask("proguardMultiLibMergeProguardFiles", proguardFiles, new File(destLibrary, "proguard.txt"))
        proguardMerger.description = "Merges all the libraries' proguard.txt files into a single proguard.txt"
        proguardMerger.dependsOn(mainTaskDeps)

        Task merger = generateAndroidManifestMergerTask(mainManifestFile, secondaryManifestFiles)
        merger.description = "Merges all the libraries' AndroidManifests to a single Manifest file"
        merger.dependsOn(mkdirs)
        merger.dependsOn(mainTaskDeps)

        List<Task> copyFiles = generateDirectoriesCopyTask(mainLibrarySourceFolder, sourceFolders, sourceFoldersPackageNames, mainTaskDeps)
        copyFiles.each { ir -> ir.dependsOn(mainTaskDeps); ir.dependsOn(annotationMerger) }

        List<String> extDepsNotations = externalDeps.collect { d -> d.key.dependencyNotation }

        ProGuardTask proguardTask = creator.createProguardTask(dest.absolutePath, collectedData, new File(destLibrary, "classes.jar"), extDepsNotations)
        proguardTask.dependsOn(r)
        proguardTask.dependsOn(mainTaskDeps)
        proguardTask.dependsOn(proguardMerger)
        proguardTask.dependsOn(merger)
        proguardTask.dependsOn(annotationMerger)
        proguardTask.dependsOn(copyFiles)

        Task generateAndroidLibraryArchive = project.tasks.create("proguardMultiLibGenerateAndoirdLibraryArchive", Zip)
        generateAndroidLibraryArchive.description = "creates an AAR from the temporary folder we copied everying into"
        generateAndroidLibraryArchive.dependsOn(annotationMerger)
        File aarDest = new File(workingDir, SimpleTasksCreator.PROGUARD_DEST_LIBS)
        generateAndroidLibraryArchive.from(destLibrary)
        generateAndroidLibraryArchive.setArchiveName("${packageName}-merged.aar")
        generateAndroidLibraryArchive.setDestinationDir(aarDest)

        proguardTask.finalizedBy(generateAndroidLibraryArchive)

        Task cleanup = project.tasks.create("proguardMultiLibCleanup")
        cleanup.description = "Deletes all the temporary directories used for the creating the single library"
        //generateAndroidLibraryArchive.finalizedBy(cleanup)
        cleanup.doLast {
            dirToExtractThingsInto.deleteDir()
            destLibrary.deleteDir()
        }

        return proguardTask
    }

    void collectLibrariesInformation(Task mkdirs, Task assembleTask, String applicationId, File aarFile) {

        def dirName = new File(dirToExtractThingsInto, aarFile.name.substring(0, aarFile.name.lastIndexOf(".")))
        sourceFolders.add(dirName)
        def libraryPackageName = applicationId
        sourceFoldersPackageNames.add(appendedTaskNameFromPackageName(libraryPackageName))

        def libraryAndroidManifest = new File(dirName, "AndroidManifest.xml")

        //extract the aar
        Task task = project.tasks.create("proguardMultiLibExplodeAars${appendedTaskNameFromPackageName(libraryPackageName)}", Copy)
        task.description = "Explodes (unzip) the library (of ${libraryPackageName}) 's AARs into a temporary directory"
        if (assembleTask != null) {
            task.dependsOn assembleTask
        }
        task.into dirName
        task.dependsOn mkdirs
        task.from project.zipTree(aarFile)

        //run JarJar Links on the input Jar, outputting the result to a temporary directory.
        File classesJar = new File(dirName, "classes.jar")

        //we always run jarjar to copy the classes and in case we have a transform for a dependency
        File r = new File(dirName, "R.txt")
        Task jarjar = generateJarJarTask(applicationId, assembleTask == null ? null : libraryPackageName, classesJar, dirToExtractThingsInto, dest, r)
        jarjar.description = "Transforms the ${applicationId} class output and changes the relevant R usages"
        jarjar.dependsOn(mkdirs)
        jarjar.dependsOn(task)
        jarjar.mustRunAfter(task)
        mainTaskDeps.add(jarjar)

        //collect other data while we are iterating.
        if (libraryPackageName.equalsIgnoreCase(this.packageName)) {
            mainManifestFile = libraryAndroidManifest
            mainLibrarySourceFolder = mainManifestFile.parentFile
        } else {
            secondaryManifestFiles.add(libraryAndroidManifest)
            if (assembleTask == null) {
                //we need to find the package name from the manifest.
                lateFoundPackages.add(libraryAndroidManifest)
            }
        }

        rFiles.add(r)
        proguardFiles.add(new File(dirName, "proguard.txt"))
    }

    void populateInternalDependencies(List<AndroidMultiLibProguardExtension.WrappedDependency> dependencies) {
        if (dependencies.size() > 0) {
            Configuration config = project.configurations.create("proguardMultiLib")
            dependencies.collect{ dep -> project.dependencies.create(dep.dependencyNotation) }.each { d -> config.dependencies << d }
            config.resolvedConfiguration.resolvedArtifacts.each { r ->
                File dep = r.file
                def find = dependencies.find { other -> other.dependencyNotation.equalsIgnoreCase("${r.moduleVersion.id.group}:${r.moduleVersion.id.name}:${r.moduleVersion.id.version}") }
                if (find != null) {
                    externalDeps.put(find, dep)
                } else {
                    throw new RuntimeException("could not detect artifact ${r}.")
                }
            }
            project.configurations.remove(config)
            if (dependencies.size() != externalDeps.size()) {
                throw new RuntimeException("android-multilib-proguard could not resolve all artifacts. please check your dependency configuration.")
            }
        }
    }

    static String appendedTaskNameFromPackageName(String pkg) {
        return pkg.replace(":", "").split("\\.").collect { str -> str.capitalize() }.join("")
    }

    Task generateJarJarTask(String identifier, String libraryPackageName, File classesJar, File dirToExtractThingsInto, File dest, File r) {
        Task jarjar = project.tasks.create("proguardMultiLibTransformWithJarJar${appendedTaskNameFromPackageName(identifier)}")
        jarjar.doLast {
                Main main = new Main()
                File rulesFile = new File(dirToExtractThingsInto, "rules.txt")
                if (rulesFile.exists()) {
                    rulesFile.delete()
                }
                rulesFile.createNewFile()

                if (r != null) {
                    String resolvedPackageName;
                    if (libraryPackageName == null) {
                        //find our manifest
                        File androidManifestFile = lateFoundPackages.find { f -> f.parent == r.parent }
                        resolvedPackageName = AndroidManifestParser.parse(androidManifestFile.newInputStream()).package
                    } else {
                        resolvedPackageName = libraryPackageName
                    }
                    //read R and get all the resource types we require for this library.
                    Collection<String> resTypesInLibrary = resourceTypesFromRFile(r)
                    if (resTypesInLibrary.isEmpty() && r.exists()) {
                        resTypesInLibrary = ["id", "layout", "drawable", "dimen", "color", "integer", "string", "attr", "bool", "color", "style"]
                    }
                    resTypesInLibrary.each { str ->
                        rulesFile << "rule ${resolvedPackageName}.R" + '**' + "${str} ${this.packageName}.R" + '@1' + "${str}\n"
                    }
                }

                externalDeps.each { dep ->
                    if (dep.key.options != null && dep.key.options.renameFrom != null && dep.key.options.renameTo != null) {
                        rulesFile << "rule ${dep.key.options.renameFrom}.** ${dep.key.options.renameTo}.@1\n"
                    }
                }
                project.logger.debug("Starting jarjar... for ${identifier} using ${rulesFile} and ${classesJar}")
                main.process(rulesFile, classesJar, new File(dest, "${identifier}-classes.jar"))
                project.logger.debug("jarjar finished for ${identifier}")
        }
    }

    static Collection<String> resourceTypesFromRFile(File rFile) {
        rFile.readLines().collect { line ->
            line.split(" ")[1]
        }.toSet()
    }

    List<Task> generateDirectoriesCopyTask(File mainDirectory, List<File> secondaryDirectories, List<String> taskNames, List<Task> explodingTasks) {
        Copy copyMain = (Copy)project.tasks.create("proguardMultiLibCopyLibraryExplodedFiles", Copy)
        copyMain.description = "Copies the main library's files into a temporary folder which is the base for the new single AAR"
        copyMain.from(mainDirectory)
        copyMain.into(destLibrary)
        copyMain.exclude("AndroidManifest.xml", "proguard.txt", "annotations.zip", "classes.jar")
        copyMain.dependsOn(explodingTasks)
        def asd = secondaryDirectories.findAll { d -> d.absolutePath != mainDirectory.absolutePath }.collect() { f ->
            Copy copyEverythingElse = (Copy)project.tasks.create("proguardMultiLibCopyFiles${taskNames.get(secondaryDirectories.indexOf(f))}", Copy)
            copyEverythingElse.description = "Copies the other libraries' files into the temporary directory, excluding files which are merged"
            copyEverythingElse.exclude("AndroidManifest.xml", "proguard.txt", "annotations.zip", "classes.jar", "**/values**/*.xml")
            copyEverythingElse.from(f)
            copyEverythingElse.into(destLibrary)

            Task mergeXml = project.tasks.create("proguardMultiLibMergeValues${taskNames.get(secondaryDirectories.indexOf(f))}")
            mergeXml.description = "Merges the resource values from ${taskNames.get(secondaryDirectories.indexOf(f))} into the primary library's values"
            mergeXml.doLast {
                ConfigurableFileTree files = project.fileTree(f)
                files.include("**/values**/*.xml")
                files.collect().each { valuesFile ->
                    ConfigurableFileTree currentMatchingFiles = project.fileTree(destLibrary)
                    currentMatchingFiles.include("**/${valuesFile.parentFile.name}/${valuesFile.name}")
                    def collect = currentMatchingFiles.collect()
                    if (collect.size() == 1) {
                        project.logger.debug("Found ${collect.first()} in dest dir. merging the files")
                        File victim = collect.first()
                        def builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                        def target = builder.parse(victim)
                        builder.parse(valuesFile).documentElement.getChildNodes().each { node ->
                            if (node.nodeType == Node.ELEMENT_NODE) {
                                target.documentElement.appendChild(target.importNode(node, true))
                            }
                        }
                        TransformerFactory.newInstance().newTransformer().transform(new DOMSource(target), new StreamResult(victim))
                    } else if (collect.size() == 0) {
                        project.logger.debug("Did not find ${valuesFile.parentFile.name}/${valuesFile.name} in ${destLibrary}. copying the file")
                        project.copy {
                            from f
                            into destLibrary
                            include "**/${valuesFile.parentFile.name}/${valuesFile.name}"
                        }
                    } else {
                        throw new RuntimeException("Looking up ${valuesFile.parentFile.name}/${valuesFile.name} in ${destLibrary} has yielded multiple results. this can't happen.")
                    }
                }
            }
            copyEverythingElse.dependsOn(mergeXml)
            return copyEverythingElse
        }

        asd.each { task -> task.dependsOn(copyMain) }

        return asd
    }

    Task generateFileMergerTask(String taskName, List<File> inputR, File destR) {
        Task task = project.tasks.create(taskName)
        task.doLast {
            if (destR.exists()) {
                destR.delete()
            }
            destR.createNewFile()
            def stream = destR.newDataOutputStream()
            inputR.each { f ->
                if (f.exists()) {
                    stream << f.newInputStream()
                }
            }
            stream.close()
        }
        return task
    }

    Task generateAnnotationMergerTask(List<File> sourceFolders) {
        Task annotationMerger = project.tasks.create("proguardMultiLibMergerAnnotationsZip", Zip)
        List<File> mergeTargets = new ArrayList<>()
        sourceFolders.each { f->
            File annotationsZip = new File(f, "annotations.zip")
            mergeTargets.add(annotationsZip)
        }
        mergeTargets.each {f->annotationMerger.from( new LazilyInitializedFileCollection() {

            @Override
            FileCollection createDelegate() {
                if (f.exists()) {
                    return project.zipTree(f)
                }
                return project.files("${UUID.randomUUID().toString()-UUID.randomUUID().toString()}") //empty
            }

            @Override
            String getDisplayName() {
                return "lazyFor${f.parentFile.name}"
            }
        } )}
        annotationMerger.setArchiveName("annotations.zip")
        annotationMerger.setDestinationDir(destLibrary)
        return annotationMerger
    }

    Task generateAndroidManifestMergerTask(File mainManifest, List<File> secondaryManifest) {
        Task merger = project.tasks.create("proguardMultiLibMergeManifests", InvokeManifestMerger)
        merger.setMainManifestFile(mainManifest)
        merger.setSecondaryManifestFiles(secondaryManifest)
        merger.setOutputFile(new File(destLibrary, "AndroidManifest.xml"))
        merger.variantName = "full" //TODO figure out why is this needed.
        return merger
    }
}
