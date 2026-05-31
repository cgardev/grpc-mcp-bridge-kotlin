# grpc-mcp-bridge-kotlin

Expose your existing **gRPC services as [MCP](https://modelcontextprotocol.io) tools** — and
serve them **natively from the JVM on Netty**. Each proto method becomes a tool whose input
schema is derived from its request descriptor; an incoming tool call is parsed into the proto
request and dispatched to your handler in-process. No sidecar, no servlet container, no Spring
in the core.

[![CI](https://github.com/cgardev/grpc-mcp-bridge-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/cgardev/grpc-mcp-bridge-kotlin/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/cgardev/grpc-mcp-bridge-kotlin.svg)](https://jitpack.io/#cgardev/grpc-mcp-bridge-kotlin)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![JDK](https://img.shields.io/badge/JDK-24-orange.svg)](https://adoptium.net)
[![Status: alpha](https://img.shields.io/badge/status-alpha-orange.svg)](#disclaimer)

> [!WARNING]
> **Alpha.** This project is in early, active development. The public API, wire behaviour and
> module layout may change without notice between releases, and it has not been hardened for
> production use. Pin an exact version and review the release notes before upgrading. See the
> [disclaimer](#disclaimer).

## Why

[MCP](https://modelcontextprotocol.io) lets models call tools, and most JVM backends already
expose their domain logic over gRPC. Hand-writing an MCP tool for each RPC — its JSON Schema, its
argument parsing, its response serialisation — is repetitive and drifts from the proto definitions
over time.

`grpc-mcp-bridge-kotlin` removes that boilerplate. It turns a gRPC service method into an MCP tool
automatically, keeping the tool contract in lock-step with the proto, and serves the result over an
**embedded Netty** HTTP transport — the same no-servlet, no-framework approach as
[`connect-kotlin-server`](https://github.com/cgardev/connect-kotlin-server).

## How it works

The bridge spans both directions:

- **gRPC → MCP (exposure).** [`ProtoJsonSchema`](project/lib-protobuf-jsonschema) converts the proto
  request descriptor into a JSON Schema (draft 2020-12) that round-trips with
  `com.google.protobuf.util.JsonFormat`. That schema becomes the tool's `inputSchema`, so the model
  sees exactly the fields the proto declares.
- **MCP → gRPC (invocation).** When the model calls the tool, `GrpcMcpToolFactory` parses the JSON
  arguments into the typed proto request, invokes the gRPC handler (a `suspend` function) in-process,
  and serialises the proto response back to JSON as the tool result.

The MCP runtime is the [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk) in its
**stateless** mode — every request is a self-contained JSON-RPC exchange, which is the right model
for a tool proxy — hosted on a small embedded **Netty** HTTP/1.1 server. Blocking dispatch runs on a
virtual-thread executor off the event loop.

## Features

- **Automatic tool schemas:** input schema generated from the proto descriptor; no hand-written JSON.
- **In-process dispatch:** tool calls invoke the gRPC handler directly — no gRPC network hop.
- **No framework lock-in:** the core modules have **zero dependency on Spring or the Servlet API**.
  Netty is the HTTP server. An optional Spring Boot starter is provided for those who want it.
- **Pluggable context propagation:** a `McpCallContextBridge` hook lets you carry per-request state
  (for example an authenticated principal published by an upstream proxy) into the gRPC handler.
- **Faithful proto/JSON mapping:** well-known types, `oneof`, maps, enums, 64-bit integers and
  `NaN`/`Infinity` are all handled as `JsonFormat` does.

## Requirements

- JDK 24+
- gRPC services generated with `grpc-kotlin` (any coroutine `suspend` method works as a tool target)

## Installation

> [!IMPORTANT]
> There are **no published releases yet**. While the project is in alpha, it is versioned **by
> commit** — depend on a specific commit and treat every commit as potentially breaking.

Pull a specific commit through [JitPack](https://jitpack.io):

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    // Replace <commit> with the exact short commit SHA you want to pin to.
    implementation("com.github.cgardev.grpc-mcp-bridge-kotlin:mcp-grpc-bridge:<commit>")
    implementation("com.github.cgardev.grpc-mcp-bridge-kotlin:mcp-server:<commit>")
}
```

## Quick start

Wrap your service methods as tools and host them — no Spring required:

```kotlin
import io.github.cgardev.library.mcp.grpc.bridge.GrpcMcpToolFactory
import io.github.cgardev.library.mcp.server.McpServerBuilder

fun main() {
    val service = CalculatorService()                 // a grpc-kotlin coroutine service
    val factory = GrpcMcpToolFactory()

    val tools = listOf(
        factory.create("add", "Add two numbers.", BinaryOp.getDefaultInstance(), service::add),
        factory.create("divide", "Divide a by b.", BinaryOp.getDefaultInstance(), service::divide),
    )

    val server = McpServerBuilder.build(
        port = 3000,
        serverName = "cgardev-calculator",
        serverVersion = "0.1.0",
        tools = tools,
    )
    server.start()                                    // binds Netty, serves MCP Streamable HTTP at /
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
}
```

### With Spring Boot

The `mcp-grpc-bridge-spring-boot-autoconfigure` starter exposes a `GrpcMcpToolFactory` bean so you
can inject it into your tool configuration; you still build and host the MCP server where you want:

```kotlin
dependencies {
    implementation("com.github.cgardev.grpc-mcp-bridge-kotlin:mcp-grpc-bridge-spring-boot-autoconfigure:<commit>")
}
```

```kotlin
@Configuration
class McpTools(private val factory: GrpcMcpToolFactory, private val service: CalculatorService) {
    @Bean
    fun calculatorTools() = listOf(
        factory.create("add", "Add two numbers.", BinaryOp.getDefaultInstance(), service::add),
    )
}
```

The bean backs off if you declare your own `GrpcMcpToolFactory` — useful for supplying a custom
`McpCallContextBridge`.

## Trying the example

The `:project:app-server` module exposes a demo `CalculatorService` over MCP:

```bash
./gradlew :project:app-server:run        # serves on http://localhost:3000/

# Initialize, then call a tool (MCP Streamable HTTP, single JSON-RPC request per POST):
curl -s localhost:3000/ \
  -H 'Content-Type: application/json' -H 'Accept: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
       "params":{"name":"add","arguments":{"a":2,"b":3}}}'
# => the CalcResult serialised as JSON: {"value":5.0}
```

## Project layout

```
project/lib-protobuf-jsonschema/                       Proto descriptor -> JSON Schema (draft 2020-12). Pure, no framework.
project/lib-mcp-server/                                Embedded Netty MCP Streamable HTTP server. Servlet-free, Spring-free.
project/lib-mcp-grpc-bridge/                            Turns gRPC methods into MCP tools (the bridge core).
project/lib-mcp-grpc-bridge-spring-boot-autoconfigure/ Optional Spring Boot starter (a GrpcMcpToolFactory bean).
project/app-server/                                    Runnable example: a gRPC CalculatorService exposed as MCP tools.
build-logic/                                           Gradle convention plugins.
```

## Building

```bash
./gradlew build      # compile + test every module
```

## Versioning & compatibility

During alpha there are **no published releases and no version numbers** — the commit **is** the
version. Pin your dependency to an exact commit and upgrade deliberately; source, binary and wire
behaviour may change between any two commits. The Gradle version stays at `0.0.0-SNAPSHOT`.

## Publishing

There are no tagged releases yet, so consumers use JitPack by commit (see
[Installation](#installation)); JitPack builds the requested commit per [`jitpack.yml`](jitpack.yml).
The build is also set up to publish via `maven-publish` to GitHub Packages (out of the box in CI) or
to Maven Central under the `io.github.cgardev` group through the
[Sonatype Central Portal](https://central.sonatype.com). See
[`.github/workflows/publish.yml`](.github/workflows/publish.yml).

## Disclaimer

This is **alpha software, provided "as is"**, without warranty of any kind, express or implied. To
the maximum extent permitted by applicable law, the authors and contributors shall **not be liable**
for any claim, damages, data loss or other liability arising from, out of or in connection with the
software or its use. **You use it at your own risk.** See Sections 7 and 8 of the
[Apache License 2.0](LICENSE) for the full terms.

## License

[Apache License 2.0](LICENSE). Not affiliated with or endorsed by the Model Context Protocol project,
Anthropic, or the gRPC project.
