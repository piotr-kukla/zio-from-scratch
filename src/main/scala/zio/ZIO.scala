package zio

sealed trait ZIO[+A] { self =>
  def map[B](f: A => B): ZIO[B] = ZIO.Map(self, f)


  def zip[B](that: ZIO[B]): ZIO[(A,B)] = ZIO.Zip(self, that)


  def run(callback: A => Unit) : Unit
}

object ZIO {
  def succeed[A](value: => A): ZIO[A] = ZIO.Effect(() => value)


  def succeedNow[A](value: A): ZIO[A] = ZIO.Succeed(value)


  case class Succeed[A](value: A) extends ZIO[A] {
    override def run(callback: A => Unit): Unit = callback(value)
  }

  case class Effect[A](f: () => A) extends ZIO[A] {
    override def run(callback: A => Unit): Unit = callback(f())
  }

  case class Zip[A,B](left: ZIO[A], right: ZIO[B]) extends ZIO[(A,B)] {
    override def run(callback: ((A, B)) => Unit): Unit =
      left.run { a =>
        right.run { b =>
          callback(a,b)
        }
      }
  }

  case class Map[A,B](zio: ZIO[A], f: A => B) extends ZIO[B] {
    override def run(callback: B => Unit): Unit =
      zio.run { a => callback(f(a))}
  }

}
