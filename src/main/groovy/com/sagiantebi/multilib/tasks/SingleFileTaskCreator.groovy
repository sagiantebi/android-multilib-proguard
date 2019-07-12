package com.sagiantebi.multilib.tasks

import com.android.build.gradle.tasks.InvokeManifestMerger
import com.google.common.io.LineReader
import com.sagiantebi.multilib.util.VariantDataCollector
import com.sagiantebi.multilib.util.VariantDataCollector.CollectedVariantData
import jdk.internal.util.xml.impl.ReaderUTF8
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.file.collections.LazilyInitializedFileCollection
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Zip
import org.pantsbuild.jarjar.Main
import org.w3c.dom.Document
import org.w3c.dom.Node
import proguard.gradle.ProGuardTask

import javax.xml.parsers.DocumentBuilder
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

    SingleFileTaskCreator(Project project, File workingDir, String packageName) {
        this.project = project
        this.workingDir = workingDir
        this.packageName = packageName;
        this.destLibrary = new File(workingDir, "single-aar")
    }

    Task generateProguardTask(SimpleTasksCreator creator, List<CollectedVariantData> collectedData) {
        File dirToExtractThingsInto = new File(workingDir, "single-temp")

        List<Task> deps = new ArrayList<>()
        List<File> rFiles = new ArrayList<>()
        List<File> proguardFiles = new ArrayList<>()
        List<File> sourceFolders = new ArrayList<>()
        List<String> sourceFoldersPackageNames = new ArrayList<>()
        List<String> packageNamesToRename = new ArrayList<>()
        File mainLibrarySourceFolder = null;

        File mainManifestFile;
        List<File> secondaryManifestFiles = new ArrayList<>();
        def dest = new File(dirToExtractThingsInto, "staging")

        Task mkdirs = this.project.tasks.create("proguardMultiLibCreateDirectories")
        mkdirs.doLast {
            destLibrary.mkdirs()
            dest.mkdirs()
            dirToExtractThingsInto.mkdirs()
        }


        collectedData.each { data ->

            def dirName = new File(dirToExtractThingsInto, data.outputFile.name.substring(0, data.outputFile.name.lastIndexOf(".")))
            sourceFolders.add(dirName)
            def libraryPackageName = data.androidVariant.applicationId;
            sourceFoldersPackageNames.add(appendedTaskNameFromPackageName(libraryPackageName))

            //extract the aar
            Task task = project.tasks.create("proguardMultiLibExplodeAars${appendedTaskNameFromPackageName(libraryPackageName)}", Copy)
            task.dependsOn data.assembleTask
            task.into dirName
            task.dependsOn mkdirs
            task.from project.zipTree(data.outputFile)

            //run JarJar Links on the input Jar, outputting the result to a temporary directory.
            File classesJar = new File(dirName, "classes.jar")

            File r = new File(dirName, "R.txt")
            Task jarjar = generateJarJarTask(data, libraryPackageName, classesJar, dirToExtractThingsInto, dest, r)
            jarjar.dependsOn(mkdirs)
            jarjar.dependsOn(task)
            jarjar.mustRunAfter(task)
            deps.add(jarjar)

            //collect other data while we are iterating.
            def libraryAndroidManifest = new File(dirName, "AndroidManifest.xml")
            if (libraryPackageName.equalsIgnoreCase(this.packageName)) {
                mainManifestFile = libraryAndroidManifest
                mainLibrarySourceFolder = mainManifestFile.parentFile
            } else {
                secondaryManifestFiles.add(libraryAndroidManifest)
                packageNamesToRename.add(libraryPackageName)
            }

            rFiles.add(r)
            proguardFiles.add(new File(dirName, "proguard.txt"))

        }

        if (mainManifestFile == null || mainLibrarySourceFolder == null) {
            throw new RuntimeException("could not find the target AndroidManifest.xml for the package ${this.packageName}")
        }

        //merge R.txt
        //merge proguard.txt
        //merge AndroidManifest.xml
        //parse package names from AndroidManifest.xml for the jarjar renaming
        //merge classes.jar using jarjar. rename relevant R imports.

        Task annotationMerger = generateAnnotationMergerTask(sourceFolders)
        annotationMerger.dependsOn(deps)

        Task r = generateFileMergerTask("proguardMultiLibMergeR", rFiles, new File(destLibrary, "R.txt"))
        r.dependsOn(deps) //make sure exploding is done

        Task proguardMerger = generateFileMergerTask("proguardMultiLibMergeProguardFiles", proguardFiles, new File(destLibrary, "proguard.txt"))
        proguardMerger.dependsOn(deps)

        Task merger = generateAndroidManifestMergerTask(mainManifestFile, secondaryManifestFiles)
        merger.dependsOn(mkdirs)
        merger.dependsOn(deps)

        List<Task> copyFiles = generateDirectoriesCopyTask(mainLibrarySourceFolder, sourceFolders, sourceFoldersPackageNames, deps)
        copyFiles.each { ir -> ir.dependsOn(deps); ir.dependsOn(annotationMerger) }

        ProGuardTask proguardTask = creator.createProguardTask(dest.absolutePath, collectedData, new File(destLibrary, "classes.jar"))
        proguardTask.dependsOn(r)
        proguardTask.dependsOn(deps)
        proguardTask.dependsOn(proguardMerger)
        proguardTask.dependsOn(merger)
        proguardTask.dependsOn(annotationMerger)
        proguardTask.dependsOn(copyFiles)

        Task generateAndroidLibraryArchive = project.tasks.create("proguardMultiLibGenerateAndoirdLibraryArchive", Zip)
        generateAndroidLibraryArchive.dependsOn(annotationMerger)
        File aarDest = new File(workingDir, SimpleTasksCreator.PROGUARD_DEST_LIBS)
        generateAndroidLibraryArchive.from(destLibrary)
        generateAndroidLibraryArchive.setArchiveName("${packageName}-${System.currentTimeMillis()}.aar")
        generateAndroidLibraryArchive.setDestinationDir(aarDest)

        proguardTask.finalizedBy(generateAndroidLibraryArchive)

        return proguardTask
    }

    String appendedTaskNameFromPackageName(String pkg) {
        return pkg.split("\\.").collect { str -> str.capitalize() }.join("")
    }

    Task generateJarJarTask(CollectedVariantData data, String libraryPackageName, File classesJar, File dirToExtractThingsInto, File dest, File r) {
        Task jarjar = project.tasks.create("proguardMultiLibJarJar${appendedTaskNameFromPackageName(data.androidVariant.applicationId)}")
        jarjar.doLast {
            Main main = new Main();
            File rulesFile = new File(dirToExtractThingsInto, "rules.txt")
            if (rulesFile.exists()) {
                rulesFile.delete()
            }
            rulesFile.createNewFile()

            //read R and get all the resource types we require for this library.
            Collection<String> resTypesInLibrary = resourceTypesFromRFile(r)
            if (resTypesInLibrary.isEmpty()) {
                resTypesInLibrary = ["id", "layout", "drawable", "dimen", "color", "integer", "string", "attr", "bool", "color", "style"]
            }
            resTypesInLibrary.each { str ->
                rulesFile << "rule ${libraryPackageName}.R" + '**' + "${str} ${this.packageName}.R" + '@1' + "${str}\n"
            }
            project.logger.debug("Starting jarjar... for ${data.project.path} using ${rulesFile} and ${classesJar}")
            main.process(rulesFile, classesJar, new File(dest, "${data.project.name}-classes.jar"))
            project.logger.debug("jarjar finished for ${data.project.path}")
        }
    }

    static Collection<String> resourceTypesFromRFile(File rFile) {
        rFile.readLines().collect { line ->
            line.split(" ")[1]
        }.toSet()
    }

    List<Task> generateDirectoriesCopyTask(File mainDirectory, List<File> secondaryDirectories, List<String> taskNames, List<Task> explodingTasks) {
        Copy copyMain = (Copy)project.tasks.create("proguardMultiLibCopyLibraryExplodedFiles", Copy)
        copyMain.from(mainDirectory)
        copyMain.into(destLibrary)
        copyMain.exclude("AndroidManifest.xml", "proguard.txt", "annotations.zip", "classes.jar")
        copyMain.dependsOn(explodingTasks)
        def asd = secondaryDirectories.findAll { d -> d.absolutePath != mainDirectory.absolutePath }.collect() { f ->
            Copy copyEverythingElse = (Copy)project.tasks.create("proguardMultiLibCopyFiles${taskNames.get(secondaryDirectories.indexOf(f))}", Copy)
            copyEverythingElse.exclude("AndroidManifest.xml", "proguard.txt", "annotations.zip", "classes.jar", "**/values**/*.xml")
            copyEverythingElse.from(f)
            copyEverythingElse.into(destLibrary)

            Task mergeXml = project.tasks.create("proguardMultiLibMergeValues${taskNames.get(secondaryDirectories.indexOf(f))}")
            mergeXml.doLast {
                ConfigurableFileTree files = project.fileTree(f)
                files.include("**/values**/*.xml")
                files.collect().each { valuesFile ->
                    project.logger.warn("directory from - ${valuesFile.parent}, filename - ${valuesFile.name}")
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
