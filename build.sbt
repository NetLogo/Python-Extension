import org.nlogo.build.{ NetLogoExtension, ExtensionDocumentationPlugin }

enablePlugins(NetLogoExtension, ExtensionDocumentationPlugin)

version    := "0.5.3"
isSnapshot := true

scalaVersion           := "2.12.12"
scalaSource in Test    := baseDirectory.value / "src" / "test"
scalaSource in Compile := baseDirectory.value / "src" / "main"
scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature", "-Xfatal-warnings", "-Xlint", "-release", "11")

netLogoVersion       := "6.2.2"
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
  "org.nlogo.languagelibrary" %% "language-library" % "2.0.0"
)
