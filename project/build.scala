import sbt._
import Project.Setting
import Keys._

import GenTypeClass._

import java.awt.Desktop

import sbtrelease._
import sbtrelease.ReleasePlugin.autoImport._
import sbtrelease.ReleaseStateTransformations._
import sbtrelease.Utilities._

import com.typesafe.sbt.pgp.PgpKeys._

import com.typesafe.sbt.osgi.OsgiKeys
import com.typesafe.sbt.osgi.SbtOsgi._

import sbtbuildinfo.BuildInfoPlugin.autoImport._

import com.typesafe.tools.mima.plugin.MimaPlugin.mimaDefaultSettings
import com.typesafe.tools.mima.plugin.MimaKeys.mimaPreviousArtifacts

import scalanative.sbtplugin.ScalaNativePlugin.autoImport._
import scalajscrossproject.ScalaJSCrossPlugin.autoImport.{toScalaJSGroupID => _, _}
import sbtcrossproject.CrossPlugin.autoImport._
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.isScalaJSProject

object build {
  type Sett = Def.Setting[_]

  val rootNativeId = "rootNative"
  val nativeTestId = "nativeTest"

  lazy val publishSignedArtifacts = ReleaseStep(
    action = st => {
      val extracted = st.extract
      val ref = extracted.get(thisProjectRef)
      extracted.runAggregated(publishSigned in Global in ref, st)
    },
    check = st => {
      // getPublishTo fails if no publish repository is set up.
      val ex = st.extract
      val ref = ex.get(thisProjectRef)
      Classpaths.getPublishTo(ex.get(publishTo in Global in ref))
      st
    },
    enableCrossBuild = true
  )

  lazy val setMimaVersion: ReleaseStep = { st: State =>
    val extracted = Project.extract(st)
    val (releaseV, _) = st.get(ReleaseKeys.versions).getOrElse(sys.error("impossible"))
    IO.write(extracted get releaseVersionFile, s"""\nbuild.scalazMimaBasis in ThisBuild := "${releaseV}"\n""", append = true)
    reapply(Seq(scalazMimaBasis in ThisBuild := releaseV), st)
  }

  val scalaCheckVersion_1_12 = SettingKey[String]("scalaCheckVersion_1_12")
  val scalaCheckVersion_1_13 = SettingKey[String]("scalaCheckVersion_1_13")
  val kindProjectorVersion = SettingKey[String]("kindProjectorVersion")

  private[this] def gitHash(): String = sys.process.Process("git rev-parse HEAD").lines_!.head

  // no generic signatures for scala 2.10.x, see SI-7932, #571 and #828
  def scalac210Options = Seq("-Yno-generic-signatures")

  private[this] val tagName = Def.setting{
    s"v${if (releaseUseGlobalVersion.value) (version in ThisBuild).value else version.value}"
  }
  private[this] val tagOrHash = Def.setting{
    if(isSnapshot.value) gitHash() else tagName.value
  }

  val scalajsProjectSettings = Seq[Sett](
    scalacOptions += {
      val a = (baseDirectory in LocalRootProject).value.toURI.toString
      val g = "https://raw.githubusercontent.com/scalaz/scalaz/" + tagOrHash.value
      s"-P:scalajs:mapSourceURI:$a->$g/"
    }
  )

  lazy val notPublish = Seq(
    publishArtifact := false,
    publish := {},
    publishLocal := {},
    publishSigned := {},
    publishLocalSigned := {}
  )

  // avoid move files
  object ScalazCrossType extends sbtcrossproject.CrossType {
    override def projectDir(crossBase: File, projectType: String) =
      crossBase / projectType

    override def projectDir(crossBase: File, projectType: sbtcrossproject.Platform) = {
      val dir = projectType match {
        case JVMPlatform => "jvm"
        case JSPlatform => "js"
        case NativePlatform => "native"
      }
      crossBase / dir
    }

    def shared(projectBase: File, conf: String) =
      projectBase.getParentFile / "src" / conf / "scala"

    override def sharedSrcDir(projectBase: File, conf: String) =
      Some(shared(projectBase, conf))
  }

  private def Scala211 = "2.11.11"
  private def Scala212 = "2.12.4"

  private val SetScala211 = releaseStepCommand("++" + Scala211)

  private[this] val buildInfoPackageName = "scalaz"

