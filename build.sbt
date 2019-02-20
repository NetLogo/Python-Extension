enablePlugins(org.nlogo.build.NetLogoExtension)

enablePlugins(org.nlogo.build.ExtensionDocumentationPlugin)

netLogoVersion := "6.0.4-9328ba6"

netLogoClassManager := "org.nlogo.py.PythonExtension"

version := "0.3.0"

isSnapshot := true

netLogoExtName := "py"

netLogoZipSources := false

netLogoTarget := org.nlogo.build.NetLogoExtension.directoryTarget(baseDirectory.value)

scalaVersion := "2.12.8"

scalaSource in Compile := baseDirectory.value / "src"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature", "-Xfatal-warnings")

libraryDependencies += "org.json4s" %% "json4s-jackson" % "3.5.3"

netLogoPackageExtras += (baseDirectory(_ / "src" / "pyext.py").value, "pyext.py")
