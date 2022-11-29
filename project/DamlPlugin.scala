import java.io.{File, FileReader, FileWriter, IOException}
import java.nio.file.Paths
import java.util.{Map => JMap}

import com.esotericsoftware.yamlbeans.{YamlReader, YamlWriter}
import sbt.Keys._
import sbt.util.CacheStoreFactory
import sbt.util.FileFunction.UpdateFunction
import sbt.{Def, _}

import scala.annotation.tailrec
import scala.collection.mutable
import scala.sys.process._
import scala.util.{Failure, Success, Try}

/** Copied from Canton repo */
object DamlPlugin extends AutoPlugin {

  sealed trait Codegen
  object Codegen {
    object Java extends Codegen
    object Scala extends Codegen
  }

  object autoImport {
    val damlCodeGeneration =
      taskKey[Seq[(File, String)]](
        "List of tuples (Daml project directory, Daml archive file, name of the generated Java package)"
      )
    val damlSourceDirectory = settingKey[File]("Directory containing daml projects")
    val damlBuildOrder =
      settingKey[Seq[String]](
        "List of directory names used to sort the Daml building by order in this list"
      )
    val damlDarOutput = settingKey[File]("Directory to put generated DAR files in")
    val damlScalaCodegenOutput =
      settingKey[File]("Directory to put Scala sources generated from DARs")
    val damlJavaCodegenOutput =
      settingKey[File]("Directory to put Java sources generated from DARs")
    val damlCompilerVersion =
      settingKey[String]("The Daml version to use for DAR and code generation")
    val damlLanguageVersions =
      settingKey[Seq[String]]("The Daml-lf language versions supported by canton")
    val damlFixedDars = settingKey[Seq[String]](
      "Which DARs do we check in to avoid problems with package id versioning across daml updates"
    )
    val damlProjectVersionOverride =
      settingKey[Option[String]]("Allows hardcoding daml project version")
    val damlEnableJavaCodegen =
      settingKey[Boolean]("Enable Java codegen")
    val damlEnableScalaCodegen =
      settingKey[Boolean]("Enable Scala codegen")

    val damlGenerateCode = taskKey[Seq[File]]("Generate scala code from Daml")
    val damlDependencies = taskKey[Seq[File]]("Paths to DARs that this project depends on")
    val damlBuild = taskKey[Seq[File]]("Build a Daml Archive from Daml source")
    val damlTest = taskKey[Unit]("Run daml test")
    val damlStudio = taskKey[Unit]("Open Daml studio for all projects in scope")
    val damlCheckProjectVersions =
      taskKey[Unit]("Ensure that the versions specified in our SBT project match Daml projects")
    val damlUpdateProjectVersions =
      taskKey[Unit](
        "Update the versions used by our Daml projects to match the current values of the SBT project"
      )
    val damlUpdateFixedDars =
      taskKey[Unit]("Update the checked in DAR with a DAR built with the current Daml version")

