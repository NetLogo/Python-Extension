package org.nlogo.extensions.py

import java.awt.GraphicsEnvironment
import java.io.{BufferedInputStream, BufferedOutputStream, BufferedReader, Closeable, File, FileInputStream, FileOutputStream, IOException, InputStreamReader}
import java.lang.ProcessBuilder.Redirect
import java.lang.{Boolean => JavaBoolean, Double => JavaDouble}
import java.net.{ServerSocket, Socket}
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ExecutorService, Executors, TimeUnit}
import javax.swing.{JMenu, SwingUtilities}

import com.fasterxml.jackson.core.JsonParser
import org.json4s.JsonAST.{JArray, JBool, JDecimal, JDouble, JInt, JLong, JNothing, JNull, JObject, JSet, JString, JValue}
import org.json4s.jackson.JsonMethods.{mapper, parse}
import org.nlogo.api
import org.nlogo.api.Exceptions.ignoring
import org.nlogo.api.{Argument, Context, ExtensionException, ExtensionManager, FileIO, OutputDestination, Workspace}
import org.nlogo.app.App
import org.nlogo.core.{Dump, LogoList, Nobody, Syntax}
import org.nlogo.nvm.HaltException
import org.nlogo.workspace.AbstractWorkspace

import scala.collection.JavaConverters._
import scala.concurrent.SyncVar
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object PythonExtension {
  private var _pythonProcess: Option[PythonSubprocess] = None

  val extDirectory: File = new File(
    getClass.getClassLoader.asInstanceOf[java.net.URLClassLoader].getURLs()(0).toURI.getPath
  ).getParentFile

  private val propFileName = "python.properties"

  private val extCheckFile = new File(extDirectory, propFileName)

  private val propFile = if (extCheckFile.exists) extCheckFile else new File(FileIO.perUserDir("py"), propFileName)

  val config: PythonConfig = PythonConfig(propFile)

  def pythonProcess: PythonSubprocess =
    _pythonProcess.getOrElse(throw new ExtensionException(
      "Python process has not been started. Please run PY:SETUP before any other python extension primitive."))

  def pythonProcess_=(proc: PythonSubprocess): Unit = {
    _pythonProcess.foreach(_.close())
    _pythonProcess =  Some(proc)
  }

  def killPython(): Unit = {
    _pythonProcess.foreach(_.close())
    _pythonProcess = None
  }

  def isHeadless: Boolean = GraphicsEnvironment.isHeadless || System.getProperty("org.nlogo.preferHeadless") == "true"

  def pythonNotFound = throw new ExtensionException("Couldn't find an appropriate version of Python. Please set the path to your Python executable in the configuration menu.")

  def validNum(d: Double): Double = d match {
    case x if x.isInfinite => throw new ExtensionException("Python reported a number too large for NetLogo.")
    case x if x.isNaN => throw new ExtensionException("Python reported a non-numeric value from a mathematical operation.")
    case x => x
  }

}

object Haltable {
  def apply[R](body: => R): R = try body catch {
    case _: InterruptedException =>
      Thread.interrupted()
      PythonExtension.pythonProcess.invalidateJobs()
      throw new HaltException(true)
    case e: Throwable => throw e
  }
}

object Handle {
  /**
    * Deal with Exceptions (both inside the Try and non-fatal thrown) in a NetLogo-friendly way.
    */
  def apply[R](body: => Try[R]): R = Try(body).flatten match {
    case Failure(he: HaltException) => throw he// We can't actually catch throw InterruptedExceptions, but in case there's a wrapped one
    case Failure(ie: InterruptedException) =>
      Thread.interrupted()
      PythonExtension.pythonProcess.invalidateJobs()
      throw new HaltException(true)
    case Failure(ee: ExtensionException) => throw ee
    case Failure(ex: Exception) => throw new ExtensionException(ex)
    case Failure(th: Throwable) => throw th
    case Success(x) => x
  }

}


