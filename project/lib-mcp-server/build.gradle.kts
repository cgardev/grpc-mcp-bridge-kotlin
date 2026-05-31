import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("io.github.cgardev.gradle.common.kotlin-conventions")
    id("io.github.cgardev.gradle.common.publishing-conventions")
}

description = "Framework-agnostic MCP (Model Context Protocol) server: hosts the MCP Streamable " +
    "HTTP transport on an embedded Netty server, with no servlet container and no Spring dependency."

val mcpSdkVersion = "0.14.1"
val nettyVersion = "4.1.115.Final"
val reactorVersion = "3.7.0"
val slf4jVersion = "2.0.16"

dependencies {
    // The MCP Java SDK: stateless server runtime, JSON-RPC schema types and the Jackson JSON
    // mapper. Brings mcp-core, mcp-json-jackson2, Reactor and Jackson transitively.
    api("io.modelcontextprotocol.sdk:mcp:$mcpSdkVersion")

    // Netty: the embedded HTTP/1.1 transport that terminates the MCP Streamable HTTP protocol.
    api("io.netty:netty-codec-http:$nettyVersion")

    // Reactor is exposed by the MCP transport SPI (Mono<Void> closeGracefully); declared
    // explicitly because this module references it directly.
    implementation("io.projectreactor:reactor-core:$reactorVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
    publications.named<MavenPublication>("maven") {
        artifactId = "mcp-server"
    }
}
