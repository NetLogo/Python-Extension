package org.nlogo.py

import java.awt.{BorderLayout, FileDialog, GridBagLayout, Insets, GridBagConstraints => GBC}
import java.io.File
import javax.swing.{BorderFactory, JButton, JDialog, JFrame, JLabel, JMenu, JPanel, JTextField}

import org.nlogo.app.App
import org.nlogo.core.I18N
import org.nlogo.swing.{ButtonPanel, RichAction, RichJButton, Utils}

class ConfigEditor(owner: JFrame, config: PythonConfig) extends JDialog(owner, "Python configuration") {
  private val python2TextField = new JTextField(config.python2.getOrElse(""), 20)
  private val python3TextField = new JTextField(config.python3.getOrElse(""), 20)

  {
    getContentPane.setLayout(new BorderLayout)
    val mainPanel = new JPanel
    mainPanel.setLayout(new BorderLayout)
    mainPanel.setBorder(BorderFactory.createEmptyBorder(5,5,5,5))
    getContentPane.add(mainPanel, BorderLayout.CENTER)

    val editPanel = new JPanel
    editPanel.setLayout(new GridBagLayout)

    editPanel.add(new JLabel(
      "Enter the path to your Python executable. If blank, the py extension will attempt to find an appropriate version of Python."
    ), Constraints(gridx=0, gridw=3))

    editPanel.add(new JLabel("Python 2:"), Constraints(gridx = 0))
    editPanel.add(python2TextField, Constraints(gridx = 1, weightx = 1.0, fill = GBC.HORIZONTAL))
    editPanel.add(RichJButton("Browse...") {
      askForPyPath("Python 2", python2TextField.getText).foreach(python2TextField.setText)
    }, Constraints(gridx = 2))

    editPanel.add(new JLabel("Python 3:"), Constraints(gridx = 0))
    editPanel.add(python3TextField, Constraints(gridx = 1,weightx = 1.0, fill = GBC.HORIZONTAL))
    editPanel.add(RichJButton("Browse...") {
      askForPyPath("Python 3", python3TextField.getText).foreach(python3TextField.setText)
    }, Constraints(gridx = 2))

    val okButton = RichJButton(I18N.gui.get("common.buttons.ok")) {
      save()
      dispose()
    }
    val cancelAction = RichAction(I18N.gui.get("common.buttons.cancel"))(_ => dispose())
    val buttonPanel = ButtonPanel(
      okButton,
      new JButton(cancelAction)
    )
    getRootPane.setDefaultButton(okButton)
    Utils.addEscKeyAction(this, cancelAction)

    mainPanel.add(editPanel, BorderLayout.CENTER)
    mainPanel.add(buttonPanel, BorderLayout.SOUTH)
    pack()
  }

  def askForPyPath(name: String, current: String): Option[String] = {
    val dialog = new FileDialog(this, s"Configure {name}", FileDialog.LOAD)
    dialog.setDirectory(new File(current).getParent)
    dialog.setFile(new File(current).getName)
    dialog.setVisible(true)
    Option(dialog.getFile).map(Option(dialog.getDirectory).getOrElse("") + _)
  }

  def save(): Unit = {
    config.python2 = python2TextField.getText
    config.python3 = python3TextField.getText
  }
}

object Constraints {
  def apply(
    gridx  : Integer = GBC.RELATIVE,
    gridy  : Integer = GBC.RELATIVE,
    gridw  : Integer = 1,
    gridh  : Integer = 1,
    weightx: Double  = 0.0,
    weighty: Double  = 0.0,
    anchor : Integer = GBC.CENTER,
    fill   : Integer = GBC.NONE,
    insets : Insets  = new Insets(0, 0, 0, 0),
    ipadx  : Integer = 0,
    ipady  : Integer = 0) =

    new GBC(gridx, gridy, gridw, gridh, weightx, weighty, anchor, fill, insets, ipadx, ipady)
}

object PythonMenu {
  val name = "Python"
}

class PythonMenu extends JMenu("Python") {
  add("Configure").addActionListener{ _ =>
    new ConfigEditor(App.app.frame, PythonExtension.config).setVisible(true)
  }
}
