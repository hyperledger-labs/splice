package org.lfdecentralizedtrust.splice.wart

import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.wartremover.test.WartTestTraverser

class ParTraverseTest extends AnyWordSpec with Matchers {

  def assertErrors(result: WartTestTraverser.Result, count: Int): Assertion = {
    result.errors.length shouldBe count
    result.errors.foreach(_ should include(ParTraverse.message))
    succeed
  }

  "ParTraverse" should {

    "flag parTraverse calls" in {
      val result = WartTestTraverser(ParTraverse) {
        val xs = new ParTraverseTest.HasParTraverse
        xs.parTraverse(_ => ())
      }
      assertErrors(result, 1)
    }

    "flag parTraverse_ calls" in {
      val result = WartTestTraverser(ParTraverse) {
        val xs = new ParTraverseTest.HasParTraverse
        xs.parTraverse_(_ => ())
      }
      assertErrors(result, 1)
    }

    "flag parFlatTraverse calls" in {
      val result = WartTestTraverser(ParTraverse) {
        val xs = new ParTraverseTest.HasParTraverse
        xs.parFlatTraverse(_ => ())
      }
      assertErrors(result, 1)
    }

    "not flag unrelated method calls" in {
      val result = WartTestTraverser(ParTraverse) {
        List(1, 2, 3).map(_ + 1)
      }
      result.errors shouldBe empty
    }

    "not flag parTraverseWithLimit calls" in {
      val result = WartTestTraverser(ParTraverse) {
        ParTraverseTest.StubMonadUtil.parTraverseWithLimit(10)(List(1, 2, 3))(_ + 1)
      }
      result.errors shouldBe empty
    }

    "not flag parTraverseWithLimit_ calls" in {
      val result = WartTestTraverser(ParTraverse) {
        ParTraverseTest.StubMonadUtil.parTraverseWithLimit_(10)(List(1, 2, 3))(_ => ())
      }
      result.errors shouldBe empty
    }

    "respect @SuppressWarnings annotation" in {
      val result = WartTestTraverser(ParTraverse) {
        @SuppressWarnings(Array("org.lfdecentralizedtrust.splice.wart.ParTraverse"))
        def suppressed(): Unit = {
          val xs = new ParTraverseTest.HasParTraverse
          xs.parTraverse(_ => ())
          ()
        }
        suppressed()
      }
      result.errors shouldBe empty
    }
  }
}

object ParTraverseTest {
  class HasParTraverse {
    def parTraverse[B](f: Int => B): List[B] = List(1).map(f)
    def parTraverse_[B](f: Int => B): Unit = { List(1).map(f); () }
    def parFlatTraverse[B](f: Int => B): List[B] = List(1).map(f)
  }

  object StubMonadUtil {
    def parTraverseWithLimit[A, B](n: Int)(xs: List[A])(f: A => B): List[B] = xs.map(f)
    def parTraverseWithLimit_[A](n: Int)(xs: List[A])(f: A => Unit): Unit = xs.foreach(f)
  }
}
