/*
 * CollectProguardFilesTask.groovy
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


import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.FileTree
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.util.PatternSet

/**
 * A Task to copy all proguard files from compiled AARs
 */

public class CollectProguardFilesTask extends DefaultTask {

    public List<File> inputAARs = new ArrayList<>()

    public File workingDirectory;

    public CollectProguardFilesTask() {
        super()
    }

    @TaskAction
    public void copyFiles() {
        List<SimpleCopyAction> simpleCopyActions = new ArrayList<>()
        Project project = getProject()
        inputAARs.each { File file ->
            FileTree files = project.zipTree(file).matching { PatternSet patternSet ->
                patternSet.setIncludes(["proguard.txt"])
            }
            if (files.files.size() > 0) {
                File target = files.singleFile
                SimpleCopyAction simpleCopyAction = new SimpleCopyAction(target, "${file.name.replace(".aar", "")}-proguard-rules.txt");
                simpleCopyActions.add(simpleCopyAction)
            }
        }
        simpleCopyActions.each { SimpleCopyAction action ->
            project.copy {
                from action.target
                into workingDirectory
                rename("proguard.txt", action.name)
            }
        }
    }

    /**
     * A struct to hold copy instructions so we can do these in a single go
     */
    private static class SimpleCopyAction {

        private final File target
        private final String name

        private SimpleCopyAction(File target, String name) {
            this.target = target
            this.name = name
        }

    }


}
