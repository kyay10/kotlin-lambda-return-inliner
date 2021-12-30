@file:Suppress("unused")

package io.github.kyay10.kotlinlambdareturninliner

import io.github.kyay10.kotlinlambdareturninliner.utils.collectFiles
import io.github.kyay10.kotlinlambdareturninliner.utils.safeAs
import io.github.kyay10.kotlinlambdareturninliner.utils.toNotNull
import io.github.kyay10.kotlinlambdareturninliner.utils.withBindingContext
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.backend.common.phaser.PhaseConfig
import org.jetbrains.kotlin.backend.jvm.JvmIrCodegenFactory
import org.jetbrains.kotlin.backend.jvm.jvmPhases
import org.jetbrains.kotlin.builtins.isFunctionType
import org.jetbrains.kotlin.builtins.isFunctionTypeOrSubtype
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.codegen.ClassBuilderFactories
import org.jetbrains.kotlin.codegen.DefaultCodegenFactory
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.openapi.vfs.impl.jar.CoreJarFileSystem
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.com.intellij.psi.PsiNameIdentifierOwner
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.container.ComponentProvider
import org.jetbrains.kotlin.container.get
import org.jetbrains.kotlin.context.ProjectContext
import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.load.kotlin.JvmPackagePartSource
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.psi.psiUtil.quoteIfNeeded
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.LazyTopDownAnalyzer
import org.jetbrains.kotlin.resolve.TopDownAnalysisMode
import org.jetbrains.kotlin.resolve.jvm.extensions.AnalysisHandlerExtension
import org.jetbrains.kotlin.resolve.multiplatform.isCommonSource
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DescriptorWithContainerSource
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.isFlexible
import org.jetbrains.kotlin.types.typeUtil.isTypeParameter
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.org.objectweb.asm.Opcodes
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier

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
  private val configuration: CompilerConfiguration,
  private val generatedSourcesDir: File?,
  private val renamesMap: MutableMap<String, String>
) :
  AnalysisHandlerExtension {
  private val hasRun get() = ::previousBindingTrace.isInitialized
  private lateinit var previousBindingTrace: BindingTrace
  private lateinit var previousModule: ModuleDescriptor
  private lateinit var ktFilesToIgnore: MutableSet<KtFile>
  val fileToKtFileDeclarations: MutableMap<VirtualFile, KtFileDeclarations> = mutableMapOf()
  val jarPathToKtSourceFiles: MutableMap<String, List<VirtualFile>> = mutableMapOf()

  private fun Field.setFinal(obj: Any?, newValue: Any?) {
    isAccessible = true
    val modifiersField: Field = Field::class.java.getDeclaredField("modifiers")
    modifiersField.isAccessible = true
    modifiersField.setInt(this, modifiers and Modifier.FINAL.inv())
    set(obj, newValue)
  }

  override fun doAnalysis(
    project: Project,
    module: ModuleDescriptor,
    projectContext: ProjectContext,
    files: Collection<KtFile>,
    bindingTrace: BindingTrace,
    componentProvider: ComponentProvider
  ): AnalysisResult? {
    // Performance optimisations go brrrrrrrrr...
    // Simply, the cost of fully repeating analysis is just too much, and so instead what we do is we skip analysis
    // for files that were previously analysed, and so now only the new files have to be analysed, which means much less
    // time spent analysing useless code. This works because we don't use shadowed declarations anymore, and instead
    // we do the mapping in the IR
    return if (hasRun && files is MutableList<KtFile>) {
      runCatching {
        files.removeAll(ktFilesToIgnore)
        componentProvider.get<LazyTopDownAnalyzer>()
          .analyzeDeclarations(TopDownAnalysisMode.TopLevelDeclarations, files)
        val generationState = GenerationState.Builder(
          project,
          ClassBuilderFactories.BINARIES,
          module,
          bindingTrace.bindingContext,
          files,
          configuration
        )
          .codegenFactory(
            if (configuration.getBoolean(JVMConfigurationKeys.IR)) JvmIrCodegenFactory(
              configuration,
              configuration.get(CLIConfigurationKeys.PHASE_CONFIG) ?: PhaseConfig(jvmPhases)
            ) else DefaultCodegenFactory
          )
          .build()
        configuration.put(IS_GENERATING_SHADOWED_DECLARATIONS, true)
        generationState.codegenFactory.cast<JvmIrCodegenFactory>().convertToIr(generationState, files)
        configuration.put(IS_GENERATING_SHADOWED_DECLARATIONS, false)
        files.clear()
        files.addAll(ktFilesToIgnore)
        AnalysisResult.success(previousBindingTrace.bindingContext, previousModule)
      }.also {
        it.exceptionOrNull()?.printStackTrace()
      }.getOrThrow()
    } else null
  }

  val fs = CoreJarFileSystem()

  @OptIn(ExperimentalStdlibApi::class)
  override fun analysisCompleted(
    project: Project,
    module: ModuleDescriptor,
    bindingTrace: BindingTrace,
    files: Collection<KtFile>
  ): AnalysisResult? = withBindingContext(bindingTrace.bindingContext) {
    if (hasRun) return null
    // Fallback: looks through all the non-common kt source files and chooses the one that wasn't generated by us.
    // TODO: maybe figure out a way to use this to find other generated files?
    //  Perhaps by looking for build/generated/source and piggybacking off of that directory for our purposes
    val sourceRoot = generatedSourcesDir ?: files.filter { it.isCommonSource != true }.map { File(it.virtualFilePath) }
      .first { it.parentFile.name != GENERATED_FOLDER_WITH_DOT }.parentFile.resolve(
        GENERATED_FOLDER_WITH_DOT
      )
    sourceRoot.apply {
      mkdirs()
      // Start off with a clean slate
      this.listFiles()?.forEach { it.delete() }
    }
    files.forEach { ktFile ->
      ktFile.acceptChildrenVoid(object : KtRecursiveVisitorVoid() {
        override fun visitCallExpression(expression: KtCallExpression) {
          super.visitCallExpression(expression)
          val resolvedCall = expression.resolvedCall
          val resolvedDescriptor = resolvedCall?.resultingDescriptor
          // This is the same criteria that the IrExtension has for inlining
          if (resolvedDescriptor?.safeAs<FunctionDescriptor>()?.isInline != true
            || (!(resolvedCall.valueArguments.any { (key, value) ->
              (value.arguments.any {
                it is KtLambdaArgument || it.getArgumentExpression()
                  ?.typeThroughResolvingCall?.isFunctionTypeOrSubtypeCached == true
              } || key.declaresDefaultValue()) && ((key.type.isMarkedNullable && key.type.isFunctionTypeOrSubtypeCached) || key.type.isFlexible() || key.type.isTypeParameter() || key.original.type.isFlexible()) || key.original.type.isTypeParameter()
            })
              && !(listOfNotNull(
              resolvedDescriptor.dispatchReceiverParameter toNotNull resolvedCall.dispatchReceiver,
              resolvedDescriptor.extensionReceiverParameter toNotNull resolvedCall.extensionReceiver
            ).any { (key, value) -> value.type.isFunctionTypeOrSubtypeCached && ((key.type.isFunctionTypeOrSubtypeCached) || key.type.isFlexible()) })
              )
          ) {
            return
          }
          val classFileLocation =
            resolvedDescriptor.safeAs<DescriptorWithContainerSource>()?.containerSource?.safeAs<JvmPackagePartSource>()?.knownJvmBinaryClass?.location
              ?: return
          val possibleLocations =
            classFileLocation.substringBefore("!/").takeIf { it.endsWith("jar") }?.let { jarPath ->
              jarPathToKtSourceFiles.getOrPut(jarPath) {
                buildList {
                  val rootLibraryFolder = File(jarPath).parentFile.parentFile
                  val versionlessLibraryFolder = rootLibraryFolder.parentFile
                  val libraryVersion = rootLibraryFolder.name
                  listOfNotNull(
                    rootLibraryFolder,
                    versionlessLibraryFolder.parentFile.resolve("${versionlessLibraryFolder.name}-common")
                      .resolve(libraryVersion)
                      .takeIf { it.exists() }).collectFiles().filter { file -> file.name.endsWith("jar") }
                    .forEach { jarFile ->
                      val virtualJarFile = fs.findFileByPath("${jarFile.absolutePath}!/")
                      val collectedFiles = virtualJarFile?.collectFiles()
                      collectedFiles?.filter { it.nameSequence.endsWith("kt") }?.let {
                        addAll(it)
                      }
                    }

                }
              }
            } ?: return
          val reader = fs.findFileByPath(
            classFileLocation
          )?.contentsToByteArray()?.let { ClassReader(it) }
          val source = run {
            var result: String? = null
            try {
              reader?.accept(object : ClassVisitor(Opcodes.API_VERSION) {
                override fun visitSource(source: String?, debug: String?) {
                  super.visitSource(source, debug)
                  result = source
                  throw EmptyThrowable // Purposeful optimization to not go through the rest of the class's data
                }
              }, 0)
            } catch (e: EmptyThrowable) {
              /* Purposefully ignored */
            }
            result
          }
          val possibleReferredFiles = possibleLocations.filter { it.name == source }
          for (file in possibleReferredFiles) {
            // Collect the declarations for each unique file only once
            val ktFileDeclarations = fileToKtFileDeclarations.getOrPut(file) {
              KtFileDeclarations(file.inputStream.readBytes().decodeToString(), project).apply {
                this.ktFile.acceptChildrenVoid(object : KtRecursiveVisitorVoid() {
                  override fun visitDeclaration(dcl: KtDeclaration) {
                    super.visitDeclaration(dcl)
                    declarations.add(dcl)
                  }
                })
              }
            }
            // TODO: member inline functions should be changed to @HidesMembers extension inline functions
            //  but be careful of needing to specify generics though
            ktFileDeclarations.declarations.forEach { function ->
              if (function is KtNamedFunction && function.hasModifier(KtTokens.INLINE_KEYWORD) && function.nameAsName == resolvedDescriptor.name && function.valueParameters.map { it.nameAsName } == resolvedDescriptor.valueParameters.map { it.name }) {
                ktFileDeclarations.declarationsToKeep.addAll(function.parentsWithSelf.toList())
              }
            }
          }
        }
      })
    }
    createShadowFiles(sourceRoot, shouldRenameDeclarations = true, renamesMap)
    previousBindingTrace = bindingTrace
    previousModule = module
    ktFilesToIgnore = files.toMutableSet()

    return AnalysisResult.RetryWithAdditionalRoots(bindingTrace.bindingContext, module, listOf(), listOf(sourceRoot))
  }

  @Suppress("SameParameterValue")
  private fun createShadowFiles(
    sourceRoot: File,
    shouldRenameDeclarations: Boolean = false,
    renamesMap: MutableMap<String, String>
  ): List<String> {
    return fileToKtFileDeclarations.map { (file, ktFileDeclarations) ->
      val text: StringBuilder = StringBuilder(ktFileDeclarations.text)
      ktFileDeclarations.declarations.removeAll(ktFileDeclarations.declarationsToKeep.toSet())
      // Just don't overthink this one too much. We're keeping any declaration inside a function just in
      // case instead of focusing on the specific inline declarations. That's because of (probably) captured
      // variables that can be used in the future in maybe local inline funs (if that ever gets supported), but
      // again just don't overthink it ;)
      val functionDeclarations = ktFileDeclarations.declarationsToKeep.filterIsInstance<KtNamedFunction>()
      val textRanges = ktFileDeclarations.declarations.map { it.textRange }
      val deletedRanges = mutableListOf<TextRange>()
      deletedRanges.addAll(textRanges.filterNot { range ->
        functionDeclarations.any {
          it.textRange != range && it.textRange.contains(
            range
          )
        }
      })
      // We're deleting the text ranges by replacing them with whitespace
      for (range in deletedRanges) {
        for (i in range.startOffset until range.endOffset) {
          text[i] = ' '
        }
      }

      if (shouldRenameDeclarations) {
        // Change the name of every shadowed declaration
        var shiftLeft = 0
        for (declaration in ktFileDeclarations.declarationsToKeep.filterIsInstance<PsiNameIdentifierOwner>()
          .mapNotNull { it.nameIdentifier }.distinctBy { it.textRange }.sortedBy { it.startOffset }) {
          val unquotedIdentifier = KtPsiUtil.unquoteIdentifier(declaration.text)
          // It's important to have the mapped name based on the actual name itself
          // so that overloads of a function would have the same identifier
          // because otherwise we'll need to define a whole list of mapped-to names for each name, which isn't ideal
          val newName = "$unquotedIdentifier-${unquotedIdentifier.hashCode()}".quoteIfNeeded()
          text.replace(
            declaration.textRange.startOffset + shiftLeft,
            declaration.textRange.endOffset + shiftLeft,
            newName
          )
          renamesMap[unquotedIdentifier] = newName
          shiftLeft += newName.length - declaration.textRange.length
        }
      }

      // Suppress all the possible errors that you can ever imagine. These shadowed files will end up being deleted in
      // IR so there's no need to worry about them
      text.insert(
        0,
        """@file:Suppress("EXPERIMENTAL_API_USAGE", "EXPERIMENTAL_API_USAGE_ERROR", "INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "CANNOT_OVERRIDE_INVISIBLE_MEMBER", "INVISIBLE_SETTER", "REDECLARATION", "PACKAGE_OR_CLASSIFIER_REDECLARATION", "DEPRECATION", ) 
          |@file:${BuildConfig.CONTAINS_COPIED_DECLARATIONS_ANNOTATION_FQNAME}
          |${"\n "}""".trimMargin()
      )
      // Generate a "unique" filename based on this source file's path in the jar
      // This should be safe for expect/actual declarations since it takes the specific jar file name into account
      // and so a "-common" will be included in common files
      val newFileName =
        "${
          file.path.substringBeforeLast("!/").substringAfterLast("/").removeSuffix(".jar")
        }_${file.path.substringAfterLast("!/").replace("/", "_")}"
      val newFile = sourceRoot.resolve(newFileName).apply { createNewFile() }
      text.toString().also { newFile.writeText(it) }
    }
  }
}

