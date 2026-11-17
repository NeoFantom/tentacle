package dev.phone2agent.core.dev

import dev.phone2agent.core.FileApi
import dev.phone2agent.core.HttpServer
import dev.phone2agent.core.PingInfo
import dev.phone2agent.core.StorageRoot
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch

private data class Options(val root: File, val port: Int, val token: String)

fun main(args: Array<String>) {
    val options = parseOptions(args)
    val root = options.root.canonicalFile
    require(root.isDirectory && root.canRead()) { "--root must be a readable directory: $root" }

    val storageRoot = StorageRoot("primary", root, "Development root")
    val server = HttpServer(
        port = options.port,
        tokenProvider = { options.token },
        api = FileApi(listOf(storageRoot)),
        info = {
            PingInfo(
                version = "0.1.0",
                device = "Desktop development server",
                androidSdk = 0,
                allFilesAccess = true,
                roots = listOf(storageRoot),
            )
        },
    )
    server.logger = { message -> System.err.println(message) }
    server.start()

    val stopped = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread({
        server.stop()
        stopped.countDown()
    }, "phone2agent-dev-shutdown"))

    val token = uriEncode(options.token)
    val device = uriEncode("Desktop development server")
    println("p2a://127.0.0.1:${server.boundPort}?token=$token&name=$device")
    System.out.flush()
    stopped.await()
}

private fun parseOptions(args: Array<String>): Options {
    if (args.any { it == "--help" || it == "-h" }) {
        println("Usage: devServer --root DIR --port N --token T")
        kotlin.system.exitProcess(0)
    }

    val values = mutableMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val option = args[index]
        require(option in setOf("--root", "--port", "--token")) { "Unknown option: $option" }
        require(index + 1 < args.size) { "Missing value for $option" }
        require(values.put(option, args[index + 1]) == null) { "Duplicate option: $option" }
        index += 2
    }

    val root = values["--root"] ?: error("Missing required option: --root")
    val port = values["--port"]?.toIntOrNull() ?: error("--port must be an integer")
    require(port in 0..65535) { "--port must be between 0 and 65535" }
    val token = values["--token"] ?: error("Missing required option: --token")
    require(token.isNotBlank()) { "--token must not be blank" }
    return Options(File(root), port, token)
}

private fun uriEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
