/*
 * VariantDataCollector.groovy
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

import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.LibraryVariant
import com.sagiantebi.multilib.AndroidMultiLibProguardExtension
import org.gradle.api.Project
import org.gradle.api.Task;

/**
 * Utility which gathers all needed infromation from a target project.
 */

public class VariantDataCollector {

    /**
     * Iterates the list of projects, resolving the needed data for the plugin to function.
     * @param wrappedProjectList a list of projects
     * @return a list of CollectedVariantData for the target projects.
     */
    public static List<CollectedVariantData> resolveAllTargets(List<AndroidMultiLibProguardExtension.WrappedProject> wrappedProjectList) {
        final List<CollectedVariantData> collectedData = new ArrayList<>()
        wrappedProjectList.each { AndroidMultiLibProguardExtension.WrappedProject target ->
            Project prj = target.getTargetProject()
            LibraryExtension extension = null
            try {
                extension = prj.extensions.getByType(LibraryExtension)
            } catch (Exception e) {
                prj.getLogger().warn("Exception raised attempting to read the Android library extension in the project " + prj)
            }
            if (extension != null) {
                CollectedVariantData info = findAndroidLibraryArtifact(target.getOptions(), extension, prj)
                if (info != null) {
                    collectedData.add(info)
                }
            }
        }
        return collectedData
    }

    /**
     * Finds the android library information given the provided options and android library extension
     * @param options The options supplied to the plugin using {@link AndroidMultiLibProguardExtension}
     * @param extension The android library extension we found
     * @param project The project which contains the Android extension.
     * @return The relevant variant data
     */
    private static CollectedVariantData findAndroidLibraryArtifact(AndroidMultiLibProguardExtension.ProjectOptions options, final LibraryExtension extension, final Project project) {
        CollectedVariantData info = null
        def libraryVariants = extension.libraryVariants.matching { LibraryVariant variant ->
            return (options.flavorName == null || variant.flavorName == options.flavorName) &&
                    ((!options.release && variant.buildType.isDebuggable()) || (options.release && !variant.buildType.isDebuggable()))
        }
        if (libraryVariants.size() > 0) {
            LibraryVariant matched = libraryVariants[0]
            File artifactOutput = new File(matched.packageLibraryProvider.getOrNull().destinationDirectory.getOrNull().asFile, matched.packageLibraryProvider.getOrNull().archiveFileName.getOrNull())
            Task assemble = matched.assembleProvider.get()
            project.logger.debug(project.path + " : target library - " + artifactOutput)
            if (artifactOutput != null && assemble != null) {
                info = new CollectedVariantData(artifactOutput, assemble, matched, extension, project)
            }
        }
        return info
    }

    /**
     * A simple struct which holds basic information about an android project
     */
    public static class CollectedVariantData {
        /**
         * The library output file (.aar)
         */
        public final File outputFile
        /**
         * The assemble task which outputs the library artifact
         */
        public final Task assembleTask
        /**
         * The android variant
         */
        public final LibraryVariant androidVariant
        /**
         * The extension in the project
         */
        public final LibraryExtension androidExtension
        /**
         * The project itself
         */
        public final Project project

        private CollectedVariantData(File file, Task task, LibraryVariant variant, LibraryExtension extension, Project project) {
            outputFile = file
            assembleTask = task
            androidVariant = variant
            androidExtension = extension
            this.project = project
        }
    }

}
