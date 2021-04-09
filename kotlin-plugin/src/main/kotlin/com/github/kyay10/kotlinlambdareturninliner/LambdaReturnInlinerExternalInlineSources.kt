package com.github.kyay10.kotlinlambdareturninliner

import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.jar.CoreJarFileSystem
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.extensions.CollectAdditionalSourcesExtension
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtVisitorVoid
import java.io.File

var hasAlreadyRun = false

class LambdaReturnInlinerExternalInlineSources(
  val project: Project,
  val messageCollector: MessageCollector,
  val configuration: CompilerConfiguration
) : CollectAdditionalSourcesExtension {
  @OptIn(ExperimentalStdlibApi::class)
  override fun collectAdditionalSourcesAndUpdateConfiguration(
    knownSources: Collection<KtFile>,
    configuration: CompilerConfiguration,
    project: Project
  ): Collection<KtFile> {
    return knownSources
    val resultingFiles = knownSources.toMutableList()
    if (hasAlreadyRun) return knownSources //Prevents infinite loops
    hasAlreadyRun = true
    configuration[JVMConfigurationKeys.MODULES]?.forEach {
      val fs = CoreJarFileSystem()
      it.getClasspathRoots().mapNotNull { if (File(it).extension != "jar") null else fs.findFileByPath("$it!/") }
        .flatMap {
          File(it.path).parentFile.parentFile.collectFiles().filter { it.extension == "jar" }
            .mapNotNull { fs.findFileByPath("${it.absolutePath}!/") }
        }.forEach {
        val collectedFiles = it.collectFiles()
        collectedFiles.filter { it.extension == "kt" }.forEach {

          resultingFiles.add(
            PsiFileFactory.getInstance(project)
              .createFileFromText(KotlinLanguage.INSTANCE, it.inputStream.readAllBytes().decodeToString()) as KtFile
          )
        }
      }
    }
    /*  val environment = KotlinCoreEnvironment.createForProduction(Disposer.newDisposable(), configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
      val result = KotlinToJVMBytecodeCompiler.analyze(environment)
      return buildList {
        knownSources.forEach {
          it.acceptChildrenVoid(object : KtVisitorVoid() {
            override fun visitKtElement(element: KtElement) {
              super.visitKtElement(element)
              element.acceptChildrenVoid(this)
            }

            override fun visitCallExpression(expression: KtCallExpression) {
              super.visitCallExpression(expression)
              val resolvedCall = expression.getResolvedCall(result!!.bindingContext)
              resolvedCall
            }
          })
        }
      }*/
    return resultingFiles
  }
}

public fun KtElement.acceptChildrenVoid(ktVisitorVoid: KtVisitorVoid) {
  acceptChildren(ktVisitorVoid, null)
}

fun VirtualFile.collectFiles(): List<VirtualFile> {
  val list = mutableListOf<VirtualFile>()
  listOf(this).collectFiles(list)
  return list
}

@JvmName("collectVirtualFiles")
tailrec fun List<VirtualFile>.collectFiles(list: MutableList<VirtualFile>) {
  if (isEmpty()) return
  list.addAll(this)
  flatMap { it.children.toList() }.collectFiles(list)
}

fun File.collectFiles(): List<File> {
  val list = mutableListOf<File>()
  listOf(this).collectFiles(list)
  return list
}

tailrec fun List<File>.collectFiles(list: MutableList<File>) {
  if (isEmpty()) return
  list.addAll(this)
  flatMap { it.listFiles()?.toList() ?: emptyList() }.collectFiles(list)
}
