import com.daml.network.config.{CoinConfig, CoinConfigTransforms}
import com.digitalasset.canton.config.CantonCommunityConfig

import java.nio.file.Paths

object TransformConfig extends App {
  val mode = args(0)
  val inputFileName = Paths.get(args(1))
  val outputFileName = Paths.get(args(2))

  mode match {
    case "remoteCantonConfigWithAdminTokens" =>
      val inputConfig = CantonCommunityConfig.parseAndLoadOrExit(Seq(inputFileName.toFile))
      val outputConfig = CoinConfigTransforms.remoteCantonConfigWithAdminTokens(inputConfig)
      CantonCommunityConfig.writeToFile(outputConfig, outputFileName)
    case "useSelfSignedTokensForLedgerApiAuth" =>
      val inputConfig = CoinConfig.parseAndLoadOrThrow(Seq(inputFileName.toFile))
      val outputConfig = CoinConfigTransforms.useSelfSignedTokensForLedgerApiAuth("test")(inputConfig)
      CoinConfig.writeToFile(outputConfig, outputFileName)
    case _ =>
      println(s"Unknown mode '$mode'")
      sys.exit(-1)
  }
}
