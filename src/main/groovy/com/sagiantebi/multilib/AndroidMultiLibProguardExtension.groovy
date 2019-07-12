/*
 * AndroidMultiLibProguardExtension.groovy
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

import org.gradle.api.Project

class AndroidMultiLibProguardExtension {

    /**
     * The project which configured us
     */
    private Project project

    /**
     * Proguard configuration file or files.
     */
    private List<File> proguardConfigurationFiles = new ArrayList<>()

    /**
     * The name of the default android proguard file, or none
     */
    private String androidProguardFileName = null;

    /**
     * If set to true, will attempt to create a single library from the input projects
     */
    private boolean singleFileMode = false;

    /**
     * If {@ref singleFileMode} is set to true, this must contain the target packagename for the final library.<br/>
     * This will also help us find the main manifest which will be used for the manifest merger.
     */
    private String singleFileFinalPackageName;

    AndroidMultiLibProguardExtension(Project project) {
        this.project = project
    }

    List<File> getProguardConfigurationFiles() {
        return proguardConfigurationFiles
    }

    public void proguardFiles(File...files) {
        files.each { file ->
            proguardConfigurationFiles.add(file)
        }
    }

    public boolean isSingleFileMode() {
        return singleFileMode
    }

    public void setSingleFileMode(boolean singleFileMode) {
        this.singleFileMode = singleFileMode
    }

    String getSingleFileFinalPackageName() {
        return singleFileFinalPackageName
    }

    void setSingleFileFinalPackageName(String singleFileFinalPackageName) {
        this.singleFileFinalPackageName = singleFileFinalPackageName
    }

    List<WrappedProject> getTargets() {
        return targets
    }

    private List<WrappedProject> targets = new ArrayList<>()

    public ProjectOptions addProject(Project project) {
        ProjectOptions options = new ProjectOptions()
        this.targets << new WrappedProject(project, options)
        if (this.project != project) {
            this.project.evaluationDependsOn(project.path)
        }
        return options

    }

    public ProjectOptions addProject(Project project, Closure closure) {
        ProjectOptions options = new ProjectOptions()
        project.configure(options, closure)
        this.targets << new WrappedProject(project, options)
        if (this.project != project) {
            this.project.evaluationDependsOn(project.path)
        }
        return options
    }

    public String getAndroidProguardFileName() {
        return androidProguardFileName
    }

    public String androidProguardFile(String fileName) {
        this.androidProguardFileName = fileName
        return this.androidProguardFileName
    }

    static class WrappedProject {
        Project targetProject
        ProjectOptions options

        public Project getTargetProject() {
            return targetProject
        }

        WrappedProject(Project p, ProjectOptions options) {
            this.targetProject = p
            this.options = options
        }

        @Override
        String toString() {
            return super.toString() + "[project - ${targetProject}, options - ${options}]"
        }
    }

    public static class ProjectOptions {

        String flavorName = null
        boolean release = true

        public ProjectOptions flavorName(String flavorName) {
            this.flavorName = flavorName
            return this
        }

        public ProjectOptions release(boolean release) {
            this.release = release
            return this
        }

        public String getFlavorName() {
            return flavorName
        }

        public boolean getRelease() {
            return release
        }

        @Override
        String toString() {
            return super.toString() + "[flavor - ${flavorName}, release - ${release}]"
        }
    }

}
