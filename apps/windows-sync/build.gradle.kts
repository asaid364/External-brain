plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.jmdns:jmdns:3.6.0")
    implementation("net.java.dev.jna:jna:5.15.0")
    implementation("net.java.dev.jna:jna-platform:5.15.0")
    implementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(compose.desktop.uiTestJUnit4)
}
compose.desktop {
    application {
        mainClass = "app.liferecord.desktop.MainKt"
        nativeDistributions {
            packageName = "LifeRecord"
            packageVersion = "1.3.0"
            includeAllModules = true
        }
    }
}

// Acceptance drivers are deliberately excluded from the distributed application.
val smoke by sourceSets.creating
configurations[smoke.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[smoke.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())
dependencies {
    add(smoke.implementationConfigurationName, "com.squareup.okhttp3:okhttp:4.12.0")
}
smoke.compileClasspath += sourceSets.main.get().output
smoke.runtimeClasspath += sourceSets.main.get().output
tasks.register<JavaExec>("goalFeatureSmoke") {
    group = "verification"
    description = "Isolated synthetic goal HTTPS/DPAPI/sync acceptance; requires an explicitly selected private interface."
    classpath = smoke.runtimeClasspath
    mainClass.set("app.liferecord.desktop.GoalFeatureSmokeKt")
    providers.gradleProperty("goalSmokeInterface").orNull?.let { args(it) }
}
tasks.register<JavaExec>("goalDesktopSmoke") {
    group = "verification"
    description = "Robot acceptance against the existing real app-image, never a production data directory."
    classpath = smoke.runtimeClasspath
    mainClass.set("app.liferecord.desktop.GoalDesktopSmokeKt")
    args(layout.buildDirectory.file("compose/binaries/main/app/LifeRecord/LifeRecord.exe").get().asFile.absolutePath)
    if (providers.gradleProperty("goalDesktopInteractive").orNull == "true") {
        args("--interactive")
        standardInput = System.`in`
    }
}
tasks.register("goalSmokeLaunchFiles") {
    group = "verification"
    description = "Writes resolved smoke classpath and Java argument file for direct interactive launch (does not launch processes)."
    val classpathFile = layout.buildDirectory.file("smoke/runtime-classpath.txt")
    val argumentsFile = layout.buildDirectory.file("smoke/desktop-interactive.args")
    outputs.files(classpathFile, argumentsFile)
    doLast {
        val classpath = smoke.runtimeClasspath.asPath
        classpathFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(classpath, Charsets.UTF_8)
        }
        fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        argumentsFile.get().asFile.writeText(listOf(
            "-cp", quoted(classpath), "app.liferecord.desktop.GoalDesktopSmokeKt",
            quoted(layout.buildDirectory.file("compose/binaries/main/app/LifeRecord/LifeRecord.exe").get().asFile.absolutePath),
            "--interactive",
        ).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        println("GOAL_SMOKE_CLASSPATH_FILE=${classpathFile.get().asFile.absolutePath}")
        println("GOAL_SMOKE_JAVA_ARGS_FILE=${argumentsFile.get().asFile.absolutePath}")
    }
}

tasks.register<JavaExec>("goalAndroidHost") {
    group = "verification"
    description = "Operator-controlled isolated HTTPS host for the opt-in Android GoalSyncIntegrationTest; never auto-approves."
    classpath = smoke.runtimeClasspath
    mainClass.set("app.liferecord.desktop.GoalAndroidHostKt")
    workingDir = projectDir
    standardInput = System.`in`
    providers.gradleProperty("goalSmokeInterface").orNull?.let { args(it) }
    providers.gradleProperty("goalSmokeNetworkHandle").orNull?.let { args(it) }
    if (providers.gradleProperty("goalAndroidFileControl").orNull == "true") args("--file-control")
}
