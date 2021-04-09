@file:OptIn(ExperimentalStdlibApi::class)

package com.github.kyay10.kotlinlambdareturninliner

import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import java.io.File

fun VirtualFile.collectFiles(): List<VirtualFile> {
  return buildList {
    listOf(this@collectFiles).collectFiles(this)
  }
}

@JvmName("collectVirtualFiles")
tailrec fun List<VirtualFile>.collectFiles(list: MutableList<VirtualFile>) {
  if (isEmpty()) return
  list.addAll(this)
  flatMap { it.children.toList() }.collectFiles(list)
}

fun File.collectFiles(): List<File> {
  return buildList {
    listOf(this@collectFiles).collectFiles(this)
  }
}

tailrec fun List<File>.collectFiles(list: MutableList<File>) {
  if (isEmpty()) return
  list.addAll(this)
  flatMap { it.listFiles().toListOrEmpty() }.collectFiles(list)
}
