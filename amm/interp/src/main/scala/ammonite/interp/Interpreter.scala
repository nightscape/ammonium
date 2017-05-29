package ammonite.interp

import java.io.{File, OutputStream, PrintStream}
import java.util.regex.Pattern

import scala.language.reflectiveCalls
import scala.collection.mutable
import scala.tools.nsc.Settings
import ammonite.ops._
import ammonite.runtime.Evaluator.addToClasspath
import ammonite.runtime._
import ammonite.runtime.tools.Resolver
import fastparse.all._

import annotation.tailrec
import ammonite.util.ImportTree
import ammonite.util.Util._
import ammonite.util._

import scala.reflect.io.VirtualDirectory


/**
 * A convenient bundle of all the functionality necessary
 * to interpret Scala code. Doesn't attempt to provide any
 * real encapsulation for now.
 */
class Interpreter(val printer: Printer,
                  val storage: Storage,
                  customPredefs: Seq[Interpreter.PredefInfo],
                  // Allows you to set up additional "bridges" between the REPL
                  // world and the outside world, by passing in the full name
                  // of the `APIHolder` object that will hold the bridge and
                  // the object that will be placed there. Needs to be passed
                  // in as a callback rather than run manually later as these
                  // bridges need to be in place *before* the predef starts
                  // running, so you can use them predef to e.g. configure
                  // the REPL before it starts
                  extraBridges: Interpreter => Seq[(String, String, AnyRef)],
                  val wd: Path,
                  verboseOutput: Boolean = true,
                  val eval: Evaluator = Interpreter.defaultEvaluator)
  extends ImportHook.InterpreterInterface{ interp =>

  def printBridge = "_root_.ammonite.repl.ReplBridge.value"


  //this variable keeps track of where should we put the imports resulting from scripts.
  private var scriptImportCallback: Imports => Unit = eval.update

  var lastException: Throwable = null

  private var _compilationCount = 0
  def compilationCount = _compilationCount


  val mainThread = Thread.currentThread()

  val dynamicClasspath = new VirtualDirectory("http://ammonite-memory-placeholder", None)
  var compiler: Compiler = null
  var pressy: Pressy = _
  val beforeExitHooks = mutable.Buffer.empty[Any ⇒ Any]

  def evalClassloader = eval.frames.head.classloader

  var addedDependencies0 = new mutable.ListBuffer[(String, String, String)]
  var addedJars0 = new mutable.HashSet[File]
  var dependencyExclusions = new mutable.ListBuffer[(String, String)]
  var profiles0 = new mutable.HashSet[String]
  var addedPluginDependencies = new mutable.ListBuffer[(String, String, String)]
  var addedPluginJars = new mutable.HashSet[File]

  def addedJars(plugin: Boolean) =
    if (plugin) addedPluginJars
    else addedJars0

  def reInit() = {
    if(compiler != null)
      init()
  }

  def initialSettings = {
    val settings = new Settings()
    settings.nowarnings.value = true
    settings
  }

  def init() = {
    // Note we not only make a copy of `settings` to pass to the compiler,
    // we also make a *separate* copy to pass to the presentation compiler.
    // Otherwise activating autocomplete makes the presentation compiler mangle
    // the shared settings and makes the main compiler sad
    val settings = Option(compiler).fold(initialSettings)(_.compiler.settings.copy)
    val classpath = Classpath.classpath(eval.frames.last.classloader.getParent) ++ eval.frames.head.classpath
    compiler = Compiler(
      classpath,
      dynamicClasspath,
      evalClassloader,
      eval.frames.head.pluginClassloader,
      () => pressy.shutdownPressy(),
      settings
    )
    pressy = Pressy(
      classpath,
      dynamicClasspath,
      evalClassloader,

      settings.copy()
    )
  }

  val bridges = extraBridges(this) :+ ("ammonite.runtime.InterpBridge", "interp", interpApi)
  for ((name, shortName, bridge) <- bridges ){
    APIHolder.initBridge(evalClassloader, name, bridge)
  }
  // import ammonite.repl.ReplBridge.{value => repl}
  // import ammonite.runtime.InterpBridge.{value => interp}
  val bridgePredefs =
    for ((name, shortName, bridge) <- bridges)
    yield Interpreter.PredefInfo(
      Name(s"${shortName}Bridge"),
      s"import $name.{value => $shortName}",
      true
    )


  val importHooks = Ref(Map[Seq[String], ImportHook](
    Seq("file") -> ImportHook.File,
    Seq("exec") -> ImportHook.Exec,
    Seq("ivy") -> ImportHook.Ivy,
    Seq("repo") -> ImportHook.Repository,
    Seq("exclude") -> ImportHook.IvyExclude,
    Seq("profile") -> ImportHook.MavenProfile,
    Seq("lib") -> ImportHook.Ivy,
    Seq("cp") -> ImportHook.Classpath,
    Seq("plugin", "ivy") -> ImportHook.PluginIvy,
    Seq("plugin", "exclude") -> ImportHook.PluginIvyExclude,
    Seq("plugin", "lib") -> ImportHook.PluginIvy,
    Seq("plugin", "cp") -> ImportHook.PluginClasspath
  ))

  val predefs = bridgePredefs ++ customPredefs ++ Seq(
    Interpreter.PredefInfo(Name("UserSharedPredef"), storage.loadSharedPredef, false),
    Interpreter.PredefInfo(Name("UserPredef"), storage.loadPredef, false)
  )

  // Use a var and a for-loop instead of a fold, because when running
  // `processModule0` user code may end up calling `processModule` which depends
  // on `predefImports`, and we should be able to provide the "current" imports
  // to it even if it's half built
  var predefImports = Imports()
  for {
    Interpreter.PredefInfo(wrapperName, sourceCode, hardcoded) <- predefs
    if sourceCode.nonEmpty
  }{
    val pkgName = Seq(Name("ammonite"), Name("predef"))

    processModule(
      ImportHook.Source(wd/s"${wrapperName.raw}.sc"),
      sourceCode,
      wrapperName,
      pkgName,
      true,
      "",
      hardcoded
    ) match{
      case Res.Success(processed) =>
        predefImports = predefImports ++ processed.finalImports
      case Res.Failure(ex, msg) =>
        ex match{
          case Some(e) => throw new RuntimeException("Error during Predef: " + msg, e)
          case None => throw new RuntimeException("Error during Predef: " + msg)
        }

      case Res.Exception(ex, msg) =>
        throw new RuntimeException("Error during Predef: " + msg, ex)
      case _ => ???
    }
  }

  reInit()



  def resolveSingleImportHook(source: ImportHook.Source, tree: ImportTree) = {
    val strippedPrefix = tree.prefix.takeWhile(_(0) == '$').map(_.stripPrefix("$"))
    val hookOpt = importHooks().collectFirst{case (k, v) if strippedPrefix.startsWith(k) => (k, v)}
    for{
      (hookPrefix, hook) <- Res(hookOpt, "Import Hook could not be resolved")
      hooked <- Res(
        hook.handle(source, tree.copy(prefix = tree.prefix.drop(hookPrefix.length)), this)
      )
      hookResults <- Res.map(hooked){
        case res: ImportHook.Result.Source =>
          for{
            processed <- processModule(
              res.source, res.code, res.wrapper, res.pkg,
              autoImport = false, extraCode = "", hardcoded = false
            )
          } yield {
            if (!res.exec) res.imports
            else processed.finalImports ++ res.imports

          }
        case res: ImportHook.Result.ClassPath =>

          if (res.plugin) handlePluginClasspath(res.files.map(_.toIO), res.coordinates)
          else interpApi0.load.doHandleClasspath(res.files.map(_.toIO), res.coordinates)

          Res.Success(Imports())
      }
    } yield {
      reInit()
      hookResults
    }
  }

  def resolveImportHooks(source: ImportHook.Source,
                         stmts: Seq[String]): Res[(Imports, Seq[String], Seq[ImportTree])] = {
      val hookedStmts = mutable.Buffer.empty[String]
      val importTrees = mutable.Buffer.empty[ImportTree]
      for(stmt <- stmts) {
        Parsers.ImportSplitter.parse(stmt) match{
          case f: Parsed.Failure => hookedStmts.append(stmt)
          case Parsed.Success(parsedTrees, _) =>
            var currentStmt = stmt
            for(importTree <- parsedTrees){
              if (importTree.prefix(0)(0) == '$') {
                val length = importTree.end - importTree.start
                currentStmt = currentStmt.patch(
                  importTree.start, (importTree.prefix(0) + ".$").padTo(length, ' '), length
                )
                importTrees.append(importTree)
              }
            }
            hookedStmts.append(currentStmt)
        }
      }

      for (hookImports <- Res.map(importTrees)(resolveSingleImportHook(source, _)))
      yield (Imports(hookImports.flatten.flatMap(_.value)), hookedStmts, importTrees)
    }

  def processLine(code: String, stmts: Seq[String], fileName: String): Res[Evaluated] = {
    val preprocess = Preprocessor(printBridge, compiler.parse)
    for{
      _ <- Catching { case ex =>
        Res.Exception(ex, "Something unexpected went wrong =(")
      }

      (hookImports, normalStmts, _) <- resolveImportHooks(
        ImportHook.Source(wd/"<console>"),
        stmts
      )

      processed <- preprocess.transform(
        normalStmts,
        eval.getCurrentLine,
        "",
        Seq(Name("$sess")),
        Name("cmd" + eval.getCurrentLine),
        predefImports ++ eval.frames.head.imports ++ hookImports,
        prints => s"$printBridge.Internal.combinePrints($prints)",
        extraCode = ""
      )
      out <- evaluateLine(
        processed, printer,
        fileName, Name("cmd" + eval.getCurrentLine),
        isExec = false
      )
    } yield out.copy(imports = out.imports ++ hookImports)

  }




  def withContextClassloader[T](t: => T) = {
    val oldClassloader = Thread.currentThread().getContextClassLoader
    try{
      Thread.currentThread().setContextClassLoader(evalClassloader)
      t
    } finally {
      Thread.currentThread().setContextClassLoader(oldClassloader)
    }
  }

  def compileClass(processed: Preprocessor.Output,
                   printer: Printer,
                   fileName: String): Res[(Util.ClassFiles, Imports)] = for {
    compiled <- Res.Success{
      if (sys.env.contains("DEBUG") || sys.props.contains("DEBUG")) println(s"Compiling\n${processed.code}\n")
      compiler.compile(processed.code.getBytes, printer, processed.prefixCharLength, fileName)
    }
    _ = _compilationCount += 1
    (classfiles, imports) <- Res[(Util.ClassFiles, Imports)](
      compiled,
      "Compilation Failed"
    )
  } yield {
    (classfiles, imports)
  }



  def evaluateLine(processed: Preprocessor.Output,
                   printer: Printer,
                   fileName: String,
                   indexedWrapperName: Name,
                   isExec: Boolean): Res[Evaluated] = {

    for{
      _ <- Catching{ case e: ThreadDeath => Evaluator.interrupted(e) }
      (classFiles, newImports) <- compileClass(
        processed,
        printer,
        fileName
      )
      res <- withContextClassloader{
        eval.processLine(
          classFiles,
          newImports,
          printer,
          fileName,
          isExec,
          indexedWrapperName
        )

      }
    } yield res
  }


  def processScriptBlock(processed: Preprocessor.Output,
                         printer: Printer,
                         wrapperName: Name,
                         fileName: String,
                         pkgName: Seq[Name]) = {
    for {
      (cls, newImports, tag) <- cachedCompileBlock(
        processed,
        printer,
        wrapperName,
        fileName,
        pkgName,
        "scala.Iterator[String]()"
      )
      res <- eval.processScriptBlock(cls, newImports, wrapperName, pkgName, tag)
    } yield res
  }



  def evalCachedClassFiles(source: ImportHook.Source,
                           cachedData: Seq[ClassFiles],
                           pkg: String,
                           wrapper: String,
                           dynamicClasspath: VirtualDirectory,
                           classFilesList: Seq[ScriptOutput.BlockMetadata]): Res[Seq[_]] = {
    Res.map(cachedData.zipWithIndex) {
      case (clsFiles, index) =>
        addToClasspath(clsFiles, dynamicClasspath)
        val blockMeta = classFilesList(index)
        blockMeta.importHookTrees.foreach(resolveSingleImportHook(source, _))
        val cls = eval.loadClass(blockMeta.id.wrapperPath, clsFiles)
        try cls.map(eval.evalMain(_))
        catch Evaluator.userCodeExceptionHandler
    }
  }

  def cachedCompileBlock(processed: Preprocessor.Output,
                         printer: Printer,
                         wrapperName: Name,
                         fileName: String,
                         pkgName: Seq[Name],
                         printCode: String): Res[(Class[_], Imports, String)] = {


      val fullyQualifiedName = (pkgName :+ wrapperName).map(_.encoded).mkString(".")

      val tag = Interpreter.cacheTag(
        processed.code, Nil, eval.frames.head.classloader.classpathHash
      )
      val compiled = storage.compileCacheLoad(fullyQualifiedName, tag) match {
        case Some((classFiles, newImports)) =>

          Evaluator.addToClasspath(classFiles, dynamicClasspath)
          Res.Success((classFiles, newImports))
        case _ =>
          val noneCalc = for {
            (classFiles, newImports) <- compileClass(
              processed, printer, fileName
            )
            _ = storage.compileCacheSave(fullyQualifiedName, tag, (classFiles, newImports))
          } yield {
            (classFiles, newImports)
          }

          noneCalc
      }
      for {
        (classFiles, newImports) <- compiled
        cls <- eval.loadClass(fullyQualifiedName, classFiles)
      } yield (cls, newImports, tag)
    }

  def processModule(source: ImportHook.Source,
                    code: String,
                    wrapperName: Name,
                    pkgName: Seq[Name],
                    autoImport: Boolean,
                    extraCode: String,
                    hardcoded: Boolean): Res[ScriptOutput.Metadata] = {

    val tag = Interpreter.cacheTag(
      code, Nil,
      if (hardcoded) Array.empty[Byte]
      else eval.frames.head.classloader.classpathHash
    )

    storage.classFilesListLoad(
      pkgName.map(_.backticked).mkString("."),
      wrapperName.backticked,
      tag
    ) match {
      case None =>
        printer.info("Compiling " + source.path)
        init()
        val res = processModule0(
          source, code, wrapperName, pkgName,
          predefImports, autoImport, extraCode
        )
        res match{
         case Res.Success(data) =>
           reInit()

           storage.classFilesListSave(
             pkgName.map(_.backticked).mkString("."),
             wrapperName.backticked,
             data.blockInfo,
             data.finalImports,
             tag
           )
           Res.Success(data)
         case r: Res.Failing => r
       }
      case Some(compiledScriptData) =>


        withContextClassloader(
          evalCachedClassFiles(
            source,
            compiledScriptData.classFiles,
            pkgName.map(_.backticked).mkString("."),
            wrapperName.backticked,
            dynamicClasspath,
            compiledScriptData.processed.blockInfo
          ) match {
            case Res.Success(_) =>
              eval.update(compiledScriptData.processed.finalImports)
              Res.Success(compiledScriptData.processed)
            case r: Res.Failing => r
          }
        )
    }
  }


  def preprocessScript(source: ImportHook.Source, code: String) = for{
    blocks <- Preprocessor.splitScript(Interpreter.skipSheBangLine(code))
    hooked <- Res.map(blocks){case (prelude, stmts) => resolveImportHooks(source, stmts) }
    (hookImports, hookBlocks, importTrees) = hooked.unzip3
  } yield (blocks.map(_._1).zip(hookBlocks), Imports(hookImports.flatMap(_.value)), importTrees)

  def processModule0(source: ImportHook.Source,
                     code: String,
                     wrapperName: Name,
                     pkgName: Seq[Name],
                     startingImports: Imports,
                     autoImport: Boolean,
                     extraCode: String): Res[ScriptOutput.Metadata] = {
    for{
      blocks <- Preprocessor.splitScript(Interpreter.skipSheBangLine(code))
      res <- processCorrectScript(
        blocks,
        startingImports,
        pkgName,
        wrapperName,
        (processed, wrapperIndex, indexedWrapperName) =>
          withContextClassloader(
            processScriptBlock(
              processed, printer,
              Interpreter.indexWrapperName(wrapperName, wrapperIndex),
              source.path.toString,
              pkgName
            )
          ),
        autoImport,
        silent = true,
        extraCode,
        source
      )
    } yield res
  }



  def processExec(code: String, silent: Boolean): Res[Imports] = {
    init()
    for {
      blocks <- Preprocessor.splitScript(Interpreter.skipSheBangLine(code))
      processedData <- processCorrectScript(
        blocks,
        eval.frames.head.imports,
        Seq(Name("$sess")),
        Name("cmd" + eval.getCurrentLine),
        { (processed, wrapperIndex, indexedWrapperName) =>
          evaluateLine(
            processed,
            printer,
            s"Main$wrapperIndex.sc",
            indexedWrapperName,
            isExec = true
          )
        },
        autoImport = true,
        silent = silent,
        "",
        ImportHook.Source(wd/"<console>")
      )
    } yield processedData.finalImports
  }



  def processCorrectScript(blocks: Seq[(String, Seq[String])],
                           startingImports: Imports,
                           pkgName: Seq[Name],
                           wrapperName: Name,
                           evaluate: Interpreter.EvaluateCallback,
                           autoImport: Boolean,
                           silent: Boolean,
                           extraCode: String,
                           source: ImportHook.Source): Res[ScriptOutput.Metadata] = {

    val preprocess = Preprocessor(printBridge, compiler.parse)
    // we store the old value, because we will reassign this in the loop
    val outerScriptImportCallback = scriptImportCallback

    /**
      * Iterate over the blocks of a script keeping track of imports.
      *
      * We keep track of *both* the `scriptImports` as well as the `lastImports`
      * because we want to be able to make use of any import generated in the
      * script within its blocks, but at the end we only want to expose the
      * imports generated by the last block to who-ever loaded the script
      */
    @tailrec def loop(blocks: Seq[(String, Seq[String])],
                      scriptImports0: Imports,
                      lastImports: Imports,
                      wrapperIndex: Int,
                      perBlockMetadata: List[ScriptOutput.BlockMetadata]): Res[ScriptOutput.Metadata] = {
      if (blocks.isEmpty) {
        // No more blocks
        // if we have imports to pass to the upper layer we do that
        if (autoImport) outerScriptImportCallback(lastImports)
        Res.Success(ScriptOutput.Metadata(lastImports, perBlockMetadata))
      } else {
        // imports from scripts loaded from this script block will end up in this buffer
        var nestedScriptImports = Imports()
        scriptImportCallback = { imports =>
          nestedScriptImports = nestedScriptImports ++ imports
        }
        // pretty printing results is disabled for scripts
        val indexedWrapperName = Interpreter.indexWrapperName(wrapperName, wrapperIndex)
        val (leadingSpaces, stmts) = blocks.head
        val res = for{
          (hookImports, hookStmts, hookImportTrees) <- resolveImportHooks(source, stmts)
          scriptImports = scriptImports0 ++ hookImports
          processed <- preprocess.transform(
            hookStmts,
            "",
            leadingSpaces,
            pkgName,
            indexedWrapperName,
            scriptImports,
            if (silent)
              _ => "scala.Iterator[String]()"
            else
              prints => s"$printBridge.Internal.combinePrints($prints)",
            extraCode = extraCode
          )

          ev <- evaluate(processed, wrapperIndex, indexedWrapperName)
        } yield (ev, scriptImports, hookImportTrees)

        res match {
          case r: Res.Failure => r
          case r: Res.Exception => r
          case Res.Success((ev, scriptImports, hookImportTrees)) =>
            val last = ev.imports ++ nestedScriptImports
            val newCompiledData = ScriptOutput.BlockMetadata(
              VersionedWrapperId(ev.wrapper.map(_.encoded).mkString("."), ev.tag),
              hookImportTrees
            )
            loop(
              blocks.tail,
              scriptImports ++ last,
              last,
              wrapperIndex + 1,
              newCompiledData :: perBlockMetadata
            )
          case Res.Skip => loop(
            blocks.tail,
            scriptImports0,
            lastImports,
            wrapperIndex + 1,
            perBlockMetadata
          )
          case _ => ???
        }
      }
    }
    // wrapperIndex starts off as 1, so that consecutive wrappers can be named
    // Wrapper, Wrapper2, Wrapper3, Wrapper4, ...
    try loop(blocks, startingImports, Imports(), wrapperIndex = 1, List())
    finally scriptImportCallback = outerScriptImportCallback
  }

  def handleOutput(res: Res[Evaluated]): Unit = {
    res match{
      case Res.Skip => // do nothing
      case Res.Exit(value) =>
        onExitCallbacks.foreach(_(value))
        pressy.shutdownPressy()
      case Res.Success(ev) => eval.update(ev.imports)
      case Res.Failure(ex, msg) => lastException = ex.getOrElse(lastException)
      case Res.Exception(ex, msg) => lastException = ex
    }
  }

  lazy val depThing = new ammonite.runtime.tools.DependencyThing(
    () => interpApi.repositories(),
    printer,
    verboseOutput
  )

  def exclude(coordinates: (String, String)*): Unit =
    dependencyExclusions ++= coordinates
  def addProfile(profile: String): Unit =
    profiles0 += profile
  def addRepository(repository: String): Unit = {

    val repo = Resolver.Http(
      "",
      repository.stripPrefix("ivy:"),
      "",
      m2 = !repository.startsWith("ivy:")
    )

    interpApi.repositories() = interpApi.repositories() :+ repo
  }
  def profiles: Set[String] =
    profiles0.toSet
  def addedDependencies(plugin: Boolean): Seq[(String, String, String)] =
    if (plugin) addedPluginDependencies
    else addedDependencies0
  def exclusions(plugin: Boolean): Seq[(String, String)] =
    if (plugin) Nil
    else dependencyExclusions
  def loadIvy(
    coordinates: Seq[(String, String, String)],
    previousCoordinates: Seq[(String, String, String)],
    exclusions: Seq[(String, String)]
  ) =
    depThing.resolveArtifacts(
      coordinates,
      previousCoordinates,
      exclusions,
      profiles
    ).toSet

  def handleEvalClasspath(jars: Seq[File], coords: Seq[(String, String, String)]) = {
    val newJars = jars.filterNot(addedJars0)
    eval.frames.head.addClasspath(newJars)
    for (jar <- newJars)
      evalClassloader.add(jar.toURI.toURL)
    addedJars0 ++= newJars
    addedDependencies0 ++= coords
    newJars
  }
  def handlePluginClasspath(jars: Seq[File], coords: Seq[(String, String, String)]): Seq[File] = {
    val newJars = jars.filterNot(addedPluginJars)
    for (jar <- newJars)
      eval.frames.head.pluginClassloader.add(jar.toURI.toURL)
    addedPluginJars ++= newJars
    addedPluginDependencies ++= coords
    newJars
  }
  def interpApi: InterpAPI = interpApi0
  private lazy val interpApi0: Interpreter.InterpAPIWithDefaultLoadJar = new Interpreter.InterpAPIWithDefaultLoadJar{ outer =>
    lazy val repositories = Ref(ammonite.runtime.tools.Resolvers.defaultResolvers)

    val load: Interpreter.DefaultLoadJar with Load = new Interpreter.DefaultLoadJar with Load {

      def interpreter = interp

      def isPlugin = false
      def handleClasspath(jars: Seq[File], coords: Seq[(String, String, String)]) =
        handleEvalClasspath(jars, coords)

      def apply(line: String, silent: Boolean) = processExec(line, silent) match{
        case Res.Failure(ex, s) => throw new CompilationError(s)
        case Res.Exception(t, s) => throw t
        case _ =>
      }

      def exec(file: Path): Unit = apply(normalizeNewlines(read(file)))

      def module(file: Path) = {
        val (pkg, wrapper) = Util.pathToPackageWrapper(file, wd)
        processModule(
          ImportHook.Source(wd/"Main.sc"),
          normalizeNewlines(read(file)),
          wrapper,
          pkg,
          true,
          "",
          hardcoded = false
        ) match{
          case Res.Failure(ex, s) => throw new CompilationError(s)
          case Res.Exception(t, s) => throw t
          case x => //println(x)
        }
        reInit()
      }

      def profiles = interp.profiles

      object plugin extends Interpreter.DefaultLoadJar {
        def interpreter = interp
        def isPlugin = true
        def handleClasspath(jars: Seq[File], coords: Seq[(String, String, String)]) =
          handlePluginClasspath(jars, coords)
      }

    }
  }

  var onExitCallbacks = Seq.empty[Any => Unit]
  def onExit(cb: Any => Unit): Unit = {
    onExitCallbacks = onExitCallbacks :+ cb
  }

}

