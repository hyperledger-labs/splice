package com.daml.network.console

import com.daml.lf.archive.DarParser
import com.daml.scalautil.Statement.discard
import com.digitalasset.canton.console.commands.{
  BaseLedgerApiAdministration,
  ParticipantAdministration,
}

import java.io.File

trait ParticipantAdministrationExtensions {
  implicit class DarSyntax(
      private val participant: ParticipantAdministration with BaseLedgerApiAdministration
  ) {
    object dars_extensions {
      // TODO(#5141) Consider removing this once Canton no longer explodes
      // when uploading the same DAR twice.
      def upload_if_not_exist(
          path: String
      ): Unit = {
        val hash = DarParser.assertReadArchiveFromFile(new File(path)).main.getHash
        val pkgs = participant.ledger_api.packages.list()
        if (!pkgs.map(_.packageId).contains(hash)) {
          discard[String](participant.dars.upload(path))
        }
      }
    }
  }
}

object ParticipantAdministrationExtensions extends ParticipantAdministrationExtensions {}
