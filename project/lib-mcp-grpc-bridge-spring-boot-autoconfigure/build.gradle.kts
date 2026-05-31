import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("io.github.cgardev.gradle.common.spring-kotlin-conventions")
    id("io.github.cgardev.gradle.common.publishing-conventions")
}

description = "Spring Boot auto-configuration starter for the MCP-gRPC bridge: exposes a " +
    "GrpcMcpToolFactory bean so consumer configurations can define MCP tools."

dependencies {
    // The framework-agnostic bridge; re-exported so importing this module alone brings everything.
    api(project(":project:lib-mcp-grpc-bridge"))

    implementation("org.springframework.boot:spring-boot-autoconfigure")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
    publications.named<MavenPublication>("maven") {
        artifactId = "mcp-grpc-bridge-spring-boot-autoconfigure"
    }
}
