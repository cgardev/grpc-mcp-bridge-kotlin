pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven { url = uri("https://repo.spring.io/milestone") }
    }
}

rootProject.name = "grpc-mcp-bridge-kotlin"

includeBuild("build-logic")

// Apps
include("project:app-server")

// Libs
include("project:lib-protobuf-jsonschema")
include("project:lib-mcp-server")
include("project:lib-mcp-grpc-bridge")
include("project:lib-mcp-grpc-bridge-spring-boot-autoconfigure")
