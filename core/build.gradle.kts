plugins { `java-library`; application }
dependencies {
    implementation("org.ow2.asm:asm:9.7.1")
    implementation("org.ow2.asm:asm-tree:9.7.1")
    implementation("org.ow2.asm:asm-analysis:9.7.1")
    implementation("org.ow2.asm:asm-util:9.7.1")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
application { mainClass.set("dev.flowlens.core.Main") }
tasks.register<Jar>("standaloneJar") {
    archiveBaseName.set("flow-lens-cli")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "dev.flowlens.core.Main" }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class", "META-INF/versions/**")
}
