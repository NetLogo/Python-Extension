package org.nlogo.py

import java.io.{File, IOException, InputStreamReader, OutputStreamWriter}
import java.lang.ProcessBuilder.Redirect
import java.net.{ServerSocket, Socket}

import org.nlogo.api
import org.nlogo.api.{Argument, Context, ExtensionException, ExtensionManager, Workspace}
import org.nlogo.core.{Dump, LogoList, Syntax, Token}
import org.nlogo.workspace.AbstractWorkspace

object PythonExtension {
  var pythonProcess: PythonSubprocess = _
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
        PythonExtension.getClass.getClassLoader.asInstanceOf[java.net.URLClassLoader].getURLs()(0).getPath
      ).getParentFile,
      "pyext.py"
    ).toString
    val port = findOpenPort
    ws.getExtensionManager

    val pb = new ProcessBuilder()
      .command(pythonCmd, pyScript, port.toString)
      .redirectOutput(Redirect.INHERIT)
      .redirectError(Redirect.INHERIT)
    val proc = try {
      pb.start()
    } catch {
      case _: IOException => throw new ExtensionException(s"Couldn't find Python executable: $pythonCmd")
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

  def exec(stmt: String): Unit = {
    send(PythonSubprocess.stmtMsg, stmt)
    val l = read(PythonSubprocess.lenSize).toInt
    val t = read(PythonSubprocess.typeSize).toInt
    val r = read(l)
    if (t != 0)
      throw new ExtensionException(r)
  }

  def eval(expr: String): AnyRef = {
    send(PythonSubprocess.exprMsg, expr)
    val l = read(PythonSubprocess.lenSize).toInt
    val t = read(PythonSubprocess.typeSize).toInt
    val r = read(l)
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
    if (PythonExtension.pythonProcess != null) {
      PythonExtension.pythonProcess.close()
    }
  }
}

object SetupPython extends api.Command {
  override def getSyntax: Syntax = Syntax.commandSyntax(
    right = List(Syntax.StringType)
  )

  override def perform(args: Array[Argument], context: Context): Unit = {
    context.workspace.getModelDir
    if (PythonExtension.pythonProcess != null) {
      PythonExtension.pythonProcess.close()
    }
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
    case o => Dump.logoObject(o, readable = true, exporting = false)
  }
}