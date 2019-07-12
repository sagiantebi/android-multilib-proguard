/*
 * AndroidMultiLibProguardPlugin.groovy
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

package com.sagiantebi.multilib

import com.sagiantebi.multilib.tasks.SimpleTasksCreator
import com.sagiantebi.multilib.tasks.SingleFileTaskCreator
import com.sagiantebi.multilib.util.VariantDataCollector
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.internal.file.archive.ZipFileTree

import org.gradle.api.tasks.Copy

class AndroidMultiLibProguardPlugin implements Plugin<Project> {

    static final String PARENT_BUILD_DIR = "multilibpg-outputs"

    private Project project

    private File workingDirectory

    private List<AndroidMultiLibProguardExtension.WrappedProject> wrappedProjectList = new ArrayList<>();

    @Override
    void apply(Project project) {
        this.project = project
        createProjectExtension(project)

        workingDirectory = new File(project.getBuildDir(), PARENT_BUILD_DIR)
        if (!workingDirectory.exists()) {
            workingDirectory.mkdirs()
        }

        project.afterEvaluate {
            if (project.extensions.findByType(AndroidMultiLibProguardExtension))
            wrappedProjectList.addAll(project.androidMultilibProguard.targets)
            configureTasks()
        }

    }

    void configureTasks() {
        SimpleTasksCreator taskCreator = new SimpleTasksCreator(project, workingDirectory)
        final List<VariantDataCollector.CollectedVariantData> collectedData = VariantDataCollector.resolveAllTargets(wrappedProjectList)
        if (collectedData.size() > 0) {
            Task copy = taskCreator.createCopyTask(collectedData)
            List<File> proguardConfigs = project.androidMultilibProguard.getProguardConfigurationFiles()
            if (proguardConfigs.size() > 0) {
                Task prepareProguardMultiLib = taskCreator.createCollectProguardFilesTask(collectedData)
                Task proguardTask;
                if (project.androidMultilibProguard.isSingleFileMode()) {
                    if (project.androidMultilibProguard.getSingleFileFinalPackageName() == null || project.androidMultilibProguard.getSingleFileFinalPackageName().isEmpty()) {
                        throw new IllegalArgumentException("singleFileMode should be used in conjunction with singleFileFinalPackageName")
                    }
                    SingleFileTaskCreator singleFileTaskCreator = new SingleFileTaskCreator(project, workingDirectory, project.androidMultilibProguard.getSingleFileFinalPackageName());
                    proguardTask = singleFileTaskCreator.generateProguardTask(taskCreator, collectedData)
                } else {
                    proguardTask = taskCreator.createProguardTask(copy.outputs.files.asPath, collectedData, null)
                }

                //create the task graph as the following -
                //copy -> prepareProguard -> createProguardConfig -> proguard
                proguardTask.dependsOn prepareProguardMultiLib
                prepareProguardMultiLib.dependsOn copy
            }
        }
    }

    void createProjectExtension(Project project) {
        project.extensions.create("androidMultilibProguard", AndroidMultiLibProguardExtension.class, this.project)
    }


}