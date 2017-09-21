enablePlugins(org.nlogo.build.NetLogoExtension)

netLogoVersion := "6.0.2"

netLogoClassManager := "org.nlogo.py.PythonExtension"

netLogoExtName := "py"

netLogoZipSources := false

netLogoTarget := org.nlogo.build.NetLogoExtension.directoryTarget(baseDirectory.value)

scalaVersion := "2.12.2"

scalaSource in Compile := baseDirectory.value / "src"


