package io.github.cgardev.library.mcp.grpc.bridge.autoconfigure

import io.github.cgardev.library.mcp.grpc.bridge.GrpcMcpToolFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Spring Boot auto-configuration that exposes a singleton [GrpcMcpToolFactory] in the application
 * context. Consumers can then inject it into their MCP tool configurations. The bean backs off if a
 * [GrpcMcpToolFactory] is already defined, so applications can supply one with a custom
 * [io.github.cgardev.library.mcp.grpc.bridge.McpCallContextBridge].
 */
@AutoConfiguration
class McpGrpcBridgeAutoConfiguration {

    /** Singleton [GrpcMcpToolFactory] used by consumer configurations to define MCP tools. */
    @Bean
    @ConditionalOnMissingBean
    fun grpcMcpToolFactory(): GrpcMcpToolFactory = GrpcMcpToolFactory()
}
