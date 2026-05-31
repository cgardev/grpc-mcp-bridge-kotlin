package io.github.cgardev.example

import io.github.cgardev.calculator.v1.BinaryOp
import io.github.cgardev.calculator.v1.CalcResult
import io.github.cgardev.calculator.v1.CalculatorServiceGrpcKt

/**
 * A gRPC service implemented with the gRPC Kotlin coroutine API. Its `suspend` methods are exposed
 * as MCP tools by [main] through [io.github.cgardev.library.mcp.grpc.bridge.GrpcMcpToolFactory],
 * which invokes them in-process — no gRPC network hop is involved.
 */
class CalculatorService : CalculatorServiceGrpcKt.CalculatorServiceCoroutineImplBase() {

    override suspend fun add(request: BinaryOp): CalcResult =
        CalcResult.newBuilder().setValue(request.a + request.b).build()

    override suspend fun subtract(request: BinaryOp): CalcResult =
        CalcResult.newBuilder().setValue(request.a - request.b).build()

    override suspend fun divide(request: BinaryOp): CalcResult {
        require(request.b != 0.0) { "division by zero is undefined" }
        return CalcResult.newBuilder().setValue(request.a / request.b).build()
    }
}
