package examples

object Hello {
  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  def main(args: Array[String]): Unit = {
    println(null)
    println("Hello")
  }
}
