@file:OptIn(ExperimentalStdlibApi::class)
@file:Suppress("unused")

package io.github.kyay10.kotlinlambdareturninliner.utils

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.io.FilenameFilter

fun VirtualFile.collectFiles(): List<VirtualFile> {
  return buildList {
    listOf(this@collectFiles).collectFiles(this)
  }
}

@JvmName("collectVirtualFiles")
tailrec fun List<VirtualFile>.collectFiles(list: MutableList<VirtualFile>) {
  if (isEmpty()) return
  list.addAll(this)
  flatMap { it.children }.collectFiles(list)
}

fun File.collectFiles(filter: FilenameFilter? = null): List<File> {
  return buildList {
    listOf(this@collectFiles).collectFiles(this, filter)
    if (filter?.accept(this@collectFiles.parentFile, this@collectFiles.name) == false) {
      removeAt(0)
    }
  }
}

fun List<File>.collectFiles(filter: FilenameFilter? = null): List<File> {
  return buildList {
    this@collectFiles.collectFiles(this, filter)
    if (filter != null) {
      this@collectFiles.forEachIndexed { index, file ->
        if (!filter.accept(file.parentFile, file.name)) {
          removeAt(index)
        }
      }
    }
  }
}

tailrec fun List<File>.collectFiles(list: MutableList<File>, filter: FilenameFilter? = null) {
  if (isEmpty()) return
  list.addAll(this)
  flatMap { it.listFiles(filter).toListOrEmpty() }.collectFiles(list)
}


