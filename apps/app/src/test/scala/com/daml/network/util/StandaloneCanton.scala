package com.daml.network.util

import com.daml.network.console.SvAppBackendReference
import org.scalatest.Suite

trait StandaloneCanton extends PostgresAroundAll with ProcessTestUtil {
  self: Suite =>

  // While tests do not run in parallel, their initialization does, so we force using unique DB names
  def dbsSuffix: String

  override def usesDbs = {
    Seq(s"sequencer_driver_${dbsSuffix}") ++
      (1 to 4)
        .map(index =>
          Seq(
            s"participant_sv${index}_${dbsSuffix}",
            s"sequencer_sv${index}_${dbsSuffix}",
            s"mediator_sv${index}_${dbsSuffix}",
          )
        )
        .flatten
  }

  def withCantonSvNodes[A](
      adminUsersFromSvBackends: (
          Option[SvAppBackendReference],
          Option[SvAppBackendReference],
          Option[SvAppBackendReference],
          Option[SvAppBackendReference],
      ),
      logSuffix: String,
      svs123: Boolean = true,
      sv4: Boolean = true,
      autoInit: Boolean = true,
      overrideDbsSuffix: Option[String] = None,
  )(extraEnv: (String, String)*)(test: => A): A = {
    val configs =
      (if (svs123) { Seq(testResourcesPath / "standalone-seq-med-par-sv123.conf") }
       else Seq()) ++
        (if (sv4) { Seq(testResourcesPath / "standalone-seq-med-par-sv4.conf") }
         else Seq())

    def adminUserEnv(index: Integer) = {
      adminUsersFromSvBackends
        .productElement(index - 1)
        .asInstanceOf[Option[SvAppBackendReference]]
        .map(s"SV${index}_ADMIN_USER" -> _.config.ledgerApiUser)
    }

    val dbNamesEnv = {
      val suffix = overrideDbsSuffix.getOrElse(dbsSuffix)
      (1 to 4)
        .map(i =>
          Seq(
            s"SV${i}_PARTICIPANT_DB" -> s"participant_sv${i}_${suffix}",
            s"SV${i}_SEQUENCER_DB" -> s"sequencer_sv${i}_${suffix}",
            s"SV${i}_MEDIATOR_DB" -> s"mediator_sv${i}_${suffix}",
          )
        )
        .flatten :+
        // The driver DB cannot be overridden on purpose, so that all sequencers share the same DB.
        "SEQUENCER_DRIVER_DB" -> s"sequencer_driver_${dbsSuffix}"
    }

    val extraEnv2 =
      extraEnv ++
        (1 to 4).map(adminUserEnv(_)).flatten ++
        dbNamesEnv :+
        ("AUTO_INIT_ALL" -> autoInit.toString)

    withCanton(
      configs,
      Seq(),
      logSuffix,
      extraEnv2: _*
    )(test)
  }
}