object Interpreter{
  val SheBang = "#!"
  val SheBangEndPattern = Pattern.compile(s"""((?m)^!#.*)$newLine""")

  case class PredefInfo(name: Name, code: String, hardcoded: Boolean)

  /**
    * This gives our cache tags for compile caching. The cache tags are a hash
    * of classpath, previous commands (in-same-script), and the block-code.
    * Previous commands are hashed in the wrapper names, which are contained
    * in imports, so we don't need to pass them explicitly.
    */
  def cacheTag(code: String, imports: Seq[ImportData], classpathHash: Array[Byte]): String = {
    val bytes = Util.md5Hash(Iterator(
      Util.md5Hash(Iterator(code.getBytes)),
      Util.md5Hash(imports.iterator.map(_.toString.getBytes)),
      classpathHash
    ))
    bytes.map("%02x".format(_)).mkString
  }

  def skipSheBangLine(code: String)= {
    if (code.startsWith(SheBang)) {
      val matcher = SheBangEndPattern matcher code
      val shebangEnd = if (matcher.find) matcher.end else code.indexOf(newLine)
      val numberOfStrippedLines = newLine.r.findAllMatchIn( code.substring(0, shebangEnd) ).length
      (newLine * numberOfStrippedLines) + code.substring(shebangEnd)
    } else
      code
  }


