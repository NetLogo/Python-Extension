package org.nlogo.py

import java.io.File
import javax.swing.{JFileChooser, JMenu, SwingUtilities}

import org.nlogo.app.App
import org.nlogo.awt.UserCancelException
import org.nlogo.swing.{FileDialog, MessageDialog, OptionDialog}

object ConfigDialog {
  def configurePython2(): Unit =
    askForPyPath("Python 2", PythonSubprocess.python2)
      .foreach(PythonExtension.config.python2 = _)

  def configurePython3(): Unit =
    askForPyPath("Python 3", PythonSubprocess.python3)
      .foreach(PythonExtension.config.python3 = _)

  def configureEither(): Unit = onEDT {
    OptionDialog.showMessage(App.app.frame,
      "Configure Python extension",
      "Which version of Python would you like to configure?",
      Array("Python 3", "Python 2")
    ) match {
      case 0 => configurePython2()
      case 1 => configurePython3()
    }
  }

  def onEDT[R](body: =>R): R =
    if (SwingUtilities.isEventDispatchThread)
      body
    else
      App.app.workspace.waitForResult(() => body)

  protected def askForPyPath(name: String, current: Option[File]): Option[String] =
    onEDT {
      try {
        /*
        val chooser = new JFileChooser()
        current.foreach(chooser.setSelectedFile)
        if (chooser.showDialog(App.app.frame, "Select") == JFileChooser.APPROVE_OPTION)
          Some(chooser.getSelectedFile.getAbsolutePath)
        else
          None
          */


        Option(FileDialog.showFiles(App.app.frame, s"Configure $name",
          java.awt.FileDialog.LOAD,
          current.map(_.getAbsolutePath).getOrElse("")))
      } catch {
        case _: UserCancelException => None
      }
    }
}

object PythonMenu {
  val name = "Python"
}

class PythonMenu extends JMenu("Python") {
  add("Configure Python 2").addActionListener(_ => ConfigDialog.configurePython2())
  add("Configure Python 3").addActionListener(_ => ConfigDialog.configurePython3())
}
