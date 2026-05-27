plugins {
    id("qupath-conventions")
}

qupathExtension {
    name = "qupath-extension-mif-merge"
    group = "qupath.ext.mifmerge"
    version = "0.1.0-SNAPSHOT"
    description = "Multi-channel qptiff registration (two-stage SIFT on DAPI) and merged pyramidal OME-TIFF export."
    automaticModule = "qupath.ext.mifmerge"
}

// ------------------------------------------------------------------------
// Dependencies
// ------------------------------------------------------------------------
dependencies {
    // QuPath core — needed at compile time (and at runtime when the extension
    // is loaded by a QuPath install, which provides these jars).
    implementation(libs.bundles.qupath)
    implementation(libs.qupath.fxtras)
    compileOnly("io.github.qupath:qupath-extension-bioformats:0.7.0")

    // bytedeco OpenCV is pulled in transitively by qupath-core-processing
    // (via libs.bundles.opencv). Declaring it explicitly makes the dependency
    // obvious — it provides SIFT in opencv_features2d.
    implementation(libs.bundles.opencv)

    // Bio-Formats — used directly by the standalone CLIs (runVerify /
    // runRegister) so they can open qptiff without launching QuPath. Inside
    // a real QuPath install, BioFormatsImageServer uses the same library.
    implementation("ome:formats-gpl:8.5.0") {
        exclude(group="xalan", module="serializer")
        exclude(group="xalan", module="xalan")
        exclude(group="io.minio", module="minio")
        exclude(group="commons-codec", module="commons-codec")
        exclude(group="commons-logging", module="commons-logging")
        exclude(group="com.google.code.findbugs", module="jsr305")
        exclude(group="com.google.code.findbugs", module="annotations")
    }

    // SLF4J + a backend so the CLI tasks produce logs when run via ./gradlew.
    runtimeOnly("ch.qos.logback:logback-classic:1.5.6")
}

// Bio-Formats lives on the OME and SciJava repos; the qupath-extension-settings
// plugin only adds SciJava, which proxies most of it. Add OME directly to be safe.
repositories {
    maven("https://artifacts.openmicroscopy.org/artifactory/maven/")
    maven("https://maven.scijava.org/content/groups/public/")
}

// ------------------------------------------------------------------------
// CLI tasks (run via ./gradlew or gradlew.bat on Windows)
// ------------------------------------------------------------------------

// runVerify: compare Java SIFT against Python golden matrices on DAPI PNGs
val verifyCaseDir = providers.gradleProperty("caseDir")

tasks.register<JavaExec>("runVerify") {
    group = "verification"
    description = "Run the Java SIFT+RANSAC pipeline on dapi_fixed.png / dapi_moving.png " +
            "and compare to the Python golden matrices in <caseDir>. " +
            "Required: -PcaseDir=/path/to/registrations_batch/<case>"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "qupath.ext.mifmerge.verify.VerifyAgainstPython"
    argumentProviders.add(CommandLineArgumentProvider { listOf(verifyCaseDir.get()) })
    systemProperty("org.bytedeco.javacpp.logger", "slf4jlogger")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "4g"
}

// runRegister: register two qptiffs end-to-end and save matrix JSON / txt / QC PNGs
val regFixedPath = providers.gradleProperty("fixedPath")
val regMovingPath = providers.gradleProperty("movingPath")
val regOutDir = providers.gradleProperty("outDir")

tasks.register<JavaExec>("runRegister") {
    group = "verification"
    description = "Register a moving qptiff to a fixed qptiff and save matrix + QC PNGs. " +
            "Required: -PfixedPath=... -PmovingPath=... -PoutDir=..."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "qupath.ext.mifmerge.verify.RegisterCli"
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(regFixedPath.get(), regMovingPath.get(), regOutDir.get())
    })
    systemProperty("org.bytedeco.javacpp.logger", "slf4jlogger")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "8g"
}

// runMerge: register N qptiffs to the first one and write a merged pyramidal OME-TIFF.
// Required: -PinputPaths=path1,path2,path3,... -PoutPath=merged.ome.tif
val mergeInputs = providers.gradleProperty("inputPaths")
val mergeOutPath = providers.gradleProperty("outPath")

tasks.register<JavaExec>("runMerge") {
    group = "verification"
    description = "Register N qptiffs to the first one (fixed) and write a merged pyramidal OME-TIFF. " +
            "Required: -PinputPaths=fixed.qptiff,moving1.qptiff[,moving2.qptiff...] -PoutPath=merged.ome.tif"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "qupath.ext.mifmerge.verify.MergeCli"
    argumentProviders.add(CommandLineArgumentProvider {
        val inputs = mergeInputs.get().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        inputs + listOf("--out", mergeOutPath.get())
    })
    systemProperty("org.bytedeco.javacpp.logger", "slf4jlogger")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "12g"
}
