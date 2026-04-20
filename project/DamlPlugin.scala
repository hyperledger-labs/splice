// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import java.io.{File, FileReader, FileWriter}
import java.util.{Map => JMap}

import com.esotericsoftware.yamlbeans.{YamlReader, YamlWriter}
import sbt.Keys._
import sbt.{Def, _}

import scala.sys.process._
import scala.util.{Failure, Success, Try}

/** Copied from Canton repo */
object DamlPlugin extends AutoPlugin {

  object autoImport {
    val damlTestTag = Tags.Tag("daml-test")
    val damlCodeGeneration =
      taskKey[Seq[(File, File, String)]](
        "List of tuples (Daml project directory, Daml archive file, name of the generated Java package)"
      )
    val damlSourceDirectory = settingKey[File]("Directory containing daml projects")
    val damlBuildOrder =
      settingKey[Seq[String]](
        "List of directory names used to sort the Daml building by order in this list"
      )
    val damlDarOutput = settingKey[File]("Directory to put generated DAR files in")
    val damlDarLfVersion =
      settingKey[String]("Lf version for which to generate DAR files")
    val damlJavaCodegenOutput =
      settingKey[File]("Directory to put Java sources generated from DARs")
    val damlCompilerVersion =
      settingKey[String]("Daml compiler version")
    val damlJavaCodegenVersion =
      settingKey[String]("Java codegen version")
    val damlLanguageVersions =
      settingKey[Seq[String]]("The Daml-lf language versions supported by canton")
    val damlFixedDars = settingKey[Seq[String]](
      "Which DARs do we check in to avoid problems with package id versioning across daml updates"
    )
    val damlProjectVersionOverride =
      settingKey[Option[String]]("Allows hardcoding daml project version")
    val damlEnableJavaCodegen =
      settingKey[Boolean]("Enable Java codegen")
    // Canton is setup such that CLI arguments need to be passed explicitly instead of read from daml.yaml
    // so we set this flag to false for Canton.
    val damlCodegenUseProject =
      settingKey[Boolean]("Read config from daml.yaml")
    val damlPrebuiltDar =
      settingKey[Option[File]](
        "Skip Daml compilation and use this pre-built DAR instead. The pre-built DAR will be copied into the damlDarOutput, so that downstream tasks e.g. codegen don't need to be aware of whether we built the dars or not."
      )
        .withRank(
          KeyRanks.Invisible
        ) // suppresses unused warnings in canton-community-app where we exclude damlBuild altogether

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
      damlDarLfVersion := "",
      damlDependencies := Seq(),
      damlJavaCodegenOutput := target.value / "daml-codegen-java",
      managedSourceDirectories ++= Seq(damlJavaCodegenOutput.value),
      damlBuildOrder := Seq(),
      damlCodeGeneration := Seq(),
      damlEnableJavaCodegen := true,
      damlCodegenUseProject := true,
      damlPrebuiltDar := None,
      damlGenerateCode := {
        // for the time being we assume if we're using code generation then the DARs must first be built
        val dars = damlBuild.value
        val damlProjectFiles = (damlSourceDirectory.value ** "daml.yaml").get
        val settings = damlCodeGeneration.value
        val javaOutputDirectory = damlJavaCodegenOutput.value
        val cacheDirectory = streams.value.cacheDirectory
        val log = streams.value.log
        val enableJavaCodegen = damlEnableJavaCodegen.value
        val enableUseProject = damlCodegenUseProject.value

        val cache = FileFunction.cached(cacheDirectory, FileInfo.hash) { input =>
          val codegens =
            if (enableJavaCodegen) Seq(javaOutputDirectory) else Seq.empty
          codegens.foreach { (outputDirectory) => IO.delete(outputDirectory) }
          settings.flatMap { case (projectDir, darFile, packageName) =>
            codegens
              .flatMap { (outputDirectory) =>
                generateCode(
                  log,
                  projectDir,
                  darFile,
                  packageName,
                  outputDirectory,
                  damlCompilerVersion.value,
                  damlJavaCodegenVersion.value,
                  enableUseProject,
                )
              }
          }.toSet
        }
        cache((settings.map(_._1) ++ damlProjectFiles ++ dars).toSet).toSeq
      },
      damlBuild := {
        val prebuiltDar = damlPrebuiltDar.value
        val dependencies = damlDependencies.value
        val outputDirectory = damlDarOutput.value
        val outputLfVersion = damlDarLfVersion.value
        val sourceDirectory = damlSourceDirectory.value
        val log = streams.value.log
        // we don't really know dependencies between daml files, so just assume if any change then we need to rebuild all packages
        val cacheDir = streams.value.cacheDirectory

        prebuiltDar.fold {
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

          val cache =
            FileFunction.cached(cacheDir, FileInfo.hash) { _ =>
              // we're ignoring the cache as we don't know the dependency

              // build the daml files in a sorted way, using the build order definition
              val projectFiles = damlProjectFiles.get.toList.sortWith(buildOrder)
              projectFiles.flatMap { projectFile =>
                buildDamlProject(
                  log,
                  sourceDirectory,
                  outputDirectory,
                  outputLfVersion,
                  sourceDirectory.toPath.relativize(projectFile.toPath).toFile,
                  damlCompilerVersion.value,
                  damlLanguageVersions.value,
                )
              }.toSet
            }

          cache(allDamlFiles.get.toSet ++ damlProjectFiles.get.toSet ++ dependencies).toSeq

        } { prebuiltDarFile =>
          // Skip compilation and use pre-built DARs
          val cache = FileFunction.cached(cacheDir, FileInfo.hash) { _ =>
            // Copy pre-built DARs into the output directory so downstream tasks find them there.
            // Also create a -current.dar copy to match the convention of buildDamlProject,
            // which downstream tasks (damlCodeGeneration, copyDarResources) rely on.
            IO.createDirectory(outputDirectory)

            val destFile = outputDirectory / prebuiltDarFile.getName
            IO.copyFile(prebuiltDarFile, destFile)
            // Derive the -current.dar name by stripping the version suffix
            val darName = prebuiltDarFile.getName.stripSuffix(".dar")
            val projectName = darName.replaceAll("-[0-9]+\\.[0-9]+\\.[0-9]+$", "")
            val currentDar = outputDirectory / s"$projectName-current.dar"
            IO.copyFile(prebuiltDarFile, currentDar)
            Set(destFile, currentDar)
          }
          cache(Set(prebuiltDarFile)).toSeq
        }
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
      damlTest := Def
        .task {
          damlBuild.value
          val sourceDirectory = damlSourceDirectory.value
          val damlProjectFiles =
            sourceDirectory ** "daml.yaml"
          val log = streams.value.log
          val damlVersion = damlCompilerVersion.value
          val damlDebug = sys.env.get("DAML_DEBUG")
          val stdoutLogger = new ProcessLogger {
            // We overwrite this because by default this ends up being an error log
            // in sbt while damlc really just logs debug information there.
            override def err(message: => String) = log.out(s"stderr: $message")
            override def out(message: => String) = log.out(message)
            override def buffer[T](f: => T) = f
          }
          damlProjectFiles.get.toList.foreach { projectFile =>
            val projectDirectory = projectFile.toPath.toAbsolutePath.getParent
            val result = Process(
              command = Seq(
                "dpm",
                "test",
                "--package-root",
                projectDirectory.toString,
              ) ++ Seq("--debug").filter(_ => damlDebug.isDefined),
              cwd = projectDirectory.toFile,
            ) ! stdoutLogger
            if (result != 0) {
              throw new MessageOnlyException(s"""
                                              |damlc test failed ${projectDirectory}:
              """.stripMargin.trim)
            }
          }
        }
        .tag(damlTestTag)
        .value

  }

