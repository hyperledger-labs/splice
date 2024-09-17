package com.daml.network.wart

import org.wartremover.{WartTraverser, WartUniverse}

object Println extends WartTraverser {
  def apply(u: WartUniverse): u.Traverser = {
    import u.universe.*

    import scala.reflect.NameTransformer

    val printlnName: TermName = TermName(NameTransformer.encode("println"))

    new Traverser {
      override def traverse(tree: Tree): Unit = {
        tree match {
          // Ignore trees marked by SuppressWarnings
          case t if hasWartAnnotation(u)(t) =>
          case Select(receiver, name)
              if name.toTermName == printlnName && receiver.symbol.fullName == "scala.Predef" =>
            error(u)(tree.pos, "println was used")
          case _ =>
            super.traverse(tree)
        }
      }
    }
  }
}
