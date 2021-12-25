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

@file:Suppress("UnstableApiUsage", "unused", "MemberVisibilityCanBePrivate")

package io.github.kyay10.kotlinlambdareturninliner

import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import kotlin.reflect.KProperty

open class LambdaReturnInlinerGradleExtension(
  objects: ObjectFactory,
  projectLayout: ProjectLayout
) {
  val generatedSourcesDirProperty: DirectoryProperty = objects.directoryProperty().apply {
    convention(projectLayout.buildDirectory.map { it.dir("generated/source/kotlinLambdaReturnInliner") })
  }
  var generatedSourcesDir: Directory by generatedSourcesDirProperty
  var generatedSourcesDirProvider: Provider<Directory> by generatedSourcesDirProperty.asProvider
}

operator fun <T> Property<T>.setValue(thisRef: Any?, property: KProperty<*>, value: T) {
  set(value)
}

operator fun <T> Property<T>.getValue(thisRef: Any?, property: KProperty<*>): T = get()


operator fun <T> PropertyAsProvider<T>.setValue(thisRef: Any?, property: KProperty<*>, value: Provider<T>) {
  this.property.set(value)
}

operator fun <T> PropertyAsProvider<T>.getValue(thisRef: Any?, property: KProperty<*>): Provider<T> = this.property

@Suppress("INLINE_CLASS_DEPRECATED") // Gradle is stuck on 1.4, and so @JvmInline can't be used
inline class PropertyAsProvider<T>(val property: Property<T>)

val <T> Property<T>.asProvider: PropertyAsProvider<T> get() = PropertyAsProvider(this)
