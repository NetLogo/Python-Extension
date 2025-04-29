import org.nlogo.build.{ NetLogoExtension, ExtensionDocumentationPlugin }

enablePlugins(NetLogoExtension, ExtensionDocumentationPlugin)

version    := "0.5.5"
isSnapshot := true

scalaVersion          := "2.13.16"
Test / scalaSource    := baseDirectory.value / "src" / "test"
Compile / scalaSource := baseDirectory.value / "src" / "main"
scalacOptions        ++= Seq("-unchecked", "-deprecation", "-feature", "-Xfatal-warnings", "-Xlint", "-release", "11")

netLogoVersion       := "7.0.0-internal1"
netLogoClassManager  := "org.nlogo.extensions.py.PythonExtension"
netLogoExtName       := "py"
netLogoPackageExtras += (baseDirectory.value / "src" / "pyext.py", None)

Compile / packageBin / artifactPath := {
  val oldPath = (Compile / packageBin / artifactPath).value.toPath
  val newPath = oldPath.getParent / s"${netLogoExtName.value}.jar"
  newPath.toFile
}

resolvers           += "netlogo-language-library" at "https://dl.cloudsmith.io/public/netlogo/language-library/maven"
libraryDependencies ++= Seq(
  "org.nlogo.languagelibrary" %% "language-library" % "3.0.0"
)