object Using {
  def apply[A <: Closeable, B](resource: A)(fn: A => B): B =
    apply(resource, (x: A) => x.close())(fn)
  def apply[A,B](resource: A, cleanup: A => Unit)(fn: A => B): B =
    try fn(resource) finally if (resource != null) cleanup(resource)
}

case class PythonConfig(configFile: File) {
  val py2Key: String = "python2"
  val py3Key: String = "python3"


  def properties: Option[Properties] = if (configFile.exists) {
    Using(new FileInputStream(configFile)) { f =>
      val props = new Properties
      props.load(f)
      Some(props)
    }
  } else None

  def setProperty(key: String, value: String): Unit = {
    val props = properties.getOrElse(new Properties)
    props.setProperty(key, value)
    Using(new FileOutputStream(configFile)) { f =>
      props.store(f, "")
    }
  }

  def python2: Option[String] =
    properties.flatMap(p => Option(p.getProperty(py2Key))).flatMap(p => if (p.trim.isEmpty) None else Some(p))

  def python2_= (p: String): Unit = setProperty(py2Key, p)

  def python3: Option[String] =
    properties.flatMap(p => Option(p.getProperty(py3Key))).flatMap(p => if (p.trim.isEmpty) None else Some(p))

  def python3_= (p: String): Unit = setProperty(py3Key, p)
}

object PythonBinary {
  def fromPath(s: String): Option[PythonBinary] = fromFile(new File(s))
  def fromFile(f: File): Option[PythonBinary] = {
    try {
      val proc = new ProcessBuilder(f.getAbsolutePath, "-V")
        .redirectError(Redirect.PIPE)
        .redirectInput(Redirect.PIPE)
        .start()
      Option(new BufferedReader(new InputStreamReader(proc.getInputStream)).readLine()).orElse(
        Option(new BufferedReader(new InputStreamReader(proc.getErrorStream)).readLine())
      ).flatMap { verString =>
        val m = """Python (\d+)\.(\d+)\.(\d+)""".r.findAllIn(verString)
        if (m.groupCount == 3) Some(PythonBinary(f, (m.group(1).toInt, m.group(2).toInt, m.group(3).toInt)))
        else None
      }
    } catch {
      case _: IOException => None
      case _: SecurityException => None
    }
  }
}

case class PythonBinary(file: File, version: (Int, Int, Int))

object PythonSubprocess {
  // In and out
  val typeSize = 1

  // Out types
  val stmtMsg = 0
  val exprMsg = 1
  val assnMsg = 2

  // In types
  val successMsg = 0
  val errorMsg = 1

  private val wrongPathTip = "Check to make sure the correct path was entered in the Python configuration" +
      " menu or supply the correct path as an argument to PY:SETUP."

