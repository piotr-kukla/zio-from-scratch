package zio

trait ZIO[+A] {

  def run: A
}

object ZIO {

  def succeedNow[A](value: A): ZIO[A] = new ZIO[A] {
    override def run: A = value
  }

}