    lazy val baseDamlPluginSettings: Seq[Def.Setting[_]] = Seq(
      sourceGenerators += damlGenerateCode.taskValue,
      resourceGenerators += damlBuild.taskValue,
      damlSourceDirectory := baseDirectory.value,
      damlDarOutput := damlSourceDirectory.value.getAbsoluteFile / ".daml" / "dist",
      damlDependencies := Seq(),
      damlScalaCodegenOutput := sourceManaged.value / "daml-codegen-scala",
      damlJavaCodegenOutput := sourceManaged.value / "daml-codegen-java",
      managedSourceDirectories ++= Seq(damlScalaCodegenOutput.value, damlJavaCodegenOutput.value),
      damlBuildOrder := Seq(),
      damlCodeGeneration := Seq(),
      damlEnableScalaCodegen := false,
      damlEnableJavaCodegen := true,
      damlGenerateCode := {
        // for the time being we assume if we're using code generation then the DARs must first be built
        damlBuild.value

        val settings = damlCodeGeneration.value
        val scalaOutputDirectory = damlScalaCodegenOutput.value
        val javaOutputDirectory = damlJavaCodegenOutput.value
        val cacheDirectory = streams.value.cacheDirectory
        val log = streams.value.log
        val enableJavaCodegen = damlEnableJavaCodegen.value
        val enableScalaCodegen = damlEnableScalaCodegen.value

        val cache = FileFunction.cached(cacheDirectory, FileInfo.hash) { input =>
          val codegens =
            (if (enableScalaCodegen) Seq((Codegen.Scala, scalaOutputDirectory)) else Seq.empty) ++
              (if (enableJavaCodegen) Seq((Codegen.Java, javaOutputDirectory)) else Seq.empty)
          settings.flatMap { case (darFile, packageName) =>
            codegens
              .flatMap { case (codegen, outputDirectory) =>
                IO.delete(outputDirectory)
                generateCode(
                  log,
                  darFile,
                  packageName,
                  codegen,
                  outputDirectory,
                  damlCompilerVersion.value,
                )
              }
          }.toSet
        }
        cache(settings.map(_._1).toSet).toSeq
      },
      damlBuild := {
        val dependencies = damlDependencies.value
        val outputDirectory = damlDarOutput.value
        val sourceDirectory = damlSourceDirectory.value
        // we don't really know dependencies between daml files, so just assume if any change then we need to rebuild all packages
        val cacheDir = streams.value.cacheDirectory
        // All daml files outside of .daml
        val allDamlFiles =
          damlSourceDirectory.value ** "*.daml" --- (damlSourceDirectory.value ** ".daml" ** "*.daml")
        val damlProjectFiles =
          damlSourceDirectory.value ** "daml.yaml"

        val buildDependencies = damlBuildOrder.value

        def buildOrder(fst: File, snd: File): Boolean = {
          def indexOf(file: File): Int = {
            val asString = file.toString
            buildDependencies.indexWhere(asString.contains(_))
          }
          val fstIdx = indexOf(fst)
          val sndIdx = indexOf(snd)
          if (fstIdx == -1 && sndIdx == -1) {
            fst.toString < snd.toString
          } else if (fstIdx == -1) {
            false
          } else if (sndIdx == -1) {
            true
          } else {
            fstIdx < sndIdx
          }
        }
        val log = streams.value.log

        val cache =
          FileFunction.cached(cacheDir) { _ => // ignoring the cache as we don't know the dependency

            // build the daml files in a sorted way, using the build order definition
            val projectFiles = damlProjectFiles.get.toList.sortWith(buildOrder)
            projectFiles.flatMap { projectFile =>
              buildDamlProject(
                log,
                sourceDirectory,
                outputDirectory,
                sourceDirectory.toPath.relativize(projectFile.toPath).toFile,
                damlCompilerVersion.value,
                damlLanguageVersions.value,
              )
            }.toSet
          }

        cache(allDamlFiles.get.toSet ++ dependencies).toSeq
      },
      // Declare dependency so that Daml packages in test scope may depend on packages in compile scope.
      (Test / damlBuild) := (Test / damlBuild).dependsOn(Compile / damlBuild).value,
      damlCheckProjectVersions := {
        val projectVersion = version.value
        val overrideVersion = damlProjectVersionOverride.value
        val damlProjectFiles = (damlSourceDirectory.value ** "daml.yaml").get

        damlProjectFiles.foreach(
          checkProjectVersions(
            overrideVersion.getOrElse(projectVersion),
            damlCompilerVersion.value,
            _,
          )
        )
      },
      damlUpdateProjectVersions := {
        // With Daml 0.13.56 characters are no longer allowed in project versions as
        // GHC does not like non-numbers in versions.
        val projectVersion = {
          val reg = "^([0-9]+\\.[0-9]+\\.[0-9])(-[^\\s]+)?$".r
          version.value match {
            case reg(vers, _) => vers
            case _ => throw new IllegalArgumentException(s"can not parse version ${version.value}")
          }
        }

        val overrideVersion = damlProjectVersionOverride.value
        val damlProjectFiles = (damlSourceDirectory.value ** "daml.yaml").get

        damlProjectFiles.foreach(
          updateProjectVersions(
            overrideVersion.getOrElse(projectVersion),
            damlCompilerVersion.value,
            _,
          )
        )
      },
      damlUpdateFixedDars := {
        val sourceDirectory = damlDarOutput.value
        val destinationDirectory = resourceDirectory.value / "dar"
        val fixedDars = damlFixedDars.value

        fixedDars.foreach(updateFixedDar(sourceDirectory, destinationDirectory, _))
      },
    )