  def start(ws: Workspace, pythonCmd: Seq[String]): PythonSubprocess = {

    def earlyFail(proc: Process, prefix: String) = {
      val stdout = readAllReady(new InputStreamReader(proc.getInputStream))
      val stderr = readAllReady(new InputStreamReader(proc.getErrorStream))
      val msg = (stderr, stdout) match {
        case ("", s) => s
        case (s, "") => s
        case (e, o) => s"Error output:\n$e\n\nOutput:\n$o"
      }
      throw new ExtensionException(s"$prefix\n$msg")
    }

    val pyScript: String = new File(PythonExtension.extDirectory, "pyext.py").toString

    ws.getExtensionManager

    val prefix = new File(ws.asInstanceOf[AbstractWorkspace].fileManager.prefix)
    // When running language tests, prefix is blank and, in general, processes can't run in non-existent directories.
    // So we default to the home directory.
    val workingDirectory = if (prefix.exists) prefix else new File(System.getProperty("user.home"))
    val pb = new ProcessBuilder(cmd(pythonCmd :+ pyScript).asJava).directory(workingDirectory)
    val proc = try {
      pb.start()
    } catch {
      case e: IOException => {
        val pythonFile = new File(pythonCmd.head)
        if (!pythonFile.exists()) {
          throw new ExtensionException(
            s"Expected path to Python executable but '$pythonFile' does not exist. $wrongPathTip"
          )
        } else if (pythonFile.isDirectory) {
          throw new ExtensionException(
            s"Expected path to Python executable but '$pythonFile' is a directory. $wrongPathTip"
          )
        } else if (!pythonFile.canExecute) {
          throw new ExtensionException(
            s"Cannot run '$pythonFile'. Check to make sure that that file is the Python executable and that NetLogo" +
                s" has permission to access and run it."
          )
        } else {
          throw new ExtensionException(s"${e.getLocalizedMessage}", e)
        }
      }
    }

    val pbInput = new BufferedReader(new InputStreamReader(proc.getInputStream))
    val portLine = pbInput.readLine

    val port = try {
      portLine.toInt
    } catch {
      case e: java.lang.NumberFormatException =>
        earlyFail(proc, s"Python process did not provide a port to connect with:\n$portLine. $wrongPathTip")
    }

    var socket: Socket = null
    while (socket == null && proc.isAlive) {
      try {
        socket = new Socket("localhost", port)
      } catch {
        case _: IOException => // keep going
        case e: SecurityException => throw new ExtensionException(e)
      }
    }
    if (!proc.isAlive) { earlyFail(proc, "Python process failed to start:") }
    new PythonSubprocess(ws, proc, socket)
  }

  def readAllReady(in: InputStreamReader): String = {
    val sb = new StringBuilder
    while (in.ready) sb.append(in.read().toChar)
    sb.toString
  }

  private def cmd(args: Seq[String]): Seq[String] = {
    val os = System.getProperty("os.name").toLowerCase
    if (os.contains("mac"))
      List("/bin/bash", "-l", "-c", args.map(a => s"'$a'").mkString(" "))
    else
      args
  }

  def python2: Option[File] =
    PythonExtension.config.python2.map(new File(_)).orElse(pythons.find(_.version._1 == 2).map(_.file))

  def python3: Option[File] =
    PythonExtension.config.python3.map(new File(_)).orElse(pythons.find(_.version._1 == 3).map(_.file))

  def anyPython: Option[File] = python3 orElse python2

  def pythons: Stream[PythonBinary] =
    path.toStream
      .flatMap(_.listFiles((_, name) => name.toLowerCase.matches(raw"python[\d\.]*(?:\.exe)??")))
      .flatMap(PythonBinary.fromFile)

  def path: Seq[File] = {
    val basePath = System.getenv("PATH")
    val os = System.getProperty("os.name").toLowerCase

    val unsplitPath = if (os.contains("mac") && basePath == "/usr/bin:/bin:/usr/sbin:/sbin")
    // On MacOS, .app files are executed with a neutered PATH environment variable. The problem is that if users are
    // using Homebrew Python or similar, it won't be on that PATH. So, we check if we're on MacOS and if we have that
    // neuteredPATH. If so, we want to execute with the users actual PATH. We use `path_helper` to get that. It's not
    // perfect; it will miss PATHs defined in certain files, but hopefully it's good enough.
      getCmdOutput("/bin/bash", "-l", "-c", "echo $PATH").head ++ basePath
    else
      basePath
    unsplitPath.split(File.pathSeparatorChar).map(new File(_)).filter(f => f.isDirectory)
  }

  private def getCmdOutput(cmd: String*): List[String] = {
    val proc = new ProcessBuilder(cmd: _*).redirectError(Redirect.PIPE).redirectInput(Redirect.PIPE).start()
    val in = new BufferedReader(new InputStreamReader(proc.getInputStream))
    Iterator.continually(in.readLine()).takeWhile(_ != null).toList
  }
}

