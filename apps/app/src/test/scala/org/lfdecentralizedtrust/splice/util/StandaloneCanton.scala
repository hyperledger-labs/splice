package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.console.SvAppBackendReference
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.IsTheCantonSequencerBFTEnabled
import org.scalatest.Suite

trait StandaloneCanton extends PostgresAroundEach with NamedLogging with ProcessTestUtil {
  self: Suite & BaseTest =>

  // While tests do not run in parallel, their initialization does, so we force using unique DB names
  def dbsSuffix: String

  override def usesDbs = {
    Seq(
      s"sequencer_driver_${dbsSuffix}",
      s"participant_extra_$dbsSuffix",
      s"participant_second_extra_$dbsSuffix",
    ) ++
      (1 to 4).flatMap(index =>
        Seq(
          s"participant_sv${index}_${dbsSuffix}",
          s"sequencer_sv${index}_${dbsSuffix}",
          s"sequencer_sv${index}_${dbsSuffix}_bft",
          s"mediator_sv${index}_${dbsSuffix}",
        )
      )
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
      participants: Boolean = true,
      sequencersMediators: Boolean = true,
      overrideSvDbsSuffix: Option[String] = None,
      overrideSequencerDriverDbSuffix: Option[String] = None,
      portsRange: Option[Int] = None,
      extraParticipantsConfigFileNames: Seq[String] = Seq.empty,
      extraParticipantsEnvMap: Map[String, String] = Map.empty,
      enableBftSequencer: Boolean = IsTheCantonSequencerBFTEnabled,
  )(extraEnv: (String, String)*)(test: => A)(implicit tc: TraceContext): A = {

    def conditionalConf(condition: Boolean, filename: String) =
      if (condition) {
        Seq(testResourcesPath / filename)
      } else {
        Seq()
      }

    val configs =
      conditionalConf(svs123 && participants, "standalone-participants-sv123.conf") ++
        conditionalConf(
          svs123 && sequencersMediators,
          "standalone-sequencers-mediators-sv123.conf",
        ) ++
        conditionalConf(
          svs123 && sequencersMediators && enableBftSequencer,
          "standalone-sequencers-sv123-extra-enable-bft.conf",
        ) ++
        conditionalConf(sv4 && participants, "standalone-participant-sv4.conf") ++
        conditionalConf(sv4 && sequencersMediators, "standalone-sequencer-mediator-sv4.conf") ++
        conditionalConf(
          sv4 && sequencersMediators && enableBftSequencer,
          "standalone-sequencer-sv4-extra-enable-bft.conf",
        ) ++
        extraParticipantsConfigFileNames.toList.map(testResourcesPath / _)

    def adminUserEnv(index: Integer) = {
      adminUsersFromSvBackends
        .productElement(index - 1)
        .asInstanceOf[Option[SvAppBackendReference]]
        .map(s"SV${index}_ADMIN_USER" -> _.config.ledgerApiUser)
    }

    val dbNamesEnv = {
      val svDbsSuffix = overrideSvDbsSuffix.getOrElse(dbsSuffix)
      val sequencerDriverDbSuffix = overrideSequencerDriverDbSuffix.getOrElse(dbsSuffix)
      (1 to 4).flatMap(i =>
        Seq(
          s"SV${i}_PARTICIPANT_DB" -> s"participant_sv${i}_${svDbsSuffix}",
          s"SV${i}_SEQUENCER_DB_BFT" -> s"sequencer_sv${i}_${svDbsSuffix}_bft",
          s"SV${i}_SEQUENCER_DB" -> s"sequencer_sv${i}_${svDbsSuffix}",
          s"SV${i}_MEDIATOR_DB" -> s"mediator_sv${i}_${svDbsSuffix}",
        )
      ) :+
        "SEQUENCER_DRIVER_DB" -> s"sequencer_driver_${sequencerDriverDbSuffix}"
    }

    val portsEnv = portsRange.fold(Seq(): Seq[(String, String)])(range =>
      (1 to 4).flatMap(i =>
        Seq(
          s"SV${i}_PARTICIPANT_LEDGER_API_PORT" -> (range * 1000 + i * 100 + 1).toString,
          s"SV${i}_PARTICIPANT_ADMIN_API_PORT" -> (range * 1000 + i * 100 + 2).toString,
          s"SV${i}_MEDIATOR_ADMIN_API_PORT" -> (range * 1000 + i * 100 + 7).toString,
          s"SV${i}_SEQUENCER_PUBLIC_API_PORT" -> (range * 1000 + i * 100 + 8).toString,
          s"SV${i}_SEQUENCER_ADMIN_API_PORT" -> (range * 1000 + i * 100 + 9).toString,
        )
      )
    )

    val allExtraEnv =
      (extraEnv ++
        (1 to 4).flatMap(adminUserEnv(_)) ++
        portsEnv ++
        dbNamesEnv) ++ extraParticipantsEnvMap.toList

    logger.debug(
      s"""
         |Starting standalone canton with log suffix \n
         |  $logSuffix\n
         |and config files:\n
         |  ${configs.mkString("\n  ")}\n
         |and extra env variables:\n
         |  ${allExtraEnv.mkString("\n  ")}
         |""".stripMargin
    )(tc)

    withCanton(
      configs,
      Seq(),
      logSuffix,
      allExtraEnv*
    )(test)
  }
}
