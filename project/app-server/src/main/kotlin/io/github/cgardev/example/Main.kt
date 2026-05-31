package io.github.cgardev.example

import io.github.cgardev.calculator.v1.BinaryOp
import io.github.cgardev.library.mcp.grpc.bridge.GrpcMcpToolFactory
import io.github.cgardev.library.mcp.server.McpServerBuilder
import java.util.concurrent.CountDownLatch

/**
 * Runs the demonstration MCP server. It wraps each method of [CalculatorService] as an MCP tool and
 * serves them over the Netty-based MCP Streamable HTTP transport. Set the `MCP_PORT` environment
 * variable to override the default port.
 */
fun main() {
    val service = CalculatorService()
    val factory = GrpcMcpToolFactory()

    val tools = listOf(
        factory.create("add", "Add two numbers (a + b).", BinaryOp.getDefaultInstance(), service::add),
        factory.create("subtract", "Subtract b from a (a - b).", BinaryOp.getDefaultInstance(), service::subtract),
        factory.create(
            "divide",
            "Divide a by b (a / b); b must be non-zero.",
            BinaryOp.getDefaultInstance(),
            service::divide,
        ),
    )

    val port = System.getenv("MCP_PORT")?.toIntOrNull() ?: 3000
    val server = McpServerBuilder.build(
        port = port,
        serverName = "cgardev-calculator",
        serverVersion = "0.1.0",
        tools = tools,
        instructions = "A demonstration MCP server that proxies a gRPC CalculatorService.",
        instanceName = "calculator",
    )

    server.start()
    println("MCP calculator server listening on http://localhost:${server.boundPort}/")

    val latch = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.close()
            latch.countDown()
        },
    )
    latch.await()
}
