package org.nlogo.extensions.py

import com.fasterxml.jackson.core.JsonParser
import org.json4s.jackson.JsonMethods.mapper
import org.me.{ShellWindow, Subprocess}
import org.me.Subprocess.path
import org.nlogo.api
import org.nlogo.api._
import org.nlogo.app.App
import org.nlogo.core.{LogoList, Syntax}
import org.nlogo.extensions.py.PythonExtension.shellWindow

import java.awt.GraphicsEnvironment
import java.io._
import java.lang.ProcessBuilder.Redirect
import java.util.Properties
import javax.swing.JMenu

object PythonExtension {
  private var _pythonProcess: Option[Subprocess] = None
  var shellWindow = new ShellWindow()

  val extDirectory: File = new File(
    getClass.getClassLoader.asInstanceOf[java.net.URLClassLoader].getURLs()(0).toURI.getPath
  ).getParentFile

  private val propFileName = "python.properties"

  private val extCheckFile = new File(extDirectory, propFileName)

  private val propFile = if (extCheckFile.exists) extCheckFile else new File(FileIO.perUserDir("py"), propFileName)

  val config: PythonConfig = PythonConfig(propFile)

  def pythonProcess: Subprocess =
    _pythonProcess.getOrElse(throw new ExtensionException(
      "Python process has not been started. Please run PY:SETUP before any other python extension primitive."))

  def pythonProcess_=(proc: Subprocess): Unit = {
    _pythonProcess.foreach(_.close())
    _pythonProcess =  Some(proc)
  }

  def killPython(): Unit = {
    _pythonProcess.foreach(_.close())
    _pythonProcess = None
  }

  def isHeadless: Boolean = GraphicsEnvironment.isHeadless || System.getProperty("org.nlogo.preferHeadless") == "true"

  def pythonNotFound = throw new ExtensionException("Couldn't find an appropriate version of Python. Please set the path to your Python executable in the configuration menu.")

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
 def python2: Option[File] =
    PythonExtension.config.python2.map(new File(_)).orElse(pythons.find(_.version._1 == 2).map(_.file))

  def python3: Option[File] =
    PythonExtension.config.python3.map(new File(_)).orElse(pythons.find(_.version._1 == 3).map(_.file))

  def anyPython: Option[File] = python3 orElse python2

  def pythons: Stream[PythonBinary] =
    path.toStream
      .flatMap(_.listFiles((_, name) => name.toLowerCase.matches(raw"python[\d\.]*(?:\.exe)??")))
      .flatMap(PythonBinary.fromFile)
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
    PythonExtension.shellWindow.setVisible(false)
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
    val pythonCmd = args.map(_.getString)
    val pyScript: String = new File(PythonExtension.extDirectory, "pyext.py").toString
    try {
      PythonExtension.pythonProcess = Subprocess.start(context.workspace, pythonCmd, Seq(pyScript), "py", "Python")
      PythonExtension.shellWindow.eval_stringified = Some(PythonExtension.pythonProcess.evalStringified)
    } catch {
      case e: Exception =>
        // Different errors can manifest in different operating systems. Thus, rather than dispatching in the specific
        // exception position, we catch all problems with Python bootup, look for common problems, and then offer advice
        // accordingly.
        val prefix = "Python failed to start."
        val wrongPathTip = "Check to make sure the correct path was entered in the Python configuration" +
            " menu or supply the correct path as an argument to PY:SETUP."
        val details = s"Details:\n\n${e.getLocalizedMessage}"
        val suffix = s"$wrongPathTip\n\n$details"

        val pythonFile = new File(pythonCmd.head)
        pythonFile.length()
        if (!pythonFile.exists) {
          throw new ExtensionException(
            s"$prefix Expected path to Python executable but '$pythonFile' does not exist. $suffix", e
          )
        } else if (pythonFile.isDirectory) {
          throw new ExtensionException(
            s"$prefix Expected path to Python executable but '$pythonFile' is a directory. $suffix", e
          )
        } else if (!pythonFile.canExecute) {
          throw new ExtensionException(s"$prefix NetLogo does not have permission to run '$pythonFile'. $suffix", e)
        } else {
          throw new ExtensionException(s"$prefix Encountered an error while running '$pythonFile'. $suffix", e)
        }
    }
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
  override def perform(args: Array[Argument], context: Context): Unit =
    PythonExtension.pythonProcess.assign(args(0).getString, args(1).get)
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
    LogoList.fromVector(Subprocess.path.flatMap(_.listFiles.filter(_.getName.toLowerCase.matches(raw"python[\d\.]*(?:\.exe)??"))).map(_.getAbsolutePath).toVector)

  override def getSyntax: Syntax = Syntax.reporterSyntax(ret = Syntax.ListType)
}
