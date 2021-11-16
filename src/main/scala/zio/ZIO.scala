package zio

import scala.concurrent.ExecutionContext

trait Fiber[+A] {
  def start: Unit
  def join: ZIO[A]
}

class FiberImpl[A](zio: ZIO[A]) extends Fiber[A] {
  var maybeResult: Option[A] = None
  var callbacks = List.empty[A => Any]

  def start(): Unit =
    ExecutionContext.global.execute { () =>
      zio.run { a =>
        maybeResult = Some(a)
        callbacks.foreach { callback => callback(a)}
      }
    }

  override def join: ZIO[A] = maybeResult match {
    case Some(a) => ZIO.succeedNow(a)
    case None => ZIO.async { complete =>
      callbacks = complete :: callbacks
    }
  }

}

sealed trait ZIO[+A] { self =>


  def fork: ZIO[Fiber[A]] = ZIO.Fork(self)

  def as[B](value: B): ZIO[B] = self.map(_ => value)

  def flatMap[B](f: A => ZIO[B]): ZIO[B] = ZIO.FlatMap(self, f)

  def map[B](f: A => B): ZIO[B] =
    flatMap(a => ZIO.succeedNow(f(a)))

  def repeat(n: Int): ZIO[Unit] =
    if (n <= 0) ZIO.succeedNow()
    else self *> repeat(n-1)

  def zipPar[B](that: ZIO[B]): ZIO[(A,B)] =
    for {
      f1 <- self.fork
      f2 <- that.fork
      a <- f1.join
      b <- f2.join
    } yield (a,b)

  def zip[B](that: ZIO[B]): ZIO[(A,B)] =
    zipWith(that)(_ -> _)

  def *>[B](that: ZIO[B]): ZIO[B] = self zipRight that

  def zipRight[B](that: ZIO[B]): ZIO[B] =
    zipWith(that)((_,b) => b)

  def zipWith[B, C](that: ZIO[B])(f: (A, B) => C): ZIO[C] =
    for {
      a <- self
      b <- that
    } yield f(a,b)

  def run(callback: A => Unit) : Unit
}

object ZIO {
  def async[A](register: (A => Any) => Any):ZIO[A] = ZIO.Async(register)

  def succeed[A](value: => A): ZIO[A] = ZIO.Effect(() => value)


  def succeedNow[A](value: A): ZIO[A] = ZIO.Succeed(value)


  case class Succeed[A](value: A) extends ZIO[A] {
    override def run(callback: A => Unit): Unit = callback(value)
  }

  case class Effect[A](f: () => A) extends ZIO[A] {
    override def run(callback: A => Unit): Unit = callback(f())
  }

  case class FlatMap[A, B](zio: ZIO[A], f: A => ZIO[B]) extends ZIO[B] {
    override def run(callback: B => Unit): Unit =
      zio.run { a =>
        f(a).run(callback)
      }
  }

  case class Async[A](register: (A => Any) => Any) extends ZIO[A] {
    override def run(callback: A => Unit): Unit = register(callback)
  }

  case class Fork[A](zio: ZIO[A]) extends ZIO[Fiber[A]] {
    override def run(callback: Fiber[A] => Unit): Unit = {
     val fiber = new FiberImpl(zio)
     fiber.start()
     callback(fiber)
    }
  }
}