class PythonSubprocess(ws: Workspace, proc : Process, socket: Socket) {
  // Signals to the executor thread that we're shutting down, so IOException's are to be expected.
  private val shuttingDown = new AtomicBoolean(false)
  // Used to distinguish if Python is not responding because it's busy doing something that we want it to do or if it's
  // busy doing something else.
  private val isRunningLegitJob = new AtomicBoolean(false)

  val in = new BufferedInputStream(socket.getInputStream)
  val out = new BufferedOutputStream(socket.getOutputStream)

  val stdout = new InputStreamReader(proc.getInputStream)
  val stderr = new InputStreamReader(proc.getErrorStream)

  private val executor: ExecutorService = Executors.newSingleThreadExecutor()

  def output(s: String): Unit = {
    if (GraphicsEnvironment.isHeadless || System.getProperty("org.nlogo.preferHeadless") == "true")
      println(s)
    else
      SwingUtilities.invokeLater { () =>
        // outputObject blocks, and we don't want to block.
        ws.outputObject(s, null, addNewline = true, readable = false, OutputDestination.Normal)
      }
  }

  def redirectPipes(): Unit = {
    val stdoutContents = PythonSubprocess.readAllReady(stdout)
    val stderrContents = PythonSubprocess.readAllReady(stderr)
    if (stdoutContents.nonEmpty)
      output(stdoutContents)
    if (stderrContents.nonEmpty)
      output(s"Python error output:\n$stderrContents")
  }

  /**
    * Runs the given code by submitting to a STE. Wraps all errors in Try.
    */
  private def async[R](body: => Try[R]): SyncVar[Try[R]] = {
    val result = new SyncVar[Try[R]]
    executor.execute { () =>
      try {
        isRunningLegitJob.set(true)
        result.put(body)
      } catch {
        case _: IOException if shuttingDown.get =>
        case e: IOException =>
          close()
          result.put(Failure(
            new ExtensionException("Disconnected from Python unexpectedly. Try running py:setup again.", e)
          ))
        case _: InterruptedException => Thread.interrupted()
        case e: Exception => result.put(Failure(e))
      } finally {
        isRunningLegitJob.set(false)
      }
    }
    result
  }

  /**
    * Uses the give send code to send to data python and the read code to read from Python. Handles response types and
    * pipe redirection.
    *
    * Python errors will be wrapped in Try, but IOExceptions will not.
    */
  private def run[R](send: => Unit)(read: => R): Try[R] = {
    send
    val t = readByte()
    val result = if (t == 0) {
      Success(read)
    } else {
      Failure(pythonException())
    }
    redirectPipes()
    result
  }

  /**
    * Checks to see if the Python process is responding. There are two reasons it may not be responding:
    * 1. Python is running a legit job that we are expecting the result of. This can only happen if we didn't block on
    * Python's response. In this case, we're okay with it not responding, so return Success
    * 2. A halt was called while Python was doing something long-running. It's still doing that thing, which might be
    * bad, so we fail in this case.
    *
    * The two cases are distinguished using `isRunningLegitJob`
    */
  def heartbeat(timeout: Duration = 1.seconds): Try[Unit] = if (!isRunningLegitJob.get) {
    val hb = async {
      // Technically this isn't necessary, since our STE should always be waiting on Python if it's doing something.
      // However, it's probably a good idea in case something goes wrong with the Python process or an STE job bugs out.
      run { sendStmt("") } {()}
    }
    hb.get(timeout.toMillis).getOrElse(
      Failure(new ExtensionException(
        "Python process is not responding. You can wait to see if it finishes what it's doing or restart it using py:setup."
      ))
    )
  } else Success(())

  def exec(stmt: String): Try[SyncVar[Try[Unit]]] =
    heartbeat().map(_ => async {
      run(sendStmt(stmt))(())
    })

  def eval(expr: String): Try[SyncVar[Try[AnyRef]]] =
    heartbeat().map(_ => async {
      run(sendExpr(expr))(readLogo())
    })

  def assign(varName: String, value: AnyRef): Try[SyncVar[Try[Unit]]] =
    heartbeat().map(_ => async {
      run(sendAssn(varName, value))(())
    })

