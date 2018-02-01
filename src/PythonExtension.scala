package org.nlogo.py

import java.awt.GraphicsEnvironment
import java.io.{BufferedInputStream, BufferedOutputStream, BufferedReader, Closeable, File, FileInputStream, FileOutputStream, IOException, InputStreamReader}
import java.lang.ProcessBuilder.Redirect
import java.lang.{Boolean => JavaBoolean, Double => JavaDouble}
import java.net.{ServerSocket, Socket}
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ExecutorService, Executors, TimeUnit}

import com.fasterxml.jackson.core.JsonParser
import org.json4s.JsonAST.{JArray, JBool, JDecimal, JDouble, JInt, JLong, JNothing, JNull, JObject, JSet, JString, JValue}
import org.json4s.jackson.JsonMethods.{mapper, parse}
import org.nlogo.api
import org.nlogo.api.{Argument, Context, ExtensionException, ExtensionManager, OutputDestination, Workspace}
import org.nlogo.app.App
import org.nlogo.core.{Dump, LogoList, Nobody, Syntax}
import org.nlogo.nvm.{ExtensionContext, HaltException}
import org.nlogo.workspace.AbstractWorkspace

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.SyncVar
import scala.util.{Failure, Success, Try}

object PythonExtension {
  private var _pythonProcess: Option[PythonSubprocess] = None

  val extDirectory: File = new File(
    getClass.getClassLoader.asInstanceOf[java.net.URLClassLoader].getURLs()(0).toURI.getPath
  ).getParentFile

  val config: PythonConfig = PythonConfig(new File(extDirectory, "python.properties"))

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

  def waitFor[R](ctx: ExtensionContext)(result: SyncVar[R]): R =  {
    @tailrec
    def helper(): R = result.get(10) match {
      case Some(x) => x
      case None =>
        ctx.workspace.breathe(ctx.nvmContext)
        helper()
    }
    try helper()
    catch {
      case _: InterruptedException | _: HaltException =>
        killPython()
        throw new HaltException(true)
    }
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


  def start(ws: Workspace, pythonCmd: Seq[String]): PythonSubprocess = {
    val pyScript: String = new File(PythonExtension.extDirectory, "pyext.py").toString

    val port = findOpenPort
    ws.getExtensionManager

    val prefix = new File(ws.asInstanceOf[AbstractWorkspace].fileManager.prefix)
    // When running language tests, prefix is blank and, in general, processes can't run in non-existent directories.
    // So we default to the home directory.
    val workingDirectory = if (prefix.exists) prefix else new File(System.getProperty("user.home"))
    val pb = new ProcessBuilder(cmd(pythonCmd :+ pyScript :+ port.toString).asJava).directory(workingDirectory)
    val proc = try {
      pb.start()
    } catch {
      // TODO: Better error message here
      case e: IOException => throw new ExtensionException(s"Failed to find execution shell. This is a bug. Please report.", e)
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
    if (!proc.isAlive) {
      val stdout = readAllReady(new InputStreamReader(proc.getInputStream))
      val stderr = readAllReady(new InputStreamReader(proc.getErrorStream))
      val msg = (stderr, stdout) match {
        case ("", s) => s
        case (s, "") => s
        case (e, o) => s"Error output:\n$e\n\nOutput:\n$o"
      }
      throw new ExtensionException(
        "Python process failed to start:\n" +
        msg
      )
    }
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

  private def findOpenPort: Int = {
    var testServer: ServerSocket = null
    try {
      testServer = new ServerSocket(0)
      testServer.getLocalPort
    } finally {
      if (testServer != null) testServer.close()
    }
  }
}

class PythonSubprocess(ws: Workspace, proc : Process, socket: Socket) {
  private val shuttingDown = new AtomicBoolean(false)

  val in = new BufferedInputStream(socket.getInputStream)
  val out = new BufferedOutputStream(socket.getOutputStream)

  val stdout = new InputStreamReader(proc.getInputStream)
  val stderr = new InputStreamReader(proc.getErrorStream)

  private val executor: ExecutorService = Executors.newSingleThreadExecutor()

  def output(s: String): Unit = {
    if (GraphicsEnvironment.isHeadless || System.getProperty("org.nlogo.preferHeadless") == "true")
      println(s)
    else
      ws.outputObject(s, null, addNewline = true, readable = false, OutputDestination.Normal)
  }

  def redirectPipes(): Unit = {
    val stdoutContents = PythonSubprocess.readAllReady(stdout)
    val stderrContents = PythonSubprocess.readAllReady(stderr)
    if (stdoutContents.nonEmpty)
      output(stdoutContents)
    if (stderrContents.nonEmpty)
      output(s"Python error output:\n$stderrContents")
  }

  private def async[R](body: => Try[R]): SyncVar[Try[R]] = {
    val result = new SyncVar[Try[R]]
    executor.execute { () =>
      try result.put(body)
      catch {
        case _: IOException if shuttingDown.get =>
        case e: IOException =>
          result.put(Failure(
            new ExtensionException("Disconnected from Python unexpectedly. Try running py:setup again.", e)
          ))
        case _: InterruptedException =>
        case e: Exception => result.put(Failure(e))
      }
    }
    result
  }

  def exec(stmt: String): SyncVar[Try[Unit]] = async {
    sendStmt(stmt)
    val t = readByte()
    redirectPipes()
    if (t == 0) {
      Success(())
    } else {
      Failure(pythonException())
    }
  }

  def eval(expr: String): SyncVar[Try[AnyRef]] = async {
    sendExpr(expr)
    val t = readByte()
    redirectPipes()
    if (t == 0) {
      Success(readLogo())
    } else {
      Failure(pythonException())
    }
  }

  def assign(varName: String, value: AnyRef): Unit = {
    sendAssn(varName, value)
    val t = readByte()
    redirectPipes()
    if (t != 0) {
      throw pythonException()
    }
  }

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
    in.close()
    out.close()
    socket.close()
    proc.destroyForcibly()
    proc.waitFor(3, TimeUnit.SECONDS)
    if (proc.isAlive)
      throw new ExtensionException("Python process failed to shutdown. Please shut it down via your process manager")
  }
}

class PythonExtension extends api.DefaultClassManager {
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
      menuBar.getComponents.find(_.getName == PythonMenu.name).getOrElse {
        menuBar.add(new PythonMenu)
      }
    }
  }
  override def unload(em: ExtensionManager): Unit = {
    super.unload(em)
    PythonExtension.killPython()
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
    PythonExtension.waitFor(context.asInstanceOf[ExtensionContext]) {
      PythonExtension.pythonProcess.exec(args.map(_.getString).mkString("\n"))
    } match {
      case Failure(e) => throw e
      case _ =>
    }
}

object RunResult extends api.Reporter {
  override def getSyntax: Syntax = Syntax.reporterSyntax(
    right = List(Syntax.StringType | Syntax.RepeatableType),
    ret = Syntax.WildcardType
  )

  override def report(args: Array[Argument], context: Context): AnyRef =
    PythonExtension.waitFor(context.asInstanceOf[ExtensionContext]) {
      PythonExtension.pythonProcess.eval(args.map(_.getString).mkString("\n"))
    } match {
      case Failure(e) => throw e
      case Success(x) => x
    }
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

object Set extends api.Command {
  override def getSyntax: Syntax = Syntax.commandSyntax(right = List(Syntax.StringType, Syntax.ReadableType))
  override def perform(args: Array[Argument], context: Context): Unit =
    PythonExtension.pythonProcess.assign(args(0).getString, args(1).get)
}