  type EvaluateCallback = (Preprocessor.Output, Int, Name) => Res[Evaluated]


  def indexWrapperName(wrapperName: Name, wrapperIndex: Int): Name = {
    Name(wrapperName.raw + (if (wrapperIndex == 1) "" else "_" + wrapperIndex))
  }

  def initPrinters(output: OutputStream,
                   info: OutputStream,
                   error: OutputStream,
                   verboseOutput: Boolean) = {
    val colors = Ref[Colors](Colors.Default)
    val printStream = new PrintStream(output, true)
    val errorPrintStream = new PrintStream(error, true)
    val infoPrintStream = new PrintStream(info, true)

    def printlnWithColor(stream: PrintStream, color: fansi.Attrs, s: String) = {
      stream.println(color(s).render)
    }

    val printer = Printer(
      printStream.print,
      printlnWithColor(errorPrintStream, colors().warning(), _),
      printlnWithColor(errorPrintStream, colors().error(), _),
      s => if (verboseOutput) printlnWithColor(infoPrintStream, fansi.Attrs.Empty, s)
    )
    (colors, printStream, errorPrintStream, printer)
  }

  def initClassLoader = {

    @tailrec
    def findBaseLoader(cl: ClassLoader): Option[ClassLoader] =
      Option(cl) match {
        case Some(cl0) =>
          val isBaseLoader =
            try {
              cl0.asInstanceOf[AnyRef {def getIsolationTargets(): Array[String]}]
                .getIsolationTargets()
                .contains("ammonite")
            } catch {
              case _: NoSuchMethodException =>
                false
            }

          if (isBaseLoader)
            Some(cl0)
          else
            findBaseLoader(cl0.getParent)
        case None =>
          None
      }

    val cl = Thread.currentThread().getContextClassLoader

    findBaseLoader(cl).getOrElse(cl)
  }