  def pythonException(): Exception ={
    val e = readString()
    val tb = readString()
    new ExtensionException(e, new Exception(tb))
  }

  private def sendStmt(msg: String): Unit = {
    out.write(PythonSubprocess.stmtMsg)
    writeString(msg)
    out.flush()
  }

  private def sendExpr(msg: String): Unit = {
    out.write(PythonSubprocess.exprMsg)
    writeString(msg)
    out.flush()
  }

  private def sendAssn(varName: String, value: AnyRef): Unit = {
    out.write(PythonSubprocess.assnMsg)
    writeString(varName)
    writeString(toJson(value))
    out.flush()
  }

  private def read(numBytes: Int): Array[Byte] = Array.fill(numBytes)(readByte())

  private def readByte(): Byte = {
    val nextByte = in.read()
    if (nextByte == -1) {
      throw new IOException("Reached end of stream.")
    }
    nextByte.toByte
  }

  private def readInt(): Int = {
    (readByte() << 24) & 0xff000000 |
    (readByte() << 16) & 0x00ff0000 |
    (readByte() <<  8) & 0x0000ff00 |
    (readByte() <<  0) & 0x000000ff
  }

  private def readString(): String = {
    val l = readInt()
    val s = new String(read(l), "UTF-8")
    s
  }

  private def readLogo(): AnyRef = toLogo(readString())

  private def writeInt(i: Int): Unit = {
    val a = Array((i >>> 24).toByte, (i >>> 16).toByte, (i >>> 8).toByte, i.toByte)
    out.write(a)
  }

  private def writeString(str: String): Unit = {
    val bytes = str.getBytes("UTF-8")
    writeInt(bytes.length)
    out.write(bytes)
  }

  def toJson(x: AnyRef): String = x match {
    case l: LogoList => "[" + l.map(toJson).mkString(", ") + "]"
    case b: java.lang.Boolean => if (b) "true" else "false"
    case Nobody => "None"
    case o => Dump.logoObject(o, readable = true, exporting = false)
  }

  def toLogo(s: String): AnyRef = toLogo(parse(s))
  def toLogo(x: JValue): AnyRef = x match {
    case JNothing => Nobody
    case JNull => Nobody
    case JString(s) => s
    case JDouble(num) => PythonExtension.validNum(num): JavaDouble
    case JDecimal(num) => PythonExtension.validNum(num.toDouble): JavaDouble
    case JLong(num) => PythonExtension.validNum(num.toDouble): JavaDouble
    case JInt(num) => PythonExtension.validNum(num.toDouble): JavaDouble
    case JBool(value) => value: JavaBoolean
    case JObject(obj) => LogoList.fromVector(obj.map(f => LogoList(f._1, toLogo(f._2))).toVector)
    case JArray(arr) => LogoList.fromVector(arr.map(toLogo).toVector)
    case JSet(set) => LogoList.fromVector(set.map(toLogo).toVector)
  }

  def close(): Unit = {
    shuttingDown.set(true)
    executor.shutdownNow()
    ignoring(classOf[IOException])(in.close())
    ignoring(classOf[IOException])(out.close())
    ignoring(classOf[IOException])(socket.close())
    proc.destroyForcibly()
    proc.waitFor(3, TimeUnit.SECONDS)
    if (proc.isAlive)
      throw new ExtensionException("Python process failed to shutdown. Please shut it down via your process manager")
  }

  /**
    * CALL THIS ON HALT!
    */
  def invalidateJobs(): Unit = isRunningLegitJob.set(false)
}

class PythonExtension extends api.DefaultClassManager {
  var pyMenu: Option[JMenu] = None

