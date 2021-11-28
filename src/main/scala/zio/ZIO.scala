package zio

import java.util.concurrent.atomic.AtomicReference

import zio.ZIO.{Async, Effect, FlatMap, Fork, Succeed}

import scala.concurrent.ExecutionContext

trait Fiber[+A] {
  def start: Unit
  def join: ZIO[A]
}

class FiberImpl[A](zio: ZIO[A]) extends Fiber[A] {

  sealed trait FiberState

  case class Running(callbacks: List[A => Any]) extends FiberState
  case class Done(result: A) extends FiberState

  val state: AtomicReference[FiberState] = new AtomicReference[FiberState](Running(List.empty))

  def complete(result: A): Unit = {
    var loop = true
    while(loop) {
      val oldState = state.get()
      oldState match {
        case Running(callbacks) =>
          if (state.compareAndSet(oldState, Done(result))) {
            callbacks.foreach(cb => cb(result))
            loop = false
          }
        case Done(result) =>
          throw new Exception("Internal defect: Fiber complete twice.")
      }
    }
  }

  def await(callback: A => Any): Unit = {
    var loop = true
    while(loop) {
      var oldState = state.get()
      oldState match {
        case Running(callbacks) =>
          loop = !state.compareAndSet(oldState, Running(callback :: callbacks))

        case Done(result) =>
          callback(result)
          loop = false
      }
    }
  }

  def start(): Unit =
    ExecutionContext.global.execute { () =>
      zio.run(complete)
    }

  override def join: ZIO[A] = ZIO.async {
    complete => await(complete)
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

  def *>[B](that: => ZIO[B]): ZIO[B] = self zipRight that

  def zipRight[B](that: => ZIO[B]): ZIO[B] =
    zipWith(that)((_,b) => b)

  def zipWith[B, C](that: => ZIO[B])(f: (A, B) => C): ZIO[C] =
    self
      .flatMap(a =>
        that
          .map(b => f(a, b))
      )

  final def run(callback: A => Unit) : Unit = {

    type Erased = ZIO[Any]
    type ErasedCallback = Any => Any
    type Cont = Any => Erased

    def erase[A](zio: ZIO[A]): Erased = zio

    def erasedCallback[A](cb: A => Unit): ErasedCallback = cb.asInstanceOf[ErasedCallback]

    val stack = new scala.collection.mutable.Stack[Cont]()

    var currentZIO = erase(self)

    var loop = true

    def resume(): Unit = {
      loop = true;
      run();
    }

    def complete(value: Any) = {
      if (stack.isEmpty) {
        loop = false;
        callback(value.asInstanceOf[A])
      } else {
        val cont = stack.pop()
        currentZIO = cont(value)
      }
    }

    def run(): Unit =
      while (loop) {
        currentZIO match {
          case Succeed(value) =>
            complete(value)
          case Effect(thunk) =>
            complete(thunk())
          case FlatMap(zio, cont: Cont) =>
            stack.push(cont)
            currentZIO = zio

          case Async(register) => {
            if (stack.isEmpty) {
              loop = false
              register(erasedCallback(callback))
            } else {
              loop = false;
              register { a =>
                currentZIO = ZIO.succeedNow(a)
                resume()
              }
            }
          }

          case Fork(zio) => {
            val fiber = new FiberImpl(zio)
            fiber.start()
            complete(fiber)
          }

        }
      }

    run()
  }
}

object ZIO {
  def async[A](register: (A => Any) => Any):ZIO[A] = ZIO.Async(register)

  def succeed[A](value: => A): ZIO[A] = ZIO.Effect(() => value)


  def succeedNow[A](value: A): ZIO[A] = ZIO.Succeed(value)


  case class Succeed[A](value: A) extends ZIO[A]

  case class Effect[A](f: () => A) extends ZIO[A]

  case class FlatMap[A, B](zio: ZIO[A], f: A => ZIO[B]) extends ZIO[B]

  case class Async[A](register: (A => Any) => Any) extends ZIO[A]

  case class Fork[A](zio: ZIO[A]) extends ZIO[Fiber[A]]
}
