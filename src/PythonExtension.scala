package org.nlogo.py
import java.io.File

import jep.Jep
import org.nlogo.api.{Argument, Context}
import org.nlogo.core.Syntax
import org.nlogo.{api, core}

object PythonExtension {
  var jep: Jep = null
}

class PythonExtension extends api.DefaultClassManager {
  def load(manager: api.PrimitiveManager): Unit = {
    manager.addPrimitive("initialize", Initialize)
    manager.addPrimitive("run", Run)
    manager.addPrimitive("runresult", RunResult)
  }
}

object Initialize extends api.Command {
  override def getSyntax: Syntax = Syntax.commandSyntax(
    right = List(Syntax.StringType)
  )

  override def perform(args: Array[Argument], context: Context): Unit = {
    if (PythonExtension.jep != null) {
      PythonExtension.jep.close()
    }

    val slash = File.separator
    val pythonHome = args(0).getString
    System.setProperty(
      "PYTHON_HOME",
      pythonHome
    )
    System.setProperty(
      "java.library.path",
      new File(pythonHome + slash + "site-packages" + slash + "jep").toString + File.pathSeparator + System.getProperty("java.library.path")
    )
    val fieldSysPath = classOf[ClassLoader].getDeclaredField("sys_paths")
    fieldSysPath.setAccessible(true)
    fieldSysPath.set(null, null)

    /*
    val jepJar = new File(pythonHome + slash + "site-packages" + slash + "jep" + slash + "jep-3.7.0.jar")
    println(jepJar)
    println(jepJar.exists())
    val cl = ClassLoader.getSystemClassLoader.asInstanceOf[URLClassLoader]
    val addURL = cl.getClass.getDeclaredMethod("addURL", classOf[URL])
    addURL.setAccessible(true)
    addURL.invoke(cl, Array(jepJar.toURI.toURL))
    */

    //val cl = new URLClassLoader(Array(jepJar.toURI.toURL))
    //val cls = cl.loadClass("jep.Jep")
    PythonExtension.jep = new Jep(false)
  }
}

object Run extends api.Command {
  override def getSyntax: Syntax = Syntax.commandSyntax(
    right = List(Syntax.StringType)
  )

  override def perform(args: Array[Argument], context: Context): Unit = {
    PythonExtension.jep.eval(args(0).getString)
  }
}

object RunResult extends api.Reporter {
  override def getSyntax: Syntax = Syntax.reporterSyntax(
    right = List(Syntax.StringType),
    ret = Syntax.WildcardType
  )

  def convert(x: AnyRef): AnyRef = x match {
    case x: java.lang.Long => x.doubleValue(): java.lang.Double
    case x: java.lang.Double => x
    case s: String => s
    case l: java.lang.Iterable[Object] => l.asScala
    case x => x.getClass + " " + x.toString
  }

  override def report(args: Array[Argument], context: Context) = {
    PythonExtension.jep.getValue(args(0).getString) match {
    }
  }
}