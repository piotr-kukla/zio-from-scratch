package zio

trait ZIO[+A] {

  def run(callback: A => Unit) : Unit
}

object ZIO {

  def succeedNow[A](value: A): ZIO[A] = new ZIO[A] {
    override def run(callback: A => Unit): Unit = callback(value)
  }

}
