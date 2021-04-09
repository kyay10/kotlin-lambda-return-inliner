package com.github.kyay10.kotlinlambdareturninliner

import kotlinx.metadata.jvm.KotlinClassHeader
import kotlinx.metadata.jvm.KotlinClassMetadata
import org.intellij.lang.annotations.Language
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.builtins.isFunctionTypeOrSubtype
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.toLogger
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.jar.CoreJarFileSystem
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.load.kotlin.JvmPackagePartSource
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.jvm.extensions.AnalysisHandlerExtension
import org.jetbrains.kotlin.resolve.multiplatform.isCommonSource
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DescriptorWithContainerSource
import org.jetbrains.kotlin.types.isFlexible
import org.jetbrains.org.objectweb.asm.Opcodes
import java.io.File

const val GENERATED_FOLDER = "kotlinLambdaReturnInliner"
const val GENERATED_FOLDER_WITH_DOT = ".$GENERATED_FOLDER"

open class KtRecursiveVisitorVoid : KtVisitorVoid() {
  override fun visitKtElement(element: KtElement) {
    super.visitKtElement(element)
    element.acceptChildrenVoid(this)
  }
}

class LambdaReturnInlinerAnalysisHandler(
  val messageCollector: MessageCollector,
  val configuration: CompilerConfiguration,
  val generatedSourcesDir: File?,
) :
  AnalysisHandlerExtension {
  var hasRun = false
  lateinit var allPossibleKtSources: List<VirtualFile>
  val fileToKtFileDeclarations: MutableMap<VirtualFile, KtFileDeclarations> = mutableMapOf()

  @OptIn(ExperimentalStdlibApi::class)
  override fun analysisCompleted(
    project: Project,
    module: ModuleDescriptor,
    bindingTrace: BindingTrace,
    files: Collection<KtFile>
  ): AnalysisResult? {
    if (!::allPossibleKtSources.isInitialized) {
      allPossibleKtSources = buildList {
        configuration[JVMConfigurationKeys.MODULES]?.forEach {
          val fs = CoreJarFileSystem()
          it.getClasspathRoots().mapNotNull { if (File(it).extension != "jar") null else fs.findFileByPath("$it!/") }
            .flatMap {
              File(it.path).parentFile.parentFile.collectFiles().filter { it.extension == "jar" }
                .mapNotNull { fs.findFileByPath("${it.absolutePath}!/") }
            }.forEach {
              val collectedFiles = it.collectFiles()
              addAll(collectedFiles.filter { it.extension == "kt" })
            }
        }
      }
    }
    if (hasRun) return null
    hasRun = true
    val sourceRoot = generatedSourcesDir ?: files.filter { it.isCommonSource != true }.map { File(it.virtualFilePath) }
      .first { it.parentFile.name != GENERATED_FOLDER_WITH_DOT }.parentFile.resolve(
        GENERATED_FOLDER_WITH_DOT
      )
    sourceRoot.apply {
      mkdirs()
      this.listFiles()?.forEach { it.delete() } // Start off with a clean slate
    }
    /*sourceRoot.resolve("test.kt").apply { createNewFile()}.appendText("""
      fun testicles() {
        println("test!")
      }
    """.trimIndent())*/

    files.forEach {
      it.acceptChildrenVoid(object : KtRecursiveVisitorVoid() {
        override fun visitCallExpression(expression: KtCallExpression) {
          super.visitCallExpression(expression)
          val resolvedCall = expression.getResolvedCall(bindingTrace.bindingContext)
          val resolvedDescriptor = resolvedCall?.resultingDescriptor
          messageCollector.toLogger().warning(resolvedDescriptor.toString())
          if (resolvedDescriptor?.safeAs<FunctionDescriptor>()?.isInline != true
            || (!(resolvedCall.valueArguments.any { (key, value) -> (value.arguments.any { it is KtLambdaArgument } || key.declaresDefaultValue()) && ((key.type.isMarkedNullable && key.type.isFunctionTypeOrSubtype) || key.type.isFlexible()) })
              && !(listOfNotNull(
              resolvedDescriptor.dispatchReceiverParameter toNotNull resolvedCall.dispatchReceiver,
              resolvedDescriptor.extensionReceiverParameter toNotNull resolvedCall.extensionReceiver
            ).any { (key, value) -> value.type.isFunctionTypeOrSubtype && ((key.type.isFunctionTypeOrSubtype) || key.type.isFlexible()) })
              )
          ) return
          val metadata =
            resolvedDescriptor.safeAs<DescriptorWithContainerSource>()?.containerSource?.safeAs<JvmPackagePartSource>()?.knownJvmBinaryClass?.classHeader?.let { it1 ->
              KotlinClassMetadata.read(
                it1
              )
            }
          val reader =
            resolvedDescriptor.safeAs<DescriptorWithContainerSource>()?.containerSource?.safeAs<JvmPackagePartSource>()?.knownJvmBinaryClass?.location?.let { it1 ->
              CoreJarFileSystem().findFileByPath(
                it1
              )
            }?.contentsToByteArray()?.let { org.objectweb.asm.ClassReader(it) }
          val source = run {
            var result: String? = null
            try {
              reader?.accept(object : org.objectweb.asm.ClassVisitor(Opcodes.API_VERSION) {
                override fun visitSource(source: String?, debug: String?) {
                  super.visitSource(source, debug)
                  result = source
                  throw EmptyThrowable // Purposeful optimisation to not go through the rest of the class's data
                }
              }, 0)
            } catch (e: EmptyThrowable) { /* Purposefully ignored */
            }
            result
          }
          val possibleReferredFiles = allPossibleKtSources.filter { it.name == source }
          for (file in possibleReferredFiles) {
            val ktFileDeclarations = fileToKtFileDeclarations.getOrPut(file) {
              KtFileDeclarations(file.inputStream.readAllBytes().decodeToString(), project).apply {
                ktFile.acceptChildrenVoid(object : KtRecursiveVisitorVoid() {
                  override fun visitDeclaration(dcl: KtDeclaration) {
                    super.visitDeclaration(dcl)
                    declarations.add(dcl)
                  }
                })
              }
            }
            ktFileDeclarations.declarations.forEach { function ->
              if (function is KtNamedFunction && function.hasModifier(KtTokens.INLINE_KEYWORD) && function.nameAsName == resolvedDescriptor.name && function.valueParameters.map { it.nameAsName } == resolvedDescriptor.valueParameters.map { it.name }) {
                ktFileDeclarations.declarationsToKeep.addAll(function.parentsWithSelf.toList())
              }
            }
          }
        }
      })
    }
    for ((file, ktFileDeclarations) in fileToKtFileDeclarations) {
      @Language("kotlin") var text: String = ktFileDeclarations.text
      ktFileDeclarations.declarations.removeAll(ktFileDeclarations.declarationsToKeep)
      // Just don't overthink this one too much. We're keeping any declaration inside of a function just in
      // case instead of focusing on the specific inline declarations. That's because of (probably) captured
      // variables that can be used in the future in maybe local inline funs (if that ever gets supported), but
      // again just don't overthink it ;)
      val functionDeclarations = ktFileDeclarations.declarationsToKeep.filterIsInstance<KtNamedFunction>()
      val textRanges = ktFileDeclarations.declarations.map { it.textRange }
      val deletedRanges = mutableListOf<TextRange>()
      deletedRanges.addAll(textRanges.filterNot { range ->
        textRanges.any {
          it != range && it.contains(
            range
          )
        } ||
          functionDeclarations.any {
            it.textRange != range && it.textRange.contains(
              range
            )
          }
      })
      /*ktFile.declarations.forEach { if(it is KtFunction && it.hasModifier(KtTokens.INLINE_KEYWORD) && it.nameAsName == resolvedDescriptor.name && it.valueParameters.map{it.nameAsName} == resolvedDescriptor.valueParameters.map { it.name }) {

      } else {
      deletedRanges.add(it.textRange)
      } }*/
      var shiftLeft: Int = 0
      for (range in deletedRanges.sortedBy { it.startOffset }) {
        text = text.removeRange(range.startOffset - shiftLeft, range.endOffset - shiftLeft)
        shiftLeft += range.length
      }
      text =
        """@file:Suppress("EXPERIMENTAL_API_USAGE", "EXPERIMENTAL_API_USAGE_ERROR", "INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "CANNOT_OVERRIDE_INVISIBLE_MEMBER", "INVISIBLE_SETTER", "REDECLARATION", "PACKAGE_OR_CLASSIFIER_REDECLARATION", ) ${"\n $text"}"""
      val newFileName = file.path.substringAfterLast("!/").replace("/", "_")
      val newFile = sourceRoot.resolve(newFileName).apply { createNewFile() }
      newFile.writeText(text)
    }
    return AnalysisResult.RetryWithAdditionalRoots(bindingTrace.bindingContext, module, listOf(), listOf(sourceRoot))
  }
}

