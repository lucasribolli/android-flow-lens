allprojects {
    group = "dev.flowlens"
    version = "0.1.0-SNAPSHOT"
}
subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(17)
            options.encoding = "UTF-8"
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
        }
    }
}
