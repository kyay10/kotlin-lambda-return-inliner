/*
 * Copyright 2010-2016 JetBrains s.r.o.
 * Copyright (C) 2021 Youssef Shoaib
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.kyay10.kotlinlambdareturninliner

import org.apache.maven.plugin.MojoExecution
import org.apache.maven.project.MavenProject
import org.jetbrains.kotlin.maven.KotlinMavenPluginExtension
import org.jetbrains.kotlin.maven.PluginOption

class LambdaReturnInlinerMavenPlugin : KotlinMavenPluginExtension {
  override fun getCompilerPluginId() = BuildConfig.KOTLIN_PLUGIN_ID

  override fun isApplicable(project: MavenProject, execution: MojoExecution) = true

  override fun getPluginOptions(project: MavenProject, execution: MojoExecution): List<PluginOption> {
    return emptyList()
  }
}
