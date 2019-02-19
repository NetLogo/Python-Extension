enablePlugins(org.nlogo.build.NetLogoExtension)

enablePlugins(org.nlogo.build.ExtensionDocumentationPlugin)

netLogoVersion := "6.0.4-9328ba6"

netLogoClassManager := "org.nlogo.py.PythonExtension"

version := "1.0.0"

netLogoExtName := "py"

netLogoZipSources := false

netLogoTarget := org.nlogo.build.NetLogoExtension.directoryTarget(baseDirectory.value / "py")

lazy val demoDir = baseDirectory(_ / "demos")
lazy val demoNames = List(
  "Flocking Clusters.nlogo",
  "Wolf Sheep Predation - Static 3D Plot.nlogo",
  "Wolf Sheep Predation - Real-time 3D Plot.nlogo",
  "Traffic Basic - Reinforcement.nlogo"
)
lazy val demoFiles = demoNames.map(n => demoDir(_ / n))

scalaVersion := "2.12.8"

scalaSource in Compile := baseDirectory.value / "src"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature", "-Xfatal-warnings")

libraryDependencies += "org.json4s" %% "json4s-jackson" % "3.5.3"

netLogoPackageExtras += (baseDirectory(_ / "pyext.py").value, "pyext.py")
netLogoPackageExtras += (baseDirectory(_ / "tests.txt").value, "tests.txt")

netLogoPackageExtras ++= {
  val demoDirVal = demoDir.value
  demoNames.map(n => (demoDirVal / n, s"demos/$n") )
}
