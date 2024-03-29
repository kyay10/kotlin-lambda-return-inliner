/*
 * Copyright (C) 2020 Brian Norman
 * Copyright (C) 2021 Youssef Shoaib
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package io.github.kyay10.kotlinlambdareturninliner

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.PluginOption
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileFilter
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LambdaReturnInlinerPluginTest {
  val outStream = ByteArrayOutputStream()
  val sampleFiles = mutableListOf<SourceFile>()
  lateinit var compiledSamples: KotlinCompilation.Result

  @BeforeAll
  fun setupSampleFiles() {
    val sampleJvmMainDirectory = File(BuildConfig.SAMPLE_JVM_MAIN_PATH)
    sampleFiles.addAll(sampleJvmMainDirectory.listFilesRecursively { it.extension == "kt" }
      .map { SourceFile.fromPath(it) })
    outStream.writeTo(System.out)
    println(
      "Kotlin Sample Compilation took ${
        measureTimeMillis {
          compiledSamples = compileSources(sampleFiles, outStream).also { println(it.messages) }
        }
      } milliseconds"
    )
  }

  @Test
  fun `When multi-lambda return`() {
    runMain(compiledSamples, "ComplexLogicKt")
    assertEquals(KotlinCompilation.ExitCode.OK, compiledSamples.exitCode)
  }

  @Test
  fun `Lambda Backed Functor Hierarchy`() {
    runMain(compiledSamples, "FunctorHierarchyLambdaBackedKt")
    assertEquals(KotlinCompilation.ExitCode.OK, compiledSamples.exitCode)
  }

  @Test
  fun `Inlined Pairs`() {
    runMain(compiledSamples, "InlinedPairsKt")
    assertEquals(KotlinCompilation.ExitCode.OK, compiledSamples.exitCode)
  }

  @Test
  fun `Scoping Functions and Nulls`() {
    runMain(compiledSamples, "ScopingFunctionsAndNullsKt")
    assertEquals(KotlinCompilation.ExitCode.OK, compiledSamples.exitCode)
  }

  @Test
  fun `Efficient List Operations`() {
    runMain(compiledSamples, "EfficientListOperationsKt")
    assertEquals(KotlinCompilation.ExitCode.OK, compiledSamples.exitCode)
  }
}

fun compile(
  sourceFiles: List<SourceFile>,
  outputStream: OutputStream,
  plugin: ComponentRegistrar = LambdaReturnInlinerComponentRegistrar(),
  commandLineProcessor: CommandLineProcessor = LambdaReturnInlinerCommandLineProcessor(),
  className: String = "MainKt",
): KotlinCompilation.Result {
  val result = compileSources(sourceFiles, outputStream, plugin, commandLineProcessor)
  runMain(result, className)
  return result
}

private fun runMain(
  result: KotlinCompilation.Result,
  className: String = "MainKt"
) {
  val kClazz = result.classLoader.loadClass(className)
  val main = kClazz.declaredMethods.single { it.name == "main" && it.parameterCount == 0 }
  try {
    main.invoke(null)
  } catch (e: InvocationTargetException) {
    throw e.targetException
  }
}

private fun compileSources(
  sourceFiles: List<SourceFile>,
  outputStream: OutputStream,
  plugin: ComponentRegistrar? = LambdaReturnInlinerComponentRegistrar(),
  commandLineProcessor: CommandLineProcessor = LambdaReturnInlinerCommandLineProcessor()
) = KotlinCompilation().apply {
  sources = sourceFiles
  useIR = true
  plugin?.let { compilerPlugins = listOf(plugin) }
  commandLineProcessors = listOf(commandLineProcessor)
  inheritClassPath = true
  messageOutputStream = outputStream
  verbose = true
  kotlincArguments = kotlincArguments + "-Xallow-kotlin-package"
  pluginOptions = pluginOptions + PluginOption(
    BuildConfig.KOTLIN_PLUGIN_ID,
    LambdaReturnInlinerCommandLineProcessor.generatedSourcesDir.toString(),
    BuildConfig.SAMPLE_GENERATED_SOURCES_DIR
  )
}.compile()

fun compile(
  sourceFile: SourceFile,
  outputStream: OutputStream,
  plugin: ComponentRegistrar = LambdaReturnInlinerComponentRegistrar(),
  commandLineProcessor: CommandLineProcessor = LambdaReturnInlinerCommandLineProcessor()
): KotlinCompilation.Result {
  return compile(listOf(sourceFile), outputStream, plugin, commandLineProcessor)
}

fun File.listFilesRecursively(filter: FileFilter): List<File> =
  listOf(this).listFilesRecursively(filter, mutableListOf())

tailrec fun List<File>.listFilesRecursively(
  filter: FileFilter,
  files: MutableList<File> = mutableListOf()
): List<File> {
  val dirs = mutableListOf<File>()
  for (file in this) {
    val filteredFiles = file.listFiles(filter) ?: continue
    files.addAll(filteredFiles)
    dirs.addAll(file.listFiles { it: File -> it.isDirectory } ?: continue)
  }
  return dirs.ifEmpty { return files }.listFilesRecursively(filter, files)
}