  import autoImport._

  override lazy val globalSettings: Seq[Def.Setting[_]] = Seq(
    damlCompilerVersion := CantonDependencies.daml_compiler_version,
    damlJavaCodegenVersion := CantonDependencies.daml_java_codegen_version,
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

  private def buildDamlProject(
      log: Logger,
      sourceDirectory: File,
      outputDirectory: File,
      outputLfVersion: String,
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

    log.debug(
      s"building ${projectDirectory}"
    )

    val damlProjectName = readDamlYaml(originalDamlProjectFile).get("name").toString
    val damlProjectVersion = readDamlYaml(originalDamlProjectFile).get("version").toString
    val versionedDar =
      outputDirectory / s"$damlProjectName-$damlProjectVersion.dar"

    val damlcCommand = "dpm" :: "build" ::
      "--package-root" :: projectDirectory.toString ::
      "--output" :: versionedDar.getAbsolutePath ::
      "--enable-multi-package=no" :: Nil
    val command =
      // if the damlDarLfVersion is not set the daml.yaml is expected to contain the target lf-version in the build-options
      if (outputLfVersion.isEmpty) damlcCommand
      else damlcCommand ::: ("--target" :: outputLfVersion :: Nil)

    val result = BuildUtil.runCommand(
      command,
      log,
      optCwd = Some(projectDirectory),
    )

    val currentDar = outputDirectory / s"$damlProjectName-current.dar"
    IO.copyFile(versionedDar, currentDar)

    Seq(versionedDar, currentDar)
  }

  private def readDamlYaml(damlProjectFile: File): JMap[String, Object] = {
    val reader = new YamlReader(new FileReader(damlProjectFile))
    try {
      reader.read(classOf[JMap[String, Object]])
    } finally reader.close()
  }

  /** Calls the Daml Codegen for the provided DAR file (hence, is suitable to use in a sourceGenerator task)
    */
  def generateCode(
      log: Logger,
      projectDir: File,
      darFile: File,
      basePackageName: String,
      managedSourceDir: File,
      damlVersion: String,
      damlJavaCodegenVersion: String,
      damlCodegenUseProject: Boolean,
  ): Seq[File] = {
    if (!darFile.exists())
      throw new MessageOnlyException(
        s"Codegen asked to generate code from nonexistent file: $darFile"
      )

    log.debug(
      s"Running $language-codegen for ${darFile} into ${managedSourceDir}, project directory: $projectDir"
    )

    if (damlCodegenUseProject) {
      val damlYaml = readDamlYaml(projectDir / "daml.yaml")
      // We don't have a JSON library easily accessible in SBT code so we opt for the rather hacky option here to drill down fields.
      val (codegenDir, packagePrefix, decoderClass) = Try {
        val codegenConfig = damlYaml
          .get("codegen")
          .asInstanceOf[JMap[String, Object]]
          .get("java")
          .asInstanceOf[JMap[String, Object]]
        val outputDir = codegenConfig
          .get("output-directory")
          .asInstanceOf[String]
        val packagePrefix = codegenConfig
          .get("package-prefix")
          .asInstanceOf[String]
        val decoderClass = codegenConfig
          .get("decoderClass")
          .asInstanceOf[String]
        (outputDir, packagePrefix, decoderClass)
      } match {
        case Success(configs) => configs
        case Failure(e) =>
          log.error(
            s"Failed to parse codegen config in daml.yaml file: $damlYaml." +
              s"Missing codegen.java, or one of the required fields output-directory, package-prefix or decoderClass in codegen.java config in $projectDir/daml.yaml?"
          )
          throw e
      }
      IO.delete(projectDir / codegenDir)
      BuildUtil.runCommand(
        Seq(
          "dpm",
          "codegen-java",
          "--output-directory",
          codegenDir,
          s"${darFile.getAbsolutePath}=$packagePrefix",
          "--decoderClass",
          decoderClass,
        ),
        log,
        optCwd = Some(projectDir),
      )
    } else {
      BuildUtil.runCommand(
        Seq(
          "dpm",
          "codegen-java",
          "--output-directory",
          managedSourceDir.getAbsolutePath,
          s"${darFile.getAbsolutePath}=$basePackageName.java",
        ),
        log,
      )
    }

    // return all generated scala files
    (managedSourceDir ** s"*.java").get
  }

}
