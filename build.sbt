import org.nlogo.build.{ NetLogoExtension, ExtensionDocumentationPlugin }

enablePlugins(NetLogoExtension)

enablePlugins(ExtensionDocumentationPlugin)

version    := "0.4.4"
isSnapshot := true

scalaVersion           := "2.12.12"
scalaSource in Test    := baseDirectory.value / "src" / "test"
scalaSource in Compile := baseDirectory.value / "src" / "main"
scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature", "-Xfatal-warnings")

netLogoVersion       := "6.2.2"
netLogoClassManager  := "org.nlogo.extensions.py.PythonExtension"
netLogoExtName       := "py"
netLogoZipSources    := false
netLogoTarget        := NetLogoExtension.directoryTarget(baseDirectory.value)
netLogoPackageExtras += (baseDirectory.value / "src" / "pyext.py", None)

resolvers           += "netlogo-lang-extension" at "https://dl.cloudsmith.io/public/netlogo/netlogoextensionlanguageserverlibrary/maven"
libraryDependencies ++= Seq(
  "org.json4s"              %% "json4s-jackson"     % "3.5.3",
  "org.nlogo.langextension" %% "lang-extension-lib" % "0.3"
)
