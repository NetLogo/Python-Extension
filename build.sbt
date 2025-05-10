import org.nlogo.build.{ NetLogoExtension, ExtensionDocumentationPlugin }

enablePlugins(NetLogoExtension, ExtensionDocumentationPlugin)

version    := "0.5.5"
isSnapshot := true

scalaVersion          := "3.7.0"
Test / scalaSource    := baseDirectory.value / "src" / "test"
Compile / scalaSource := baseDirectory.value / "src" / "main"
scalacOptions        ++= Seq("-unchecked", "-deprecation", "-feature", "-Xfatal-warnings", "-release", "11")

netLogoVersion       := "7.0.0-beta1" // This extension gets its NL version from language-library; any update to its NL version has to be mirrored in that package and published in a new version --Jason B. (5/5/25)
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
  "org.nlogo.languagelibrary" %% "language-library" % "3.1.0"
)