    lazy val damlTestSetting =
      damlTest := {
        damlBuild.value
        val sourceDirectory = damlSourceDirectory.value
        val damlProjectFiles =
          sourceDirectory ** "daml.yaml"
        val log = streams.value.log
        val damlVersion = damlCompilerVersion.value
        val damlc = ensureDamlc(damlVersion, log)
        // so far canton system dars depend on daml-script, but maybe daml-triggers or others some day?
        val damlLibsEnv = ensureDamlLibsEnv(damlVersion, damlLanguageVersions.value, log)
        damlProjectFiles.get.toList.foreach { projectFile =>
          val projectDirectory = projectFile.toPath.toAbsolutePath.getParent
          val result = Process(
            command =
              Seq(damlc.getAbsolutePath, "test", "--project-root", projectDirectory.toString),
            cwd = projectDirectory.toFile,
            extraEnv = damlLibsEnv: _*,
          ) ! log
          if (result != 0) {
            throw new MessageOnlyException(s"""
                                              |damlc test failed ${projectDirectory}:
              """.stripMargin.trim)
          }
        }
      }

  }

  import autoImport._

  override lazy val globalSettings: Seq[Def.Setting[_]] = Seq(
    damlCompilerVersion := CantonDependencies.daml_compiler_version,
    damlLanguageVersions := CantonDependencies.daml_language_versions,
    damlCodeGeneration := Seq(),
    damlFixedDars := Seq(),
    damlProjectVersionOverride := None,
  )

  override lazy val projectSettings: Seq[Def.Setting[_]] =
    Seq(
      cleanFiles += (Compile / damlSourceDirectory).value.getAbsoluteFile / ".daml"
    ) ++
      inConfig(Compile)(baseDamlPluginSettings) ++
      inConfig(Test)(damlTestSetting)

  /** Verify that the versions in the daml.yaml file match what is being used in the sbt project.
    * If a mismatch is found a [[sbt.internal.MessageOnlyException]] will be thrown.
    */
  private def checkProjectVersions(
      projectVersion: String,
      damlVersion: String,
      damlProjectFile: File,
  ): Unit = {
    require(
      damlProjectFile.exists,
      s"supplied daml.yaml must exist [${damlProjectFile.absolutePath}]",
    )

    val values = readDamlYaml(damlProjectFile)
    ensureMatchingVersion(projectVersion, "version")
    ensureMatchingVersion(damlVersion, "sdk-version")

    def ensureMatchingVersion(sbtVersion: String, fieldName: String): Unit = {
      val damlVersion = values.get(fieldName).toString
      // With Daml 0.13.56 characters are no longer allowed in project versions as
      // GHC does not like non-numbers in versions.
      val sbtNonSnapshotVersion = sbtVersion.stripSuffix("-SNAPSHOT")
      if (sbtNonSnapshotVersion != damlVersion) {
        throw new MessageOnlyException(
          s"daml.yaml $fieldName value [$damlVersion] does not match the '-SNAPSHOT'-stripped value in our sbt project [$sbtVersion] in file [$damlProjectFile]"
        )
      }
    }
  }

  /** Write the project and daml versions of our sbt project to the given daml.yaml project file.
    */
  private def updateProjectVersions(
      projectVersion: String,
      damlVersion: String,
      damlProjectFile: File,
  ): Unit = {
    require(
      damlProjectFile.exists,
      s"supplied daml.yaml must exist [${damlProjectFile.absolutePath}]",
    )

    val values = readDamlYaml(damlProjectFile)
    values.put("version", projectVersion)
    values.put("sdk-version", damlVersion)

    val writer = new YamlWriter(new FileWriter(damlProjectFile))
    try {
      writer.write(values)
    } finally writer.close()
  }

