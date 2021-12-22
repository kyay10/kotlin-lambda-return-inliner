@file:Suppress("unused")

package io.github.kyay10.kotlinlambdareturninliner

import io.github.kyay10.kotlinlambdareturninliner.utils.*
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.backend.common.phaser.PhaseConfig
import org.jetbrains.kotlin.backend.jvm.JvmIrCodegenFactory
import org.jetbrains.kotlin.backend.jvm.jvmPhases
import org.jetbrains.kotlin.builtins.BuiltInsPackageFragment
import org.jetbrains.kotlin.builtins.FunctionInterfacePackageFragment
import org.jetbrains.kotlin.builtins.ReflectionTypes
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
import org.jetbrains.kotlin.container.StorageComponentContainer
import org.jetbrains.kotlin.container.ValueDescriptor
import org.jetbrains.kotlin.container.get
import org.jetbrains.kotlin.context.ProjectContext
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.PackageFragmentDescriptorImpl
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.load.java.AnnotationTypeQualifierResolver
import org.jetbrains.kotlin.load.java.JavaClassFinder
import org.jetbrains.kotlin.load.java.JavaClassesTracker
import org.jetbrains.kotlin.load.java.JavaTypeEnhancementState
import org.jetbrains.kotlin.load.java.components.JavaPropertyInitializerEvaluator
import org.jetbrains.kotlin.load.java.components.JavaResolverCache
import org.jetbrains.kotlin.load.java.components.SignaturePropagator
import org.jetbrains.kotlin.load.java.lazy.*
import org.jetbrains.kotlin.load.java.lazy.descriptors.LazyJavaPackageFragment
import org.jetbrains.kotlin.load.java.sources.JavaSourceElementFactory
import org.jetbrains.kotlin.load.java.structure.JavaPackage
import org.jetbrains.kotlin.load.java.typeEnhancement.SignatureEnhancement
import org.jetbrains.kotlin.load.kotlin.DeserializedDescriptorResolver
import org.jetbrains.kotlin.load.kotlin.JvmPackagePartSource
import org.jetbrains.kotlin.load.kotlin.KotlinClassFinder
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.psi.psiUtil.quoteIfNeeded
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.BindingTraceContext
import org.jetbrains.kotlin.resolve.LazyTopDownAnalyzer
import org.jetbrains.kotlin.resolve.TopDownAnalysisMode
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.jvm.SyntheticJavaPartsProvider
import org.jetbrains.kotlin.resolve.jvm.extensions.AnalysisHandlerExtension
import org.jetbrains.kotlin.resolve.lazy.ForceResolveUtil
import org.jetbrains.kotlin.resolve.lazy.ResolveSession
import org.jetbrains.kotlin.resolve.lazy.declarations.DeclarationProviderFactory
import org.jetbrains.kotlin.resolve.lazy.declarations.PackageMemberDeclarationProvider
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyPackageDescriptor
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyPackageMemberScope
import org.jetbrains.kotlin.resolve.multiplatform.isCommonSource
import org.jetbrains.kotlin.resolve.sam.SamConversionResolver
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.serialization.deserialization.ErrorReporter
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DescriptorWithContainerSource
import org.jetbrains.kotlin.storage.StorageManager
import org.jetbrains.kotlin.types.checker.NewKotlinTypeChecker
import org.jetbrains.kotlin.types.isFlexible
import org.jetbrains.kotlin.types.typeUtil.isTypeParameter
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.org.objectweb.asm.Opcodes
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.Type
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.isAccessible

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
  private lateinit var ktFilesToIgnore: Collection<KtFile>
  private lateinit var allPossibleKtSources: List<VirtualFile>
  private lateinit var componentProvider: ComponentProvider
  private val whitespaceCache = mutableMapOf<Int, String>()
  val fileToKtFileDeclarations: MutableMap<VirtualFile, KtFileDeclarations> = mutableMapOf()
  val jarPathToKtSourceFiles: MutableMap<String, List<VirtualFile>> = mutableMapOf()

  private fun Field.setFinal(obj: Any?, newValue: Any?) {
    isAccessible = true
    val modifiersField: Field = Field::class.java.getDeclaredField("modifiers")
    modifiersField.isAccessible = true
    modifiersField.setInt(this, modifiers and Modifier.FINAL.inv())
    set(obj, newValue)
  }

  @OptIn(ExperimentalStdlibApi::class)
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
    // time spent analysing useless code. This works because we don't use shadowed declarations anymore and instead
    // we do the mapping in the IR
    // TODO: configure declaration mapping in IR for inlining
    //val prevComponentProvider = if (this::componentProvider.isInitialized) this.componentProvider else componentProvider
    this.componentProvider = componentProvider
    if (!hasRun) {
      /*val myComponentProvider = RankedComponentProvider(composeContainer("test"){
        useInstance(DelegatingModuleDescriptorWithDelegatedDependencies(module))
      }, componentProvider.cast())
      return myComponentProvider.get<LazyTopDownAnalyzer>().analyzeDeclarations(TopDownAnalysisMode.TopLevelDeclarations, files).let {
        AnalysisResult.success(bindingTrace.bindingContext, myComponentProvider.get())
      }
      //wrapModulePackageFragmentProvider(module, componentProvider)
      module.allDependencyModules.forEach {
        //wrapModulePackageFragmentProvider(it, componentProvider)
      }*/
    }
    /*return null*/
    return if (hasRun && files is MutableList<KtFile>) {

      kotlin.runCatching {
/*        val slicedMap =
          bindingTrace::class.java.allDeclaredFields.first { it.name == "map" }.apply { isAccessible = true }
            .get(bindingTrace).cast<SlicedMapImpl>()
        slicedMap::class.java.allDeclaredFields.first { it.name == "alwaysAllowRewrite" }.setFinal(slicedMap, true)
        val slicedMap2 =
          previousBindingTrace::class.java.allDeclaredFields.first { it.name == "map" }.apply { isAccessible = true }
            .get(previousBindingTrace).cast<SlicedMapImpl>()
        slicedMap2::class.java.allDeclaredFields.first { it.name == "alwaysAllowRewrite" }.setFinal(slicedMap2, true)*/
/*        val mySpecialTrace = MySpecialTrace()
        val myComponentProvider = RankedComponentProvider(composeContainer("test"){
          useInstance(DelegatingModuleDescriptorWithDelegatedDependencies(module))
        }, componentProvider.cast())*/
/*        val myComponentProvider = RankedComponentProvider(composeContainer("test"){
          useInstance(ModuleDescriptorImpl(module.name, projectContext.storageManager, previousModule.builtIns, module.platform, stableName = module.stableName ).apply {
            setDependencies(buildList<ModuleDescriptorImpl> {
              add(this@apply.cast())
              add(module.cast())
              addAll(module.allDependencyModules.cast())
            }, setOf(module.cast()))
            initialize(CompositePackageFragmentProvider(listOf(module.cast<ModuleDescriptorImpl>().packageFragmentProviderForModuleContentWithoutDependencies), "LambdaReturnInlinerPackageFragmentProvider"))
          })
        }, componentProvider.cast())*/
        files.removeAll(ktFilesToIgnore)
        componentProvider/*myComponentProvider*/.get<LazyTopDownAnalyzer>()
          .analyzeDeclarations(TopDownAnalysisMode.TopLevelDeclarations, files - ktFilesToIgnore)
        val generationState = GenerationState.Builder(
          project,
          ClassBuilderFactories.BINARIES,
          module,
          bindingTrace.bindingContext,
          files.toList(),
          configuration
        )
          .codegenFactory(
            if (configuration.getBoolean(JVMConfigurationKeys.IR)) JvmIrCodegenFactory(
              configuration.get(CLIConfigurationKeys.PHASE_CONFIG) ?: PhaseConfig(jvmPhases)
            ) else DefaultCodegenFactory
          )
          .build()
        configuration.put(IS_GENERATING_SHADOWED_DECLARATIONS, true)
        generationState.codegenFactory.cast<JvmIrCodegenFactory>().convertToIr(generationState, files - ktFilesToIgnore)
        configuration.put(IS_GENERATING_SHADOWED_DECLARATIONS, false)
        files.addAll(ktFilesToIgnore)
        files.retainAll(ktFilesToIgnore)

/*        previousBindingTrace.bindingContext.addOwnDataTo(mySpecialTrace, true)
        bindingTrace.bindingContext.addOwnDataTo(mySpecialTrace, true)*/
        /*previousModule.cast<ModuleDescriptorImpl>().packageFragmentProviderForModuleContentWithoutDependencies.cast<WrappingPackageFragmentProvider>().wrappers.forEach {
          it.updateFromContainer(componentProvider)
        }
        previousModule.cast<ModuleDescriptorImpl>().allDependencyModules.forEachIndexed { index, moduleDescriptor ->
          moduleDescriptor.cast<ModuleDescriptorImpl>().packageFragmentProviderForModuleContentWithoutDependencies.cast<WrappingPackageFragmentProvider>().wrappers.forEach {
            it.updateFromContainer(componentProvider)
            it.module = module.allDependencyModules[index]
          }
        }*/
        AnalysisResult.success(previousBindingTrace.bindingContext, previousModule/*myComponentProvider.get()*/)
      }.also {
        it.exceptionOrNull()?.printStackTrace()
      }.getOrThrow()
    } else null
    /*
    return hasRun.ifTrue {
      AnalysisResult.success(previousBindingTrace.bindingContext, module)
    }
    return hasRun.ifTrue {
      kotlin.runCatching {
        val slicedMap =
          bindingTrace::class.java.allDeclaredFields.first { it.name == "map" }.apply { isAccessible = true }
            .get(bindingTrace).cast<SlicedMapImpl>()
        slicedMap::class.java.allDeclaredFields.first { it.name == "alwaysAllowRewrite" }.setFinal(slicedMap, true)
        val slicedMap2 =
          previousBindingTrace::class.java.allDeclaredFields.first { it.name == "map" }.apply { isAccessible = true }
            .get(previousBindingTrace).cast<SlicedMapImpl>()
        slicedMap2::class.java.allDeclaredFields.first { it.name == "alwaysAllowRewrite" }.setFinal(slicedMap2, true)
        //previousBindingTrace.bindingContext.addOwnDataTo(bindingTrace, true)
        componentProvider.get<LazyTopDownAnalyzer>()
          .analyzeDeclarations(TopDownAnalysisMode.TopLevelDeclarations, files - ktFilesToIgnore)
        bindingTrace.bindingContext.addOwnDataTo(previousBindingTrace, true)
        AnalysisResult.success(
          CompositeBindingContext.create(
            listOf(*//* bindingTrace.bindingContext,*//*previousBindingTrace.bindingContext)
          )*//*bindingTrace.bindingContext*//*, module
        )
      }.also {
        it.exceptionOrNull()?.printStackTrace()
      }.getOrThrow()
    }*/
  }

  val fs = CoreJarFileSystem()

  @OptIn(ExperimentalStdlibApi::class)
  override fun analysisCompleted(
    project: Project,
    module: ModuleDescriptor,
    bindingTrace: BindingTrace,
    files: Collection<KtFile>
  ): AnalysisResult? = withBindingContext(bindingTrace.bindingContext) {
    /*if (!::allPossibleKtSources.isInitialized) {
      allPossibleKtSources = collectPossibleKtSources()
    }*/
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
                  ?.typeThroughResolvingCall?.isFunctionTypeOrSubtype == true
              } || key.declaresDefaultValue()) && ((key.type.isMarkedNullable && key.type.isFunctionTypeOrSubtype) || key.type.isFlexible() || key.type.isTypeParameter() || key.original.type.isFlexible()) || key.original.type.isTypeParameter()
            })
              && !(listOfNotNull(
              resolvedDescriptor.dispatchReceiverParameter toNotNull resolvedCall.dispatchReceiver,
              resolvedDescriptor.extensionReceiverParameter toNotNull resolvedCall.extensionReceiver
            ).any { (key, value) -> value.type.isFunctionTypeOrSubtype && ((key.type.isFunctionTypeOrSubtype) || key.type.isFlexible()) })
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
          val reader = CoreJarFileSystem().findFileByPath(
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
          //val possibleReferredFiles2 = allPossibleKtSources.filter { it.name == source }
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
/*
    val roots = listOf(sourceRoot).map { KotlinSourceRoot(it.absolutePath, isCommon = false) }
    val newFiles = createSourceFilesFromSourceRoots(configuration, project, roots).toSet() - files

    KotlinJavaPsiFacade.getInstance(project).clearPackageCaches()

    val fileBasedDeclarationProviderFactory = FileBasedDeclarationProviderFactory(componentProvider.get<GlobalContext>().storageManager, files + newFiles as Collection<KtFile>)
    val resolveSession = componentProvider.get<ResolveSession>()
    resolveSession::class.java.allDeclaredFields.filter { it.name == "declarationProviderFactory" }.first().setFinal(resolveSession, fileBasedDeclarationProviderFactory)

    componentProvider.cast<StorageComponentContainer>().run{
      LazyTopDownAnalyzer(
        get(),get(),get(),get(),get(),get(),
        LazyDeclarationResolver(get(), get(), ResolveSession(get(), get(), get(), fileBasedDeclarationProviderFactory, get(), get())
          .apply {
            setAnnotationResolve(get())
            setDescriptorResolver(get())
            setFunctionDescriptorResolver(get())
            setTypeResolver(get())
            setLazyDeclarationResolver(get())
            setFileScopeProvider(get())
            setDeclarationScopeProvider(get())
            setLookupTracker(get())
            setLanguageVersionSettings(get())
            setDelegationFilter(get())
            setWrappedTypeFactory(get())
            setPlatformDiagnosticSuppressor(get())
            setSamConversionResolver(get())
            setAdditionalClassPartsProvider(get())
            setSealedClassInheritorsProvider(get())
          }, get()),get(),get(),get(),get(),get(),get(),get(),get(),resolveMultiple(ClassifierUsageChecker::class.java).map { it.getValue() as ClassifierUsageChecker },get()
      )
    }
      .analyzeDeclarations(TopDownAnalysisMode.TopLevelDeclarations, newFiles)
*/
    previousBindingTrace = bindingTrace
    previousModule = module
    ktFilesToIgnore = files.toList()

    return AnalysisResult.RetryWithAdditionalRoots(bindingTrace.bindingContext, module, listOf(), listOf(sourceRoot))
  }

  private fun createShadowFiles(
    sourceRoot: File,
    shouldRenameDeclarations: Boolean = false,
    renamesMap: MutableMap<String, String>
  ): List<String> {
    return fileToKtFileDeclarations.map { (file, ktFileDeclarations) ->
      val text: StringBuilder = StringBuilder(ktFileDeclarations.text)
      ktFileDeclarations.declarations.removeAll(ktFileDeclarations.declarationsToKeep)
      // Just don't overthink this one too much. We're keeping any declaration inside a function just in
      // case instead of focusing on the specific inline declarations. That's because of (probably) captured
      // variables that can be used in the future in maybe local inline funs (if that ever gets supported), but
      // again just don't overthink it ;)
      val functionDeclarations = ktFileDeclarations.declarationsToKeep.filterIsInstance<KtNamedFunction>()
      val textRanges = ktFileDeclarations.declarations.map { it.textRange }
      val deletedRanges = mutableListOf<TextRange>()
      // Ensure that we only delete unique ranges, and so any nested ones shall just bubble up to their parent
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
      var shiftLeft = 0
      // We're deleting the text ranges from start to end of the file because it's simpler to do by just shifting
      // the offsets as we go along, and so let's sort the ranges that we need to delete from first to last in file
      for (range in deletedRanges.sortedBy { it.startOffset }) {
        text.replace(
          range.startOffset - shiftLeft,
          range.endOffset - shiftLeft,
          whitespaceCache.getOrPut(range.length) { " " * range.length })
        //shiftLeft += range.length
      }

      if (shouldRenameDeclarations) {
        // Change the name of every shadowed declaration
        shiftLeft = 0
        for (declaration in ktFileDeclarations.declarationsToKeep.filterIsInstance<PsiNameIdentifierOwner>()
          .mapNotNull { it.nameIdentifier }.distinctBy { it.textRange }.sortedBy { it.startOffset }) {
          val unquotedIdentifier = KtPsiUtil.unquoteIdentifier(declaration.text)
          val newName = "$unquotedIdentifier-${unquotedIdentifier.hashCode()}".quoteIfNeeded()
          val replaced =
            text.substring(declaration.textRange.startOffset + shiftLeft, declaration.textRange.endOffset + shiftLeft)
          text.replace(
            declaration.textRange.startOffset + shiftLeft,
            declaration.textRange.endOffset + shiftLeft,
            newName
          )
          renamesMap[unquotedIdentifier] = newName
          println("$unquotedIdentifier, $newName, $replaced, ${declaration.textRange.startOffset + shiftLeft}, ${declaration.textRange.endOffset + shiftLeft}")
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
      // TODO: make this safe for expect/actual sources. In fact, make sure to handle any actual declarations that sneak
      //  in the generated shadowed file (perhaps with a simple suppress)
      val newFileName = file.path.substringAfterLast("!/").replace("/", "_")
      val newFile = sourceRoot.resolve(newFileName).apply { createNewFile() }
      text.toString().also { newFile.writeText(it) }
    }
  }

/*  @OptIn(ExperimentalStdlibApi::class)
  private fun collectPossibleKtSources() = buildList {
    configuration[JVMConfigurationKeys.MODULES]?.forEach { jvmModule ->
      jvmModule.getClasspathRoots()
        .mapNotNull { classpathRoot -> File(classpathRoot).takeIf { it.path.endsWith("jar") } }
        .flatMap { jar ->
          jar.parentFile.parentFile.collectFiles().filter { file -> file.name.endsWith("jar") }
            .mapNotNull { fs.findFileByPath("${it.resolve(it).absolutePath}!/") }
        }.forEach { virtualJarFile ->
          val collectedFiles = virtualJarFile.collectFiles()
          addAll(collectedFiles.filter { it.nameSequence.endsWith("kt") })
        }
    }
  }*/
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

fun wrapModulePackageFragmentProvider(
  module: ModuleDescriptor,
  componentProvider: ComponentProvider
) {
  module::class.declaredMemberProperties.first { it.name == "packageFragmentProviderForModuleContent" }
    .apply { isAccessible = true }.cast<KMutableProperty1<ModuleDescriptor, PackageFragmentProvider?>>().set(
      module,
      WrappingPackageFragmentProvider(
        module.cast<ModuleDescriptorImpl>().packageFragmentProviderForModuleContentWithoutDependencies,
        componentProvider.get(),
        LazyJavaResolverContext(componentProvider.get(), TypeParameterResolver.EMPTY) { null }
      )
    )
}

open class WrappingPackageFragmentProvider(
  val delegate: PackageFragmentProvider,
  val resolveSession: ResolveSession,
  val context: LazyJavaResolverContext,
) : PackageFragmentProvider by delegate, PackageFragmentProviderOptimized {
  private val _wrappers = mutableListOf<MutablePackageDescriptor>()
  val wrappers: List<MutablePackageDescriptor>
    get() = _wrappers

  @Suppress("OverridingDeprecatedMember", "DEPRECATION")
  override fun getPackageFragments(fqName: FqName): List<PackageFragmentDescriptor> {
    val jPackage = context.components.finder.findPackage(fqName)
    return delegate.getPackageFragments(fqName).map { packageFragmentDescriptor ->
      MutablePackageDescriptor(
        packageFragmentDescriptor,
        resolveSession,
        context,
        jPackage
      )?.also(_wrappers::add) ?: packageFragmentDescriptor
    }
  }

  override fun isEmpty(fqName: FqName): Boolean = delegate.isEmpty(fqName)

  override fun collectPackageFragments(fqName: FqName, packageFragments: MutableCollection<PackageFragmentDescriptor>) {
    val jPackage = context.components.finder.findPackage(fqName)
    delegate.collectPackageFragmentsOptimizedIfPossible(
      fqName,
      object : MutableCollection<PackageFragmentDescriptor> by packageFragments {
        override fun add(element: PackageFragmentDescriptor): Boolean {
          return packageFragments.add(
            MutablePackageDescriptor(
              element,
              resolveSession,
              context,
              jPackage,
            )?.also(_wrappers::add) ?: element
          )
        }

        override fun addAll(elements: Collection<PackageFragmentDescriptor>): Boolean {
          return packageFragments.addAll(
            elements.map {
              MutablePackageDescriptor(
                it,
                resolveSession,
                context,
                jPackage,
              )?.also(_wrappers::add) ?: it
            }
          )
        }
      })
  }
}

sealed interface MutablePackageDescriptor : PackageFragmentDescriptor {
  var module: ModuleDescriptor
  var realMemberScope: MemberScope
  override fun getContainingDeclaration(): ModuleDescriptor {
    return module
  }

  override fun getMemberScope(): MemberScope {
    return realMemberScope
  }

  fun updateFromContainer(container: ComponentProvider) {
    with(container) {
      module = get()
    }
  }

  companion object {
    operator fun invoke(
      delegate: PackageFragmentDescriptor,
      resolveSession: ResolveSession,
      context: LazyJavaResolverContext,
      jPackage: JavaPackage?,
    ): MutablePackageDescriptor? = when (delegate) {
      //is LazyJavaPackageFragment -> MutableLazyJavaPackageFragment(context, jPackage!!)
      is LazyPackageDescriptor -> MutableLazyPackageDescriptor(
        delegate,
        resolveSession
      )
      is FunctionInterfacePackageFragment -> MutableFunctionInterfacePackageFragment(delegate)
      is BuiltInsPackageFragment -> MutableBuiltInsPackageFragment(delegate)
      else -> MutablePackageDescriptorImpl(delegate)
    }
  }
}

class MutableLazyPackageDescriptor(delegate: LazyPackageDescriptor, resolveSession: ResolveSession) :
  DelegatingLazyPackageDescriptor(delegate, resolveSession), MutablePackageDescriptor {
  override var module = delegate.module
  var resolveSession = resolveSession
    set(value) {
      field = value
      realMemberScope = LazyPackageMemberScope(resolveSession, realDeclarationProvider, this)
    }
  var realDeclarationProvider = delegate.declarationProvider
    set(value) {
      field = value
      realMemberScope = LazyPackageMemberScope(resolveSession, realDeclarationProvider, this)
    }

  override var realMemberScope = delegate.getMemberScope()
  override fun getContainingDeclaration(): ModuleDescriptor = super<MutablePackageDescriptor>.getContainingDeclaration()
  override fun getMemberScope(): MemberScope = super<MutablePackageDescriptor>.getMemberScope()

  override fun forceResolveAllContents() {
    ForceResolveUtil.forceResolveAllContents(getMemberScope())
  }

  override fun getDeclarationProvider(): PackageMemberDeclarationProvider {
    return realDeclarationProvider
  }

  override fun updateFromContainer(container: ComponentProvider) {
    with(container) {
      super.updateFromContainer(container)
      resolveSession = get()
      val declarationProviderFactory: DeclarationProviderFactory = get()
      realDeclarationProvider = declarationProviderFactory.getPackageMemberDeclarationProvider(fqName)!!
    }
  }
}

open class MutablePackageDescriptorImpl(delegate: PackageFragmentDescriptor) :
  DelegatingPackageDescriptorImpl(delegate), MutablePackageDescriptor {
  override var module = delegate.module
  override var realMemberScope = delegate.getMemberScope()
  override fun getContainingDeclaration(): ModuleDescriptor = super<MutablePackageDescriptor>.getContainingDeclaration()

  override fun getMemberScope(): MemberScope = super<MutablePackageDescriptor>.getMemberScope()
}

open class DelegatingModuleDescriptor(var delegate: ModuleDescriptor) : ModuleDescriptor by delegate {
  override fun equals(other: Any?): Boolean {
    return if(other is DelegatingModuleDescriptor) delegate == other.delegate else delegate == other
  }

  override fun hashCode(): Int {
    return delegate.hashCode()
  }
}

open class DelegatingModuleDescriptorWithDelegatedDependencies(delegate: ModuleDescriptor) :
  DelegatingModuleDescriptor(delegate) {
  val moduleDependencyToDelegatedDependency = mutableMapOf<ModuleDescriptor, DelegatingModuleDescriptor>()
  override val allDependencyModules: List<ModuleDescriptor>
    get() = super.allDependencyModules.map {
      moduleDependencyToDelegatedDependency.getOrPut(it) {
        DelegatingModuleDescriptor(
          it
        )
      }
    }
}

open class MutableBuiltInsPackageFragment(delegate: BuiltInsPackageFragment) : MutablePackageDescriptorImpl(delegate),
  BuiltInsPackageFragment {
  override val isFallback: Boolean
    get() = delegate.cast<BuiltInsPackageFragment>().isFallback
}

open class MutableFunctionInterfacePackageFragment(delegate: FunctionInterfacePackageFragment) :
  MutableBuiltInsPackageFragment(delegate), FunctionInterfacePackageFragment {
  override val isFallback: Boolean
    get() = delegate.cast<BuiltInsPackageFragment>().isFallback
}

open class MutableLazyJavaPackageFragment private constructor(
  delegate: LazyJavaPackageFragment,
  val outerContext: LazyJavaResolverContext,
  val jPackage: JavaPackage
) :
  MutablePackageDescriptorImpl(delegate) {
  override var module: ModuleDescriptor
    get() = super.module
    set(value) {
      super.module = value
      outerContext.module.cast<DelegatingModuleDescriptor>().delegate = value
    }

  constructor(
    outerContext: LazyJavaResolverContext,
    jPackage: JavaPackage
  ) : this(
    outerContext.replaceComponents(
      outerContext.components.copy(
        module = DelegatingModuleDescriptorWithDelegatedDependencies(
          outerContext.components.module
        )
      )
    ), jPackage, Unit
  )

  private constructor(
    outerContext: LazyJavaResolverContext,
    jPackage: JavaPackage, unit: Unit = Unit
  ) : this(LazyJavaPackageFragment(outerContext, jPackage), outerContext, jPackage)
}

open class DelegatingLazyPackageDescriptor(open val delegate: LazyPackageDescriptor, resolveSession: ResolveSession) :
  LazyPackageDescriptor(delegate.module, delegate.fqName, resolveSession, delegate.declarationProvider) {
  override fun <R, D> accept(visitor: DeclarationDescriptorVisitor<R, D>, data: D): R {
    return delegate.accept(visitor, data)
  }

  override fun getContainingDeclaration(): ModuleDescriptor {
    return delegate.containingDeclaration
  }

  override fun getSource(): SourceElement {
    return delegate.source
  }

  override fun getName(): Name {
    return delegate.name
  }

  override fun getOriginal(): DeclarationDescriptorWithSource {
    return delegate.original
  }

  override fun acceptVoid(visitor: DeclarationDescriptorVisitor<Void, Void>?) {
    delegate.acceptVoid(visitor)
  }

  override fun getMemberScope(): MemberScope {
    return delegate.getMemberScope()
  }

  override fun forceResolveAllContents() {
    delegate.forceResolveAllContents()
  }

  override fun getDeclarationProvider(): PackageMemberDeclarationProvider {
    return delegate.declarationProvider
  }

  override fun toString(): String {
    return "DelegatingLazyPackageDescriptor(delegate=$delegate)"
  }

  override val annotations: Annotations
    get() = delegate.annotations
}


open class DelegatingPackageDescriptorImpl(open val delegate: PackageFragmentDescriptor) :
  PackageFragmentDescriptorImpl(delegate.module, delegate.fqName) {
  override fun <R, D> accept(visitor: DeclarationDescriptorVisitor<R, D>, data: D): R {
    return delegate.accept(visitor, data)
  }

  override fun getContainingDeclaration(): ModuleDescriptor {
    return delegate.containingDeclaration
  }

  override fun getSource(): SourceElement {
    return delegate.source
  }

  override fun getName(): Name {
    return delegate.name
  }

  override fun getOriginal(): DeclarationDescriptorWithSource {
    return delegate.original
  }

  override fun acceptVoid(visitor: DeclarationDescriptorVisitor<Void, Void>?) {
    delegate.acceptVoid(visitor)
  }

  override fun getMemberScope(): MemberScope {
    return delegate.getMemberScope()
  }

  override fun toString(): String {
    return "DelegatingPackageDescriptor(delegate=$delegate)"
  }

  override val annotations: Annotations
    get() = delegate.annotations
}

class MySpecialTrace : BindingTraceContext(true)

fun JavaResolverComponents.copy(
  storageManager: StorageManager = this.storageManager,
  finder: JavaClassFinder = this.finder,
  kotlinClassFinder: KotlinClassFinder = this.kotlinClassFinder,
  deserializedDescriptorResolver: DeserializedDescriptorResolver = this.deserializedDescriptorResolver,
  signaturePropagator: SignaturePropagator = this.signaturePropagator,
  errorReporter: ErrorReporter = this.errorReporter,
  javaResolverCache: JavaResolverCache = this.javaResolverCache,
  javaPropertyInitializerEvaluator: JavaPropertyInitializerEvaluator = this.javaPropertyInitializerEvaluator,
  samConversionResolver: SamConversionResolver = this.samConversionResolver,
  sourceElementFactory: JavaSourceElementFactory = this.sourceElementFactory,
  moduleClassResolver: ModuleClassResolver = this.moduleClassResolver,
  packagePartProvider: PackagePartProvider = this.packagePartProvider,
  supertypeLoopChecker: SupertypeLoopChecker = this.supertypeLoopChecker,
  lookupTracker: LookupTracker = this.lookupTracker,
  module: ModuleDescriptor = this.module,
  reflectionTypes: ReflectionTypes = this.reflectionTypes,
  annotationTypeQualifierResolver: AnnotationTypeQualifierResolver = this.annotationTypeQualifierResolver,
  signatureEnhancement: SignatureEnhancement = this.signatureEnhancement,
  javaClassesTracker: JavaClassesTracker = this.javaClassesTracker,
  settings: JavaResolverSettings = this.settings,
  kotlinTypeChecker: NewKotlinTypeChecker = this.kotlinTypeChecker,
  javaTypeEnhancementState: JavaTypeEnhancementState = this.javaTypeEnhancementState,
  javaModuleResolver: JavaModuleAnnotationsProvider = this.javaModuleResolver,
  syntheticPartsProvider: SyntheticJavaPartsProvider = this.syntheticPartsProvider
) = JavaResolverComponents(
  storageManager,
  finder,
  kotlinClassFinder,
  deserializedDescriptorResolver,
  signaturePropagator,
  errorReporter,
  javaResolverCache,
  javaPropertyInitializerEvaluator,
  samConversionResolver,
  sourceElementFactory,
  moduleClassResolver,
  packagePartProvider,
  supertypeLoopChecker,
  lookupTracker,
  module,
  reflectionTypes,
  annotationTypeQualifierResolver,
  signatureEnhancement,
  javaClassesTracker,
  settings,
  kotlinTypeChecker,
  javaTypeEnhancementState,
  javaModuleResolver,
  syntheticPartsProvider
)
/*
class MySpecialTrace(val project: Project, val module: ModuleDescriptor) : BindingTraceContext(true) {
  override fun <K, V> record(slice: WritableSlice<K, V>, key: K, value: V) {
    val newKey = key.withFixedContainingDeclaration(module)
    val newValue = value.withFixedContainingDeclaration(module)
    super.record(slice, newKey, newValue)
  }

  private val fixedContainingDeclarationMap = mutableMapOf<DeclarationDescriptor, DeclarationDescriptor?>()

  private fun <T> T.withFixedContainingDeclaration(module: ModuleDescriptor): T =
    if (this !is DeclarationDescriptor) this else (fixedContainingDeclarationMap.getOrPut(this) {
      safeAs<CallableMemberDescriptor>()?.let {
        if (it is PropertyAccessorDescriptor) {
          val index = it.correspondingProperty.accessors.indexOf(it)
          it.correspondingProperty.newCopyBuilder()
            .setOwner(it.containingDeclaration.withFixedContainingDeclaration(module)).build()?.accessors?.get(index)
        } else {
          it.newCopyBuilder().setOwner(it.containingDeclaration.withFixedContainingDeclaration(module)).build()
        }
      } ?: safeAs<ClassDescriptor>()?.let {
        DelegatingClassDescriptorWithCustomOwner(
          it,
          it.containingDeclaration.withFixedContainingDeclaration(module)
        )
      } ?: safeAs<ValueParameterDescriptor>()?.let {
        DelegatingValueParameterDescriptorWithCustomOwner(
          it,
          it.containingDeclaration.withFixedContainingDeclaration(module)
        )
      } ?: safeAs<VariableDescriptor>()?.let {
        DelegatingVariableDescriptorWithCustomOwner(
          it,
          it.containingDeclaration.withFixedContainingDeclaration(module)
        )
      } ?: safeAs<PackageFragmentDescriptor>()?.let { module.getPackage(it.fqName).fragments.first() }
      ?: safeAs<PackageViewDescriptor>()?.let { module.getPackage(it.fqName) }
    } ?: this) as T


}

open class DelegatingClassDescriptor(protected val delegate: ClassDescriptor) : ClassDescriptor by delegate {
  override fun getOriginal(): ClassDescriptor = this
}

class DelegatingClassDescriptorWithCustomOwner(delegate: ClassDescriptor, var owner: DeclarationDescriptor) :
  DelegatingClassDescriptor(delegate) {
  override fun getContainingDeclaration(): DeclarationDescriptor = owner
}

open class DelegatingValueParameterDescriptor(protected val delegate: ValueParameterDescriptor) :
  ValueParameterDescriptor by delegate {
  override fun getOriginal(): ValueParameterDescriptor = this
}

class DelegatingValueParameterDescriptorWithCustomOwner(
  delegate: ValueParameterDescriptor,
  var owner: CallableDescriptor
) :
  DelegatingValueParameterDescriptor(delegate) {
  override fun getContainingDeclaration(): CallableDescriptor = owner
}

open class DelegatingVariableDescriptor(protected val delegate: VariableDescriptor) : VariableDescriptor by delegate {
  override fun getOriginal(): VariableDescriptor = this
}

class DelegatingVariableDescriptorWithCustomOwner(delegate: VariableDescriptor, var owner: DeclarationDescriptor) :
  DelegatingVariableDescriptor(delegate) {
  override fun getContainingDeclaration(): DeclarationDescriptor = owner
}
*/

class RankedComponentProvider(val providers: List<StorageComponentContainer>) : ComponentProvider {
  constructor(vararg providers: StorageComponentContainer): this(providers.toList())
  override fun <T> create(request: Class<T>): T {
    var firstError: Result<T>? = null
    return providers.asSequence().map { kotlin.runCatching { it.create(request) } }.firstOrNull { it.isSuccess.apply { if(firstError == null) firstError = it } }?.getOrThrow() ?: firstError!!.getOrThrow()
  }

  override fun resolve(request: Type): ValueDescriptor? =
    providers.asSequence().map { kotlin.runCatching { it.resolve(request) } }.firstNotNullOfOrNull { it.getOrNull() }
}