object EmptyThrowable : Throwable() {
  override fun fillInStackTrace(): Throwable = this
}

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

fun KtElement.acceptChildrenVoid(ktVisitorVoid: KtVisitorVoid) {
  acceptChildren(ktVisitorVoid, null)
}

private val _isFunctionTypeOrSubtypeCache: MutableMap<ClassifierDescriptor, Boolean> = hashMapOf()
val KotlinType.isFunctionTypeOrSubtypeCached: Boolean
  get() {
    val thisClassifier = constructor.declarationDescriptor
    val superTypes = constructor.supertypes
    _isFunctionTypeOrSubtypeCache[thisClassifier]?.let { return it }
    for (type in superTypes) {
      _isFunctionTypeOrSubtypeCache[type.constructor.declarationDescriptor]?.let { return it }
    }

    val result = if (isFunctionType) true else isFunctionTypeOrSubtype
    thisClassifier?.let { _isFunctionTypeOrSubtypeCache[it] = result }
    if (!isFunctionType) {
      // Must be a function subtype
      // Add whichever supertype is a function type to the cache
      for (type in superTypes) {
        val typeClassifier = type.constructor.declarationDescriptor
        if (type.isFunctionType) {
          typeClassifier?.let { _isFunctionTypeOrSubtypeCache[it] = result }
        }
      }
    }
    return result
  }
