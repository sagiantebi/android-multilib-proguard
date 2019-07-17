# android-multilib-proguard

[![Build Status](https://travis-ci.org/sagiantebi/android-multilib-proguard.svg?branch=master)](https://travis-ci.org/sagiantebi/android-multilib-proguard)

A Gradle plugin which automates the procedures required to apply proguard to multiple android library projects.

## Features
  - Single file output (FAT AAR) using a combination of Proguard and JarJar Links (The [Pants Build](https://github.com/pantsbuild/jarjar) fork)
  - Single file output can also embed external maven dependencies (see Limitations) in the final generated AAR
  - The Java package name for external dependencies can also be renamed to avoid conflicts 
  - Libraries will keep mutual references after a Proguard pass using this plugin
  - Reference multiple 'android-library' projects
  - Automatic injestion of proguard rules from dependencies (consumer rules)
  - Generates common proguard rules using AAPT and applies them automatically.
  - configureable - can use flavors or debug builds

## Usage

add `android-multlib-proguard` to your build script's dependepcies -
```groovy
classpath 'com.sagiantebi:android-multilib-proguard:0.11'
```
configure the plugin as you like -
```groovy
apply plugin: 'android-multilib-proguard'

androidMultilibProguard {
    //no flavors, and the release build.
    addProject project(':mainlibrary')
    //custom configuration using a closure
    addProject(project(':submodule-one')) {
        flavorName "stub"
        release false
    }
    setSingleFileMode(true) //true for a far aar, false for multiple aars
    setSingleFileFinalPackageName("org.one.of.the.added.projects.package.name") //when setSingleFileMode is set to true this must be provided
    //include external depedencies in single file mode -
    addDependency("com.google.code.gson:gson:2.8.1") {
        renameFrom = "com.google.gson"
        renameTo = "com.fyber.external.google.gson"
    }
    //add the files containing the proguard rules
    proguardFiles project(':mainlibrary').file("proguard-rules.pro"), project(':submodule-one').file("proguard-rules.pro")
    //give the name of the android proguard file you would like (optional)
    androidProguardFile 'proguard-android.txt'
}
```

## Requirements

- Gradle v5.4
- Android's Gradle build plugin v3.4
- Tested using build tools 28.x.x but should work with 26.x.x and 27.x.x

## Usage

now you can simply run the task `proguardMultiLib` which will output the following onto the buildscript's `build/multilibpg-outputs` directory -
- external-proguard-files - contains the proguard config files which we created/found (consumer files from aar, aapt rules)
- libs - the original-untouched libraries (without proguard applied)
- libs-proguard - the libraries or the single fat aar produced after running proguard.
- proguard - the proguard output files (mapping, seeds, usage and the generated configuration file)

## Limitations

- Single library mode (FAT AAR) _might_ not work with 
    - renderscript, aidl or native (.so) JNI libs - largely untested.
    - when the gradle projects are referencing each other as `implementation` or `api`. only `compileOnly` has been proven working.
    - overlapping annotations between libraries might have issues. the annotation merger at it's current state is quite primitive
    - You need to include relevant proguard rules for the embedded libraries (if any)
    - Embedding an external maven AAR in a single file should be tested thoroughly.

## License

MIT
