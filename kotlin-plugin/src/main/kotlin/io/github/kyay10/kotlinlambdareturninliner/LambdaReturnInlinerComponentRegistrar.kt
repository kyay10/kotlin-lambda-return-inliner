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

package io.github.kyay10.kotlinlambdareturninliner

import com.google.auto.service.AutoService
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.resolve.jvm.extensions.AnalysisHandlerExtension


val IS_GENERATING_SHADOWED_DECLARATIONS = CompilerConfigurationKey<Boolean>("IS_GENERATING_SHADOWED_DECLARATIONS")

@AutoService(ComponentRegistrar::class)
class LambdaReturnInlinerComponentRegistrar @Suppress("unused") constructor() :
  ComponentRegistrar { // Used by service loader

  override fun registerProjectComponents(
    project: MockProject,
    configuration: CompilerConfiguration
  ) {
    val messageCollector = configuration.get(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
    val generatedSourcesDir = configuration[LambdaReturnInlinerCommandLineProcessor.generatedSourcesDir]
    val renamesMap = mutableMapOf<String, String>()
    AnalysisHandlerExtension.registerExtension(
      project,
      LambdaReturnInlinerAnalysisHandler(messageCollector, configuration, generatedSourcesDir, renamesMap)
    )
    IrGenerationExtension.registerExtension(
      project,
      LambdaReturnInlinerIrGenerationExtension(project, messageCollector, configuration, renamesMap)
    )
  }
}