  def defaultEvaluator = Evaluator(initClassLoader, 0)

  private abstract class DefaultLoadJar extends LoadJar {

    def interpreter: Interpreter

    def isPlugin: Boolean
    def handleClasspath(jars: Seq[File], coords: Seq[(String, String, String)]): Seq[File]

    def doHandleClasspath(jars: Seq[File], coords: Seq[(String, String, String)]): Seq[File] = {
      val newJars = handleClasspath(jars, coords)
      callbacks.foreach(_(newJars))
      newJars
    }

    def cp(jar: Path): Unit = {
      val f = new java.io.File(jar.toString)
      doHandleClasspath(Seq(f), Nil)
      interpreter.reInit()
    }
    def cp(jars: Seq[Path]): Unit = {
      doHandleClasspath(jars.map(_.toString).map(new java.io.File(_)), Nil)
      interpreter.reInit()
    }
    def ivy(coordinates: (String, String, String)*): Unit = {
      val resolved = interpreter.loadIvy(coordinates, interpreter.addedDependencies(isPlugin), interpreter.exclusions(isPlugin)) -- interpreter.addedJars(isPlugin)

      doHandleClasspath(resolved.toSeq, coordinates)

      interpreter.reInit()
    }

    private var callbacks = Seq.empty[Seq[java.io.File] => Unit]
    def onJarAdded(cb: Seq[java.io.File] => Unit): Unit = {
      callbacks = callbacks :+ cb
    }
  }
  private trait InterpAPIWithDefaultLoadJar extends InterpAPI {
    def load: DefaultLoadJar with Load
  }
}
