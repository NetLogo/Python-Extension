enablePlugins(org.nlogo.build.NetLogoExtension)

enablePlugins(org.nlogo.build.ExtensionDocumentationPlugin)

resolvers      += "netlogo" at "https://dl.cloudsmith.io/public/netlogo/netlogo/maven/"
resolvers      += "netlogo-lang-extension" at "https://dl.cloudsmith.io/public/netlogo/netlogoextensionlanguageserverlibrary/maven"
netLogoVersion := "6.2.0-d27b502"

netLogoClassManager := "org.nlogo.extensions.py.PythonExtension"

version := "0.4.4"

isSnapshot := true

netLogoExtName := "py"

netLogoZipSources := false

netLogoTarget := org.nlogo.build.NetLogoExtension.directoryTarget(baseDirectory.value)

scalaVersion := "2.12.12"

scalaSource in Test := baseDirectory.value / "src" / "test"

scalaSource in Compile := baseDirectory.value / "src" / "main"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature", "-Xfatal-warnings")

libraryDependencies ++= Seq(
  "org.json4s"              %% "json4s-jackson"      % "3.5.3",
  "org.nlogo.langextension" %% "lang-extension-lib"  % "0.2",
  "com.typesafe"            % "config"               % "1.3.1"  % "test",
  "org.scalatest"           %% "scalatest"           % "3.0.0"  % "test",
  "org.picocontainer"       %  "picocontainer"       % "2.13.6" % "test",
  "org.ow2.asm"             %  "asm-all"             % "5.0.3"  % "test"
)

netLogoPackageExtras += (baseDirectory(_ / "src" / "pyext.py").value, "pyext.py")

val pyDirectory = settingKey[File]("directory that extension is moved to for testing")

pyDirectory := {
  baseDirectory.value / "extensions" / "py"
}

val moveToPyDir = taskKey[Unit]("add all resources to `py` directory")

moveToPyDir := {
  (packageBin in Compile).value
  val testTarget = NetLogoExtension.directoryTarget(pyDirectory.value)
  testTarget.create(NetLogoExtension.netLogoPackagedFiles.value)
  val testResources = ((baseDirectory.value / "test").allPaths).filter(_.isFile)
  for (file <- testResources.get)
    IO.copyFile(file, pyDirectory.value / "test" / IO.relativize(baseDirectory.value / "test", file).get)
  // Despite all this hard work to create an `extensions/py` dir for the NetLogo test suite to see and use
  // the `py.jar` that is loaded is the one created in the `package` task, so it needs the `pyext.py`
  val artifactPathExt = (artifactPath in (Compile, packageBin)).value.getParent
  IO.copyFile(pyDirectory.value / "pyext.py", new File(artifactPathExt) / "pyext.py")
}

test in Test := {
  IO.createDirectory(pyDirectory.value)
  moveToPyDir.value
  (test in Test).value
  IO.delete(pyDirectory.value)
}
