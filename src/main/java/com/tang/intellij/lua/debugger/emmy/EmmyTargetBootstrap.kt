package com.tang.intellij.lua.debugger.emmy

/** Prepares one or more transport candidates before the shared Emmy handshake starts. */
interface EmmyTargetBootstrap {
    fun prepareTransports(): List<Transporter>

    /** Optional one-shot token used by attach-mode Agent authentication. */
    val authToken: String?
        get() = null

    fun stop() {}
}

class ConfiguredEmmyTargetBootstrap(
    private val configuration: EmmyDebugConfiguration
) : EmmyTargetBootstrap {
    override fun prepareTransports(): List<Transporter> = listOf(
        when (configuration.type) {
            EmmyDebugTransportType.PIPE_CLIENT -> PipelineClientTransporter(configuration.pipeName)
            EmmyDebugTransportType.PIPE_SERVER -> PipelineServerTransporter(configuration.pipeName)
            EmmyDebugTransportType.TCP_CLIENT -> SocketClientTransporter(configuration.host, configuration.port)
            EmmyDebugTransportType.TCP_SERVER -> SocketServerTransporter(configuration.host, configuration.port)
        }
    )
}
