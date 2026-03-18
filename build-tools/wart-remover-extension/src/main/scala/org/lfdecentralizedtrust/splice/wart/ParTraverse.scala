package org.lfdecentralizedtrust.splice.wart

import org.wartremover.{WartTraverser, WartUniverse}

object ParTraverse extends WartTraverser {
  val message =
    "Do not use parTraverse without a parallelism limit. Use MonadUtil.parTraverseWithLimit instead."

  def apply(u: WartUniverse): u.Traverser = {
    import u.universe.*

    val forbiddenNames: Set[TermName] =
      Set("parTraverse", "parTraverse_", "parFlatTraverse").map(TermName(_))

    new Traverser {
      override def traverse(tree: Tree): Unit = {
        tree match {
          case t if hasWartAnnotation(u)(t) =>
          case Select(_, name) if forbiddenNames.contains(name.toTermName) =>
            error(u)(tree.pos, message)
          case _ =>
            super.traverse(tree)
        }
      }
    }
  }
}