  /** We intentionally take the unusual step of checking in certain DARs to ensure stable package ids across different Daml versions.
    * This task will take the dynamically built DAR and update the checked in version.
    */
  private def updateFixedDar(
      sourceDirectory: File,
      destinationDirectory: File,
      filename: String,
  ): Unit = {
    val sourcePath = sourceDirectory / filename
    val destinationPath = destinationDirectory / filename

    if (!sourcePath.exists) {
      throw new MessageOnlyException(
        s"Cannot update fixed DAR as DAR at path not found: [$sourcePath]"
      )
    }

    IO.copyFile(sourcePath, destinationPath)
  }

  private def artifactoryUrl(damlVersion: String) =
    s"https://storage.googleapis.com/daml-binaries/split-releases/${damlVersion}/"

  private def ensureDamlc(damlVersion: String, log: Logger) = {
    val os =
      if (System.getProperty("os.name").toLowerCase.startsWith("mac os x"))
        "macos"
      else
        "linux"
    ensureArtifactAvailable(
      url = artifactoryUrl(damlVersion),
      artifactFilename = s"damlc-${damlVersion}-$os.tar.gz",
      damlVersion = damlVersion,
      tarballPath = Seq("damlc", "damlc"),
      log = log,
    )
  }

  private def ensureDamlLibsEnv(
      damlVersion: String,
      damlLanguageVersions: Seq[String],
      log: Logger,
  ) = {
    // so far canton system dars depend on daml-script, but maybe daml-triggers or others some day?
    val damlLibsDependencyTypes = Seq("daml-script")
    val damlLibsDependencyVersions = damlLanguageVersions.foldLeft(Seq(""))(_ :+ "-" + _)
    (for {
      depType <- damlLibsDependencyTypes
      depVersion <- damlLibsDependencyVersions
    } yield {
      ensureArtifactAvailable(
        url = artifactoryUrl(damlVersion) + s"${depType}/",
        artifactFilename = s"${depType}${depVersion}.dar",
        damlVersion = damlVersion,
        localSubdir = Some("daml-libs"),
        log = log,
      )
    }).headOption.map("DAML_SDK" -> _.getParentFile.getParentFile.getAbsolutePath).toSeq
  }

  private def buildDamlProject(
      log: Logger,
      sourceDirectory: File,
      outputDirectory: File,
      relativeDamlProjectFile: File,
      damlVersion: String,
      damlLanguageVersions: Seq[String],
  ): Seq[File] = {

    val originalDamlProjectFile =
      sourceDirectory.toPath.resolve(relativeDamlProjectFile.toPath).toFile
    require(
      originalDamlProjectFile.exists,
      s"supplied daml.yaml must exist [${originalDamlProjectFile.absolutePath}]",
    )
    val projectDirectory = originalDamlProjectFile.getAbsoluteFile.getParentFile
    val url = artifactoryUrl(damlVersion)
    val damlc = ensureDamlc(damlVersion, log)

    val damlLibsEnv = ensureDamlLibsEnv(damlVersion, damlLanguageVersions, log)

    log.debug(
      s"building ${projectDirectory}"
    )

    val damlProjectName = readDamlYaml(originalDamlProjectFile).get("name").toString
    val damlProjectVersion = readDamlYaml(originalDamlProjectFile).get("version").toString
    val outputDar =
      outputDirectory / s"$damlProjectName-$damlProjectVersion.dar"

    val result = BuildUtil.runCommand(
      damlc.getAbsolutePath :: "build" ::
        "--project-root" :: projectDirectory.toString ::
        "--output" :: outputDar.getAbsolutePath :: Nil,
      log,
      optCwd = Some(projectDirectory),
      extraEnv = damlLibsEnv, // env variable set so that damlc finds daml-script dar
    )

    Seq(outputDar)
  }

  private def readDamlYaml(damlProjectFile: File): JMap[String, Object] = {
    val reader = new YamlReader(new FileReader(damlProjectFile))
    try {
      reader.read(classOf[JMap[String, Object]])
    } finally reader.close()
  }

