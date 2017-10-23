package org.nlogo.py

import java.awt.GraphicsEnvironment
import java.io.{BufferedInputStream, BufferedOutputStream, BufferedReader, File, IOException, InputStreamReader}
import java.lang.ProcessBuilder.Redirect
import java.lang.{Boolean => JavaBoolean, Double => JavaDouble}
import java.net.{ServerSocket, Socket}

import org.json4s.JsonAST.{JArray, JBool, JDecimal, JDouble, JInt, JLong, JNothing, JNull, JObject, JSet, JString, JValue}
import org.json4s.jackson.JsonMethods.parse
import org.nlogo.api
import org.nlogo.api.{Argument, Context, ExtensionException, ExtensionManager, OutputDestination, Workspace}
import org.nlogo.core.{Dump, LogoList, Nobody, Syntax}
import org.nlogo.workspace.AbstractWorkspace

import scala.collection.JavaConverters._

object PythonExtension {
  private var _pythonProcess: Option[PythonSubprocess] = None

  def pythonProcess: PythonSubprocess = _pythonProcess.getOrElse(throw new ExtensionException("Python process has not been started. Please run PY:SETUP before any other python extension primitive."))

  def pythonProcess_=(proc: PythonSubprocess): Unit = {
    _pythonProcess.foreach(_.close())
    _pythonProcess =  Some(proc)
  }
}

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
    val pyScript: String = new File(
      new File(
        // Getting the path straight from the URL will leave, eg, '%20's in the place of spaces. Converting to URI first
        // seems to prevent that.
        PythonExtension.getClass.getClassLoader.asInstanceOf[java.net.URLClassLoader].getURLs()(0).toURI.getPath
      ).getParentFile,
      "pyext.py"
    ).toString

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

  def python2: Option[File] = pythons.find(_.version._1 == 2).map(_.file)

  def python3: Option[File] = pythons.find(_.version._1 == 3).map(_.file)

  def anyPython: Option[File] = python3 orElse python2

  case class PythonBinary(file: File, version: (Int, Int, Int))

  def pythons: Stream[PythonBinary] =
    path.toStream
      .flatMap(_.listFiles((_, name) => name.toLowerCase.matches(raw"python[\d\.]*(?:\.exe)??")))
      .flatMap(pythonBinary)

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

  def pythonBinary(f: File): Option[PythonBinary] = {
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
  val in = new BufferedInputStream(socket.getInputStream)
  val out = new BufferedOutputStream(socket.getOutputStream)

  val stdout = new InputStreamReader(proc.getInputStream)
  val stderr = new InputStreamReader(proc.getErrorStream)

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

  def exec(stmt: String): Unit = {
    sendStmt(stmt)
    val t = readByte()
    redirectPipes()
    if (t != 0) {
      throw pythonException()
    }
  }

  def eval(expr: String): AnyRef = {
    sendExpr(expr)
    val t = readByte()
    redirectPipes()
    if (t == 0) {
      readLogo()
    } else {
      throw pythonException()
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
      throw new ExtensionException("Python process quit unexpectedly")
    }
    nextByte.toByte
  }

  private def readInt(): Int = {
    (readByte() << 24) & 0xff000000 |
      (readByte() << 16) & 0x00ff0000 |
      (readByte() << 8) & 0x0000ff00 |
      (readByte() << 0) & 0x000000ff
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
    case JDouble(num) => num: JavaDouble
    case JDecimal(num) => num.toDouble: JavaDouble
    case JLong(num) => num.toDouble: JavaDouble
    case JInt(num) => num.toDouble: JavaDouble
    case JBool(value) => value: JavaBoolean
    case JObject(obj) => LogoList.fromVector(obj.map(f => LogoList(f._1, toLogo(f._2))).toVector)
    case JArray(arr) => LogoList.fromVector(arr.map(toLogo).toVector)
    case JSet(set) => LogoList.fromVector(set.map(toLogo).toVector)
  }

  def close(): Unit = {
    in.close()
    out.close()
    socket.close()
    proc.destroy()
    proc.waitFor()
  }
}

class PythonExtension extends api.DefaultClassManager {
  override def load(manager: api.PrimitiveManager): Unit = {
    manager.addPrimitive("setup", SetupPython)
    manager.addPrimitive("run", Run)
    manager.addPrimitive("runresult", RunResult)
    manager.addPrimitive("set", Set)
    manager.addPrimitive("python2", FindPython(PythonSubprocess.python2 _))
    manager.addPrimitive("python3", FindPython(PythonSubprocess.python3 _))
    manager.addPrimitive("python", FindPython(PythonSubprocess.anyPython _))
    manager.addPrimitive("__path", Path)
  }

  override def unload(em: ExtensionManager): Unit = {
    super.unload(em)
    PythonExtension._pythonProcess.foreach(_.close())
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
    PythonExtension.pythonProcess.exec(args.map(_.getString).mkString("\n"))
}

case class FindPython(pyFinder: () => Option[File]) extends api.Reporter {
  override def report(args: Array[Argument], context: Context): String =
    pyFinder().getOrElse(
      throw new ExtensionException("Couldn't find Python. Try specifying an exact path.")
    ).getAbsolutePath

  override def getSyntax: Syntax = Syntax.reporterSyntax(ret = Syntax.StringType)
}

object Path extends api.Reporter {
  override def report(args: Array[Argument], context: Context): LogoList =
    LogoList.fromVector(PythonSubprocess.path.flatMap(_.listFiles.filter(_.getName.toLowerCase.matches(raw"python[\d\.]*(?:\.exe)??"))).map(_.getAbsolutePath).toVector)

  override def getSyntax: Syntax = Syntax.reporterSyntax(ret = Syntax.ListType)
}
object RunResult extends api.Reporter {
  override def getSyntax: Syntax = Syntax.reporterSyntax(
    right = List(Syntax.StringType | Syntax.RepeatableType),
    ret = Syntax.WildcardType
  )

  override def report(args: Array[Argument], context: Context): AnyRef =
    PythonExtension.pythonProcess.eval(args.map(_.getString).mkString("\n"))
}

object Set extends api.Command {
  override def getSyntax: Syntax = Syntax.commandSyntax(right = List(Syntax.StringType, Syntax.ReadableType))
  override def perform(args: Array[Argument], context: Context): Unit =
    PythonExtension.pythonProcess.assign(args(0).getString, args(1).get)
}
