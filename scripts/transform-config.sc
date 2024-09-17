import com.daml.network.config.{SpliceConfig, ConfigTransforms}
import com.digitalasset.canton.config.CantonCommunityConfig

import java.nio.file.Paths

object TransformConfig extends App {
  val mode = args(0)
  val inputFileName = Paths.get(args(1))
  val outputFileName = Paths.get(args(2))

  mode match {
    case "useSelfSignedTokensForLedgerApiAuth" =>
      val inputConfig = SpliceConfig.parseAndLoadOrThrow(Seq(inputFileName.toFile))
      val outputConfig =
        ConfigTransforms.useSelfSignedTokensForLedgerApiAuth("test")(inputConfig)
      // Deliberately leaking secrets to file
      SpliceConfig.writeToFile(outputConfig, outputFileName, confidential = false)
    case "integrationTestDefaults" =>
      val testId = args(3)
      val inputConfig = SpliceConfig.parseAndLoadOrThrow(Seq(inputFileName.toFile))
      val outputConfig =
        ConfigTransforms.defaults(Some(testId)).foldLeft(inputConfig)((c, t) => t(c))
      SpliceConfig.writeToFile(outputConfig, outputFileName, confidential = false)
    case _ =>
      println(s"Unknown mode '$mode'")
      sys.exit(-1)
  }
}
