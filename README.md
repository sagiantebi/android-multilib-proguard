# android-multilib-proguard

[![Build Status](https://travis-ci.org/sagiantebi/android-multilib-proguard.svg?branch=master)](https://travis-ci.org/sagiantebi/android-multilib-proguard)

A Gradle plugin which automates the procedures required to apply proguard to multiple android library projects.

## Features
  - Reference multiple 'android-library' projects
  - Automatic injestion of proguard rules from dependencies (consumer rules)
  - Generates common proguard rules using AAPT and applies them automatically.
  - Can deal with flavors or debug builds (configureable)

## Usage

add `android-multlib-proguard` to your build script's dependepcies -
```groovy
classpath 'com.sagiantebi:android-multilib-proguard:0.9'
```
configure the plugin as you like -
```groovy
androidMultilibProguard {
    //no flavors, and the release build.
    addProject project(':mainlibrary')
    //custom configuration using a closure
    addProject(project(':submodule-one')) {
        flavorName "stub"
        release false
    }
    //add the files containing the proguard rules
    proguardFiles project(':mainlibrary').file("proguard-rules.pro"), project(':submodule-one').file("proguard-rules.pro")
    //give the name of the android proguard file you would like (optional)
    androidProguardFile 'proguard-android.txt'
}
```

now you can simple run the task `proguardMultiLib` which will output the following onto the buildscript's `build/multilibpg-outputs` directory -
- external-proguard-files - contains the files which we created/found (consumer files from aar, aapt rules)
- libs - the untouched libraries (without proguard applied)
- libs-proguard - the libraries after a proguard run
- proguard - the proguard output files (mapping, seeds, usage and the configuration used)

## Limitations

Currently the plugin can't handle any custom gradle configurations, and only knows how to quesry the android build plugin for the common configuration (provided / compile)

## License

MIT

newline
