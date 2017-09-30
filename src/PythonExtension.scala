package org.nlogo.py

import java.io.{File, IOException, InputStreamReader, OutputStreamWriter}
import java.net.{ServerSocket, Socket}

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
  val lenSize = 10
  val typeSize = 1

  // Out types
  val stmtMsg = 0
  val exprMsg = 1

  // In types
  val successMsg = 0
  val errorMsg = 1


  def start(ws: Workspace, pythonCmd: String): PythonSubprocess = {
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
    val pb = new ProcessBuilder(cmd(pythonCmd, pyScript, port).asJava).directory(workingDirectory)
    val proc = try {
      pb.start()
    } catch {
      // TODO: Better error message here
      case e: IOException => throw new ExtensionException(s"Couldn't find Python executable: $pythonCmd", e)
    }
    var socket: Socket = null
    while (socket == null && proc.isAlive) {
      Thread.sleep(10)
      try {
        socket = new Socket("localhost", port)
      } catch {
        case _: IOException => // keep going
        case e: SecurityException => throw new ExtensionException(e)
      }
    }
    if (!proc.isAlive) throw new ExtensionException("Python process failed to start")

    new PythonSubprocess(ws, proc, socket)
  }

  private def cmd(pythonCmd: String, pythonScript: String, port: Int): List[String] = {
    val os = System.getProperty("os.name").toLowerCase

    val cmd = if (os.contains("mac") && System.getenv("PATH") == "/usr/bin:/bin:/usr/sbin:/sbin")
      // On MacOS, .app files are executed with a neutered PATH environment variable. The problem is that if users are
      // using Homebrew Python or similar, it won't be on that PATH. So, we check if we're on MacOS and if we have that
      // neuteredPATH. If so, we want to execute with the users actual PATH. We use `path_helper` to get that. It's not
      // perfect; it will miss PATHs defined in certain files, but hopefully it's good enough.
      List("/bin/bash", "-c",
        s"eval $$(/usr/libexec/path_helper -s) ; '$pythonCmd' '$pythonScript' $port")
    else
      List(pythonCmd, pythonScript, port.toString)
    cmd
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
  val in = new InputStreamReader(socket.getInputStream)
  val out = new OutputStreamWriter(socket.getOutputStream)

  val stdout = new InputStreamReader(proc.getInputStream)
  val stderr = new InputStreamReader(proc.getErrorStream)

  def redirectPipes(): Unit = {
    val stdoutContents = readAllReady(stdout)
    val stderrContents = readAllReady(stderr)
    if (stdoutContents.nonEmpty)
      ws.outputObject(
        stdoutContents, null,
        addNewline = true, readable = false,
        OutputDestination.Normal
      )
    if (stderrContents.nonEmpty)
      ws.outputObject(
        s"Python error output:\n$stderrContents", null,
        addNewline = true, readable = false,
        OutputDestination.Normal
      )
  }

  def readAllReady(in: InputStreamReader): String = {
    val sb = new StringBuilder
    while (in.ready) sb.append(in.read().toChar)
    sb.toString
  }

  def exec(stmt: String): Unit = {
    send(PythonSubprocess.stmtMsg, stmt)
    val l = read(PythonSubprocess.lenSize).toInt
    val t = read(PythonSubprocess.typeSize).toInt
    val r = read(l)
    redirectPipes()
    if (t != 0)
      throw new ExtensionException(r)
  }

  def eval(expr: String): AnyRef = {
    send(PythonSubprocess.exprMsg, expr)
    val l = read(PythonSubprocess.lenSize).toInt
    val t = read(PythonSubprocess.typeSize).toInt
    val r = read(l)
    redirectPipes()
    if (t == 0)
      ws.readFromString(r)
    else
      throw new ExtensionException(r)
  }

  private def send(msgType: Int, msg: String): Unit = {
    val fullMsg = s"%0${PythonSubprocess.lenSize}d%d%s".format(msg.length, msgType, msg)
    out.write(fullMsg)
    out.flush()
  }

  private def read(bytes: Int): String = {
    val sb = new StringBuilder
    for (_ <- 0 until bytes) {
      /* This can be used to prevent the UI from locking, but at a significant performance hit
      while (!in.ready) {
        ws.asInstanceOf[AbstractWorkspace].breathe()
        ws.world.wait(1) // Prevent UI from locking up while we wait
      }
      */
      val nextChar = in.read()
      if (nextChar == -1) {
        throw new ExtensionException("Python process quit unexpectedly")
      }
      sb.append(nextChar.toChar)
    }
    sb.toString
  }

  def close(): Unit = {
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
  }

  override def unload(em: ExtensionManager): Unit = {
    super.unload(em)
    PythonExtension._pythonProcess.foreach(_.close())
  }
}

object SetupPython extends api.Command {
  override def getSyntax: Syntax = Syntax.commandSyntax(
    right = List(Syntax.StringType)
  )

  override def perform(args: Array[Argument], context: Context): Unit = {
    context.workspace.getModelDir
    PythonExtension.pythonProcess = PythonSubprocess.start(context.workspace, args(0).getString)
  }
}

object Run extends api.Command {
  override def getSyntax: Syntax = Syntax.commandSyntax(
    right = List(Syntax.StringType | Syntax.RepeatableType)
  )

  override def perform(args: Array[Argument], context: Context): Unit =
    PythonExtension.pythonProcess.exec(args.map(_.getString).mkString("\n"))
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
  override def perform(args: Array[Argument], context: Context): Unit = {
    PythonExtension.pythonProcess.exec(s"${args(0).getString} = ${convertToPython(args(1).get)}")
  }

  def convertToPython(x: AnyRef): String = x match {
    case l: LogoList => "[" + l.map(convertToPython).mkString(", ") + "]"
    case b: java.lang.Boolean => if (b) "True" else "False"
    case Nobody => "None"
    case o => Dump.logoObject(o, readable = true, exporting = false)
  }
}