  override def load(manager: api.PrimitiveManager): Unit = {
    manager.addPrimitive("setup", SetupPython)
    manager.addPrimitive("run", Run)
    manager.addPrimitive("runresult", RunResult)
    manager.addPrimitive("set", Set)
    manager.addPrimitive("python2",
      FindPython(
        PythonSubprocess.python2 _,
        PythonExtension.config.python2 _)
    )
    manager.addPrimitive("python3",
      FindPython(
        PythonSubprocess.python3 _,
        PythonExtension.config.python3 _)
    )
    manager.addPrimitive("python",
      FindPython(
        PythonSubprocess.anyPython _,
        () => PythonExtension.config.python3 orElse PythonExtension.config.python2
      )
    )
    manager.addPrimitive("__path", Path)
  }

  override def runOnce(em: ExtensionManager): Unit = {
    super.runOnce(em)
    mapper.configure(JsonParser.Feature.ALLOW_NON_NUMERIC_NUMBERS, true)

    if (!PythonExtension.isHeadless) {
      val menuBar = App.app.frame.getJMenuBar

      menuBar.getComponents.collectFirst {
        case mi: JMenu if mi.getText == PythonMenu.name => mi
      }.getOrElse {
        pyMenu = Option(menuBar.add(new PythonMenu))
      }
    }
  }
  override def unload(em: ExtensionManager): Unit = {
    super.unload(em)
    PythonExtension.killPython()
    if (!PythonExtension.isHeadless) {
      pyMenu.foreach(App.app.frame.getJMenuBar.remove _)
    }
  }
}

object SetupPython extends api.Command {
  override def getSyntax: Syntax = Syntax.commandSyntax(
    right = List(Syntax.StringType | Syntax.RepeatableType)
  )

  override def perform(args: Array[Argument], context: Context): Unit = {
    context.workspace.getModelDir
    PythonExtension.pythonProcess = PythonSubprocess.start(context.workspace, args.map(_.getString))
  }
}

object Run extends api.Command {
  override def getSyntax: Syntax = Syntax.commandSyntax(
    right = List(Syntax.StringType | Syntax.RepeatableType)
  )

  override def perform(args: Array[Argument], context: Context): Unit =
    Handle { Haltable {
      PythonExtension.pythonProcess.exec(args.map(_.getString).mkString("\n")).flatMap(_.get)
    } }
}

object RunResult extends api.Reporter {
  override def getSyntax: Syntax = Syntax.reporterSyntax(
    right = List(Syntax.StringType | Syntax.RepeatableType),
    ret = Syntax.WildcardType
  )

  override def report(args: Array[Argument], context: Context): AnyRef =
    Handle { Haltable {
      PythonExtension.pythonProcess.eval(args.map(_.getString).mkString("\n")).flatMap(_.get)
    } }
}

object Set extends api.Command {
  override def getSyntax: Syntax = Syntax.commandSyntax(right = List(Syntax.StringType, Syntax.ReadableType))
  override def perform(args: Array[Argument], context: Context): Unit =
    Handle { Haltable {
      PythonExtension.pythonProcess.assign(args(0).getString, args(1).get).flatMap(_.get)
    } }
}


case class FindPython(
  pyFinder: () => Option[File],
  getConfig: () => Option[String]) extends api.Reporter {

  override def report(args: Array[Argument], context: Context): String =
    pyFinder().map(_.getAbsolutePath).orElse(
      if (PythonExtension.isHeadless)
        None
      else {
        PythonExtension.pythonNotFound
      }
    ).getOrElse {
      throw new ExtensionException("Couldn't find Python 2. Try specifying an exact path or configuring a default Python 2.")
    }

  override def getSyntax: Syntax = Syntax.reporterSyntax(ret = Syntax.StringType)
}

object Path extends api.Reporter {
  override def report(args: Array[Argument], context: Context): LogoList =
    LogoList.fromVector(PythonSubprocess.path.flatMap(_.listFiles.filter(_.getName.toLowerCase.matches(raw"python[\d\.]*(?:\.exe)??"))).map(_.getAbsolutePath).toVector)

  override def getSyntax: Syntax = Syntax.reporterSyntax(ret = Syntax.ListType)
}
