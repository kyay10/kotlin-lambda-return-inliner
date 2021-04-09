/*
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
@file:Suppress("unused")

package com.github.kyay10.kotlinlambdareturninliner

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import kotlin.reflect.KProperty

open class OptionCommandLineProcessor(override val pluginId: String) : CommandLineProcessor {
  @PublishedApi
  internal val _pluginOptions: MutableCollection<TransformableCliOption<*>> = mutableListOf()
  override val pluginOptions: Collection<TransformableCliOption<*>> = _pluginOptions

  inline fun <T : Any> option(
    optionName: String,
    valueDescription: String,
    description: String,
    required: Boolean = true,
    allowMultipleOccurrences: Boolean = false,
    crossinline transform: (String) -> T
  ): TransformableCliOption<T> {
    return object :
      TransformableCliOption<T>(optionName, valueDescription, description, required, allowMultipleOccurrences) {
      override fun transform(value: String): T = transform(value)
    }.also { _pluginOptions.add(it) }
  }

  // The `: String` thing is specifically so that you can distinguish clearly that it's an option of String
  @Suppress("FINAL_UPPER_BOUND")
  fun <T : String> option(
    optionName: String,
    valueDescription: String,
    description: String,
    required: Boolean = true,
    allowMultipleOccurrences: Boolean = false,
  ): TransformableCliOption<T> {
    return object :
      TransformableCliOption<T>(optionName, valueDescription, description, required, allowMultipleOccurrences) {
      @Suppress("UNCHECKED_CAST")
      override fun transform(value: String): T = value as T
    }.also { _pluginOptions.add(it) }
  }

  override fun processOption(
    option: AbstractCliOption,
    value: String,
    configuration: CompilerConfiguration
  ) {
    @Suppress("UNCHECKED_CAST")
    (option as? TransformableCliOption<Any>)?.let {
      configuration.put(
        it.configurationKey,
        it.transform(value)
      )
    } ?: throw IllegalArgumentException("Unexpected config option ${option.optionName}")
  }
}

abstract class TransformableCliOption<T : Any>(
  name: String,
  override val valueDescription: String,
  override val description: String,
  override val required: Boolean = true,
  override val allowMultipleOccurrences: Boolean = false
) : AbstractCliOption {
  override val optionName: String = name
  abstract fun transform(value: String): T
  val configurationKey: CompilerConfigurationKey<T> = CompilerConfigurationKey<T>(name)

  operator fun getValue(thisRef: Any?, property: KProperty<*>): CompilerConfigurationKey<T> = configurationKey
}
