import com.daml.network.config.{CNNodeConfig, CNNodeConfigTransforms}
import com.digitalasset.canton.config.CantonCommunityConfig

import java.nio.file.Paths

object TransformConfig extends App {
  val mode = args(0)
  val inputFileName = Paths.get(args(1))
  val outputFileName = Paths.get(args(2))

  mode match {
    case "remoteCantonConfigWithAdminTokens" =>
      val inputConfig = CantonCommunityConfig.parseAndLoadOrExit(Seq(inputFileName.toFile))
      val outputConfig = CNNodeConfigTransforms.remoteCantonConfigWithAdminTokens(inputConfig)
      CantonCommunityConfig.writeToFile(outputConfig, outputFileName)
    case "useSelfSignedTokensForLedgerApiAuth" =>
      val inputConfig = CNNodeConfig.parseAndLoadOrThrow(Seq(inputFileName.toFile))
      val outputConfig = CNNodeConfigTransforms.useSelfSignedTokensForLedgerApiAuth("test")(inputConfig)
      // Deliberately leaking secrets to file
      CNNodeConfig.writeToFile(outputConfig, outputFileName, confidential = false)
    case _ =>
      println(s"Unknown mode '$mode'")
      sys.exit(-1)
  }
}
