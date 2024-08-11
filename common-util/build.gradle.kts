import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

dependencies {
    compileOnly(project(":common"))
    api("org.reflections:reflections:0.10.2") {
        exclude("org.slf4j")
    }
}

tasks {
    withType<ShadowJar> {
        archiveClassifier.set("")
        relocate("org.reflections", "taboolib.library.reflections")
        relocate("javassist", "taboolib.library.javassist")
    }
}