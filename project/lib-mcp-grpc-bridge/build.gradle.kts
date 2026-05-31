import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("io.github.cgardev.gradle.common.kotlin-conventions")
    id("io.github.cgardev.gradle.common.publishing-conventions")
}

description = "Bridges gRPC service methods to MCP tools: derives each tool's JSON Schema from the " +
    "proto request descriptor and dispatches MCP tool calls to the gRPC handler in-process."

val mcpSdkVersion = "0.14.1"
val protobufVersion = "4.28.3"
val jacksonVersion = "2.17.0"
val coroutinesVersion = "1.9.0"

dependencies {
    // Generates each tool's input schema from the proto request descriptor.
    api(project(":project:lib-protobuf-jsonschema"))

    // MCP SDK: the stateless tool-specification and schema types this factory produces.
    api("io.modelcontextprotocol.sdk:mcp:$mcpSdkVersion")

    // Proto <-> JSON conversion for tool arguments and responses.
    api("com.google.protobuf:protobuf-java-util:$protobufVersion")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
    publications.named<MavenPublication>("maven") {
        artifactId = "mcp-grpc-bridge"
    }
}
