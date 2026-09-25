plugins { `java-gradle-plugin` }
dependencies {
    implementation(project(":core"))
    compileOnly("com.android.tools.build:gradle-api:8.7.3")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    testImplementation("com.android.tools.build:gradle-api:8.7.3")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.ow2.asm:asm:9.7.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
gradlePlugin {
    plugins {
        create("flowLens") {
            id = "dev.flowlens.android"
            implementationClass = "dev.flowlens.gradle.FlowLensPlugin"
            displayName = "Android Flow Lens"
            description = "Pre-obfuscation bytecode flow and bounded taint analysis"
        }
    }
}
tasks.register<Jar>("standaloneJar") {
    archiveBaseName.set("flow-lens-gradle")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class", "META-INF/versions/**")
}
