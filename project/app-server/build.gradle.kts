import com.google.protobuf.gradle.id

plugins {
    id("io.github.cgardev.gradle.common.kotlin-conventions")
    application
    id("com.google.protobuf") version "0.9.4"
}

val grpcVersion = "1.69.0"
val protobufVersion = "4.28.3"
val grpcKotlinVersion = "1.4.1"
val coroutinesVersion = "1.9.0"

dependencies {
    // The MCP-gRPC bridge (turns service methods into MCP tools) and the Netty MCP server.
    implementation(project(":project:lib-mcp-grpc-bridge"))
    implementation(project(":project:lib-mcp-server"))

    // gRPC service definitions generated from the demo proto, for the Kotlin coroutine server API.
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("com.google.protobuf:protobuf-kotlin:$protobufVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    // Supplies javax.annotation.Generated referenced by the generated gRPC stubs.
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")

    // A simple SLF4J binding so the server logs to the console when run.
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.github.cgardev.example.MainKt")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
                id("grpckt")
            }
            task.builtins {
                id("kotlin")
            }
        }
    }
}

// The Kotlin sources reference the generated gRPC service stubs, so code generation
// must complete before Kotlin compilation begins.
tasks.named("compileKotlin") {
    dependsOn("generateProto")
}
