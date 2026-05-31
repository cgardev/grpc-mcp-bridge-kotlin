import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("io.github.cgardev.gradle.common.kotlin-conventions")
    id("io.github.cgardev.gradle.common.publishing-conventions")
}

description = "Runtime generator of JSON Schema (draft 2020-12) documents from Protobuf message " +
    "descriptors, round-trip compatible with com.google.protobuf.util.JsonFormat."

val protobufVersion = "4.28.3"
val jacksonVersion = "2.17.0"

dependencies {
    api("com.google.protobuf:protobuf-java-util:$protobufVersion")
    api("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("com.networknt:json-schema-validator:1.5.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
    publications.named<MavenPublication>("maven") {
        artifactId = "protobuf-jsonschema"
    }
}
