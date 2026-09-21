package cplus

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

fun main(args: Array<String>) {
    if (args.isEmpty() || args.contentEquals(arrayOf("--help")) || args.contentEquals(arrayOf("-h"))) {
        printUsage()
        return
    }

    try {
        val (input, output) = parseArguments(args)
        val source = if (input == "-") generateSequence(::readLine).toList().joinToString("\n") else Path(input).readText()
        val result = CPlusTranspiler().transpile(source)
        if (output == null) print(result) else Path(output).writeText(result)
    } catch (error: Exception) {
        System.err.println("cplus: ${error.message ?: error::class.simpleName}")
        kotlin.system.exitProcess(2)
    }
}

private fun parseArguments(args: Array<String>): Pair<String, String?> {
    return when {
        args.size == 1 -> args[0] to null
        args.size == 3 && args[0] == "-o" -> args[2] to args[1]
        else -> throw IllegalArgumentException("usage: cplus [-o output.c] input.cp (use - for stdin)")
    }
}

private fun printUsage() {
    println("usage: cplus [-o output.c] input.cp")
    println("       cplus -o output.c input.c+")
}