  private def ensureArtifactAvailable(
      url: String,
      artifactFilename: String,
      damlVersion: String,
      log: Logger,
      tarballPath: Seq[String] = Seq.empty,
      localSubdir: Option[String] = None,
  ): File = {
    import better.files.File

    val root =
      localSubdir.foldLeft(
        File(System.getProperty("user.home")) / ".cache" / "daml-build" / damlVersion
      )(_ / _)

    val artifact =
      if (tarballPath.nonEmpty) tarballPath.foldLeft(root)(_ / _) else root / artifactFilename

    this.synchronized {
      if (!artifact.exists) {
        log.info(s"Downloading missing ${artifactFilename} to ${root.path}")
        root.createDirectoryIfNotExists(createParents = true)
        val curlWithBasicOptions = "curl" :: "-sSL" :: "--fail" :: Nil
        val credentials = url match {
          case artifactory if artifactory.startsWith("https://digitalasset.jfrog.io/") =>
            // CircleCI specifies ARTIFACTORY_ env variables
            val artifactoryUser = Option(System.getenv("ARTIFACTORY_USER")).getOrElse("")
            val artifactoryPassword = Option(System.getenv("ARTIFACTORY_PASSWORD")).getOrElse("")
            if (artifactoryUser.nonEmpty && artifactoryPassword.nonEmpty)
              "-u" :: s"${artifactoryUser}:${artifactoryPassword}" :: Nil
            else
              "--netrc" :: Nil // on dev machines look up artifactory credentials in ~/.netrc per https://everything.curl.dev/usingcurl/netrc
          case _maven => Nil // maven does not require credentials
        }
        val fileAndUrl =
          "-o" :: (root / artifactFilename).toJava.getPath :: (url + artifactFilename) :: Nil
        BuildUtil.runCommandWithRetries(
          curlWithBasicOptions ++ credentials ++ fileAndUrl,
          log,
          optError = Some(s"Failed to download from ${url + artifactFilename}"),
        )

        if (tarballPath.nonEmpty) {
          val tarball = root / artifactFilename
          log.info(s"Downloaded damlc tarball to ${root.path}. Untarring ${tarball.pathAsString}")
          BuildUtil.runCommand(
            "tar" :: "xzf" :: tarball.pathAsString :: Nil,
            log,
            optCwd = Some(root.toJava),
          )

          // best effort removal of tarball no longer needed to save space
          tarball.delete(swallowIOExceptions = true)
        }
      }

      artifact.toJava
    }
  }

  /** Calls the Daml Codegen for the provided DAR file (hence, is suitable to use in a sourceGenerator task)
    */
  def generateCode(
      log: Logger,
      darFile: File,
      basePackageName: String,
      language: Codegen,
      managedSourceDir: File,
      damlVersion: String,
  ): Seq[File] = {
    if (!darFile.exists())
      throw new MessageOnlyException(
        s"Codegen asked to generate code from nonexistent file: $darFile"
      )

    val (url, artifact, packageName, suffix) = language match {
      case Codegen.Java =>
        (
          s"https://repo.maven.apache.org/maven2/com/daml/codegen-java/${damlVersion}/",
          s"codegen-java-${damlVersion}.jar",
          basePackageName + ".java",
          "java",
        )
      case Codegen.Scala =>
        (
          s"https://repo.maven.apache.org/maven2/com/daml/codegen-scala-main/${damlVersion}/",
          s"codegen-scala-main-${damlVersion}.jar",
          basePackageName,
          "scala",
        )
    }

    val codegenJarPath = ensureArtifactAvailable(
      url = url,
      artifactFilename = artifact,
      damlVersion = damlVersion,
      log = log,
    ).getAbsolutePath

    log.debug(s"Running $language-codegen for ${darFile} into ${managedSourceDir}")

    BuildUtil.runCommand(
      "java" :: "-jar" :: codegenJarPath :: s"${darFile.getAbsolutePath}=$packageName" ::
        s"--output-directory=${managedSourceDir.getAbsolutePath}" :: Nil,
      log,
    )

    // return all generated scala files
    (managedSourceDir ** s"*.${suffix}").get
  }

}