private fun KotlinClassMetadata.Companion.read(header: org.jetbrains.kotlin.load.kotlin.header.KotlinClassHeader): KotlinClassMetadata? =
  read(header.toKotlinXHeader())


private fun org.jetbrains.kotlin.load.kotlin.header.KotlinClassHeader.toKotlinXHeader(): KotlinClassHeader =
  KotlinClassHeader(
    kind.id,
    metadataVersion.toArray(),
    bytecodeVersion.toArray(),
    data,
    strings,
    multifileClassName,
    packageName,
    extraInt
  )

object EmptyThrowable : Throwable() {
  override fun fillInStackTrace(): Throwable = this
}

infix fun <A, B> A?.toNotNull(that: B?): Pair<A, B>? = this?.let { a -> that?.let { b -> Pair(a, b) } }

data class KtFileDeclarations(
  val text: String,
  val ktFile: KtFile,
  val declarations: MutableList<KtDeclaration> = mutableListOf(),
  val declarationsToKeep: MutableList<PsiElement> = mutableListOf()
) {
  constructor(
    text: String,
    project: Project,
    declarations: MutableList<KtDeclaration> = mutableListOf(),
    declarationsToKeep: MutableList<PsiElement> = mutableListOf()
  ) : this(
    text, PsiFileFactory.getInstance(project)
      .createFileFromText(KotlinLanguage.INSTANCE, text) as KtFile, declarations, declarationsToKeep
  )
}
