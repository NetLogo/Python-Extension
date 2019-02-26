// (C) Uri Wilensky. https://github.com/NetLogo/Python-Extension

package org.nlogo.extensions.py

import org.nlogo.headless.TestLanguage

class Tests extends TestLanguage(Seq(new java.io.File("tests.txt").getCanonicalFile)) {
  System.setProperty("org.nlogo.preferHeadless", "true")
}