  lazy val standardSettings: Seq[Sett] = Seq[Sett](
    unmanagedSourceDirectories in Compile += {
      val base = ScalazCrossType.shared(baseDirectory.value, "main").getParentFile
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, v)) if v >= 12 =>
          base / "scala-2.12+"
        case _ =>
          base / "scala-2.12-"
      }
    },
    organization := "org.scalaz",
    mappings in (Compile, packageSrc) ++= (managedSources in Compile).value.map{ f =>
      // https://github.com/sbt/sbt-buildinfo/blob/v0.7.0/src/main/scala/sbtbuildinfo/BuildInfoPlugin.scala#L58
      val buildInfoDir = "sbt-buildinfo"
      val path = if(f.getAbsolutePath.contains(buildInfoDir)) {
        (file(buildInfoPackageName) / f.relativeTo((sourceManaged in Compile).value / buildInfoDir).get.getPath).getPath
      } else {
        f.relativeTo((sourceManaged in Compile).value).get.getPath
      }
      (f, path)
    },
    scalaVersion := Scala212,
    crossScalaVersions := Seq("2.10.6", Scala211, Scala212, "2.13.0-M2"),
    resolvers ++= (if (scalaVersion.value.endsWith("-SNAPSHOT")) List(Opts.resolver.sonatypeSnapshots) else Nil),
    fullResolvers ~= {_.filterNot(_.name == "jcenter")}, // https://github.com/sbt/sbt/issues/2217
    scalaCheckVersion_1_12 := {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, v)) if v <= 11 =>
          "1.12.5"
        case _ =>
          "1.12.6"
      }
    },
    scalaCheckVersion_1_13 := "1.13.5",
    scalacOptions ++= Seq(
      // contains -language:postfixOps (because 1+ as a parameter to a higher-order function is treated as a postfix op)
      "-deprecation",
      "-encoding", "UTF-8",
      "-feature",
      "-Xfuture",
      "-language:implicitConversions", "-language:higherKinds", "-language:existentials", "-language:postfixOps",
      "-unchecked"
    ) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2,10)) => scalac210Options
      case Some((2,v)) if v >= 12 => Seq("-opt:l:method")
      case _ => Nil
    }),

    scalacOptions in (Compile, doc) ++= {
      val base = (baseDirectory in LocalRootProject).value.getAbsolutePath
      Seq("-sourcepath", base, "-doc-source-url", "https://github.com/scalaz/scalaz/tree/" + tagOrHash.value + "€{FILE_PATH}.scala")
    },

    // retronym: I was seeing intermittent heap exhaustion in scalacheck based tests, so opting for determinism.
    parallelExecution in Test := false,
    testOptions in Test += {
      val scalacheckOptions = Seq("-maxSize", "5", "-workers", "1", "-maxDiscardRatio", "50") ++ {
        if(isScalaJSProject.value)
          Seq("-minSuccessfulTests", "10")
        else
          Seq("-minSuccessfulTests", "33")
      }
      Tests.Argument(TestFrameworks.ScalaCheck, scalacheckOptions: _*)
    },
    genTypeClasses := {
      typeClasses.value.flatMap { tc =>
        val dir = name.value match {
          case ConcurrentName =>
            (scalaSource in Compile).value
          case _ =>
            ScalazCrossType.shared(baseDirectory.value, "main")
        }
        typeclassSource(tc).sources.map(_.createOrUpdate(dir, streams.value.log))
      }
    },
    checkGenTypeClasses := {
      val classes = genTypeClasses.value
      if(classes.exists(_._1 != FileStatus.NoChange))
        sys.error(classes.groupBy(_._1).filterKeys(_ != FileStatus.NoChange).mapValues(_.map(_._2)).toString)
    },
    typeClasses := Seq(),
    genToSyntax := {
      val tcs = typeClasses.value
      val objects = tcs.map(tc => "object %s extends To%sSyntax".format(Util.initLower(tc.name), tc.name)).mkString("\n")
      val all = "object all extends " + tcs.map(tc => "To%sSyntax".format(tc.name)).mkString(" with ")
      objects + "\n\n" + all
    },
    typeClassTree := {
      typeClasses.value.map(_.doc).mkString("\n")
    },

    showDoc in Compile := {
      val _ = (doc in Compile).value
      val out = (target in doc in Compile).value
      val index = out / "index.html"
      if (index.exists()) Desktop.getDesktop.open(out / "index.html")
    },

    credentialsSetting,
    publishSetting,
    publishArtifact in Test := false,

    // adapted from sbt-release defaults
    // (performs `publish-signed` instead of `publish`)
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runTest,
      SetScala211,
      releaseStepCommand(s"${nativeTestId}/run"),
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      publishSignedArtifacts,
      SetScala211,
      releaseStepCommand(s"${rootNativeId}/publishSigned"),
      setNextVersion,
      setMimaVersion,
      commitNextVersion,
      pushChanges
    ),
    releaseTagName := tagName.value,
    pomIncludeRepository := {
      x => false
    },
    pomExtra := (
      <url>http://scalaz.org</url>
        <licenses>
          <license>
            <name>BSD-style</name>
            <url>http://opensource.org/licenses/BSD-3-Clause</url>
            <distribution>repo</distribution>
          </license>
        </licenses>
        <scm>
          <url>git@github.com:scalaz/scalaz.git</url>
          <connection>scm:git:git@github.com:scalaz/scalaz.git</connection>
        </scm>
        <developers>
          {
          Seq(
            ("runarorama", "Runar Bjarnason"),
            ("pchiusano", "Paul Chiusano"),
            ("tonymorris", "Tony Morris"),
            ("retronym", "Jason Zaugg"),
            ("ekmett", "Edward Kmett"),
            ("alexeyr", "Alexey Romanov"),
            ("copumpkin", "Daniel Peebles"),
            ("rwallace", "Richard Wallace"),
            ("nuttycom", "Kris Nuttycombe"),
            ("larsrh", "Lars Hupel")
          ).map {
            case (id, name) =>
              <developer>
                <id>{id}</id>
                <name>{name}</name>
                <url>http://github.com/{id}</url>
              </developer>
          }
        }
        </developers>
      ),
    // kind-projector plugin
    libraryDependencies ++= (scalaBinaryVersion.value match {
      case "2.10" =>
        compilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.patch) :: Nil
      case _ =>
        Nil
    }),
    resolvers += Resolver.sonatypeRepo("releases"),
    kindProjectorVersion := "0.9.4",
    libraryDependencies += compilerPlugin("org.spire-math" % "kind-projector" % kindProjectorVersion.value cross CrossVersion.binary)
  ) ++ osgiSettings ++ Seq[Sett](
    OsgiKeys.additionalHeaders := Map("-removeheaders" -> "Include-Resource,Private-Package")
  ) ++ mimaDefaultSettings ++ Seq[Sett](
    mimaPreviousArtifacts := {
      val artifactId =
        if(isScalaJSProject.value) {
          s"${name.value}_sjs0.6_${scalaBinaryVersion.value}"
        } else {
          s"${name.value}_${scalaBinaryVersion.value}"
        }

      scalazMimaBasis.?.value.map {
        organization.value % artifactId % _
      }.toSet
    }
  )

  val nativeSettings = Seq(
    scalaVersion := Scala211,
    crossScalaVersions := Scala211 :: Nil
  )

  lazy val core = crossProject(JSPlatform, JVMPlatform, NativePlatform).crossType(ScalazCrossType)
    .settings(standardSettings)
    .settings(
      name := "scalaz-core",
      sourceGenerators in Compile += (sourceManaged in Compile).map{
        dir => Seq(GenerateTupleW(dir), TupleNInstances(dir))
      }.taskValue,
      buildInfoKeys := Seq[BuildInfoKey](version, scalaVersion),
      buildInfoPackage := buildInfoPackageName,
      osgiExport("scalaz"),
      OsgiKeys.importPackage := Seq("javax.swing;resolution:=optional", "*"))
    .enablePlugins(sbtbuildinfo.BuildInfoPlugin)
    .jsSettings(scalajsProjectSettings)
    .jvmSettings(
      typeClasses := TypeClass.core
    )
    .nativeSettings(
      nativeSettings
    )

  final val ConcurrentName = "scalaz-concurrent"

  lazy val effect = crossProject(JSPlatform, JVMPlatform, NativePlatform).crossType(ScalazCrossType)
    .settings(standardSettings)
    .settings(
      name := "scalaz-effect",
      osgiExport("scalaz.effect", "scalaz.std.effect", "scalaz.syntax.effect"))
    .dependsOn(core)
    .jsSettings(scalajsProjectSettings)
    .jvmSettings(
      typeClasses := TypeClass.effect
    )
    .nativeSettings(
      nativeSettings
    )

  lazy val iteratee = crossProject(JSPlatform, JVMPlatform, NativePlatform).crossType(ScalazCrossType)
    .settings(standardSettings)
    .settings(
      name := "scalaz-iteratee",
      osgiExport("scalaz.iteratee"))
    .dependsOn(core, effect)
    .jsSettings(scalajsProjectSettings)
    .nativeSettings(
      nativeSettings
    )

  lazy val publishSetting = publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (version.value.trim.endsWith("SNAPSHOT"))
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
  }

  lazy val credentialsSetting = credentials += {
    Seq("build.publish.user", "build.publish.password") map sys.props.get match {
      case Seq(Some(user), Some(pass)) =>
        Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", user, pass)
      case _                           =>
        Credentials(Path.userHome / ".ivy2" / ".credentials")
    }
  }

  lazy val scalazMimaBasis = SettingKey[String]("scalazMimaBasis", "Version of scalaz against which to run MIMA.")

  lazy val genTypeClasses = TaskKey[Seq[(FileStatus, File)]]("gen-type-classes")

  lazy val typeClasses = TaskKey[Seq[TypeClass]]("type-classes")

  lazy val genToSyntax = TaskKey[String]("gen-to-syntax")

  lazy val showDoc = TaskKey[Unit]("show-doc")

  lazy val typeClassTree = TaskKey[String]("type-class-tree", "Generates scaladoc formatted tree of type classes.")

  lazy val checkGenTypeClasses = TaskKey[Unit]("check-gen-type-classes")

  def osgiExport(packs: String*) = OsgiKeys.exportPackage := packs.map(_ + ".*;version=${Bundle-Version}")
}

// vim: expandtab:ts=2:sw=2
