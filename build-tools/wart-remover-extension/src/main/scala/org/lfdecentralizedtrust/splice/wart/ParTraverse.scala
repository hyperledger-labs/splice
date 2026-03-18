package org.lfdecentralizedtrust.splice.wart

import org.wartremover.{WartTraverser, WartUniverse}

/** Flags calls to `parTraverse`, `parTraverse_`, and `parFlatTraverse` which perform
  * unbounded parallel traversal. Use `MonadUtil.parTraverseWithLimit` instead.
  *
  * Uses name-based matching (like the Println wart) rather than Cats receiver-type matching
  * because no code in `apps/` defines custom methods with these names, and the type-narrowing
  * approach would require `cats` as a compile dependency of the wart module.
  */
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
