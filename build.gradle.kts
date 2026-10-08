plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.10.3"
}

group = "io.github.moalrubaie"
version = "0.1.0"

repositories { mavenCentral() }

dependencyManagement {
    imports { mavenBom("org.springframework.ai:spring-ai-bom:2.0.1") }
}

// The demo and the live agent are MCP *clients*. They get their own source set, so the
// server's classpath stays free of client and model dependencies.
val demo: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[demo.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[demo.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

dependencies {
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")

    "demoImplementation"("org.springframework.ai:spring-ai-anthropic")

    testImplementation(demo.output)
    testImplementation("org.springframework.ai:spring-ai-anthropic")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.release = 21
    options.compilerArgs.add("-parameters") // tool parameter names come from the Java names
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.register<JavaExec>("demo") {
    group = "application"
    description = "Run both servers in-process and compare them on the same tasks (no API key)."
    classpath = demo.runtimeClasspath
    mainClass = "io.github.moalrubaie.orders.demo.Demo"
}

tasks.register<JavaExec>("agent") {
    group = "application"
    description = "Let Claude use each server on the same tasks (needs ANTHROPIC_API_KEY)."
    classpath = demo.runtimeClasspath
    mainClass = "io.github.moalrubaie.orders.agent.LiveAgent"
    args = (project.findProperty("agentArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}

spotless {
    java {
        target("src/*/java/**/*.java")
        palantirJavaFormat("2.102.0")
        removeUnusedImports()
    }
}
