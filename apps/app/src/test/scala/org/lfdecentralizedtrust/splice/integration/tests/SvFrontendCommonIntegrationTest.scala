package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.util.FrontendLoginUtil

abstract class SvFrontendCommonIntegrationTest
    extends FrontendIntegrationTest("sv1", "sv2")
    with FrontendLoginUtil {

  def getParameterNamesFromClass(clazz: Class[?]): List[String] = {
    import java.lang.reflect.Modifier
    val fields = clazz.getDeclaredFields
    val parameterFields = fields.filterNot(f => Modifier.isStatic(f.getModifiers))
    parameterFields.map(_.getName).toList
  }

}
