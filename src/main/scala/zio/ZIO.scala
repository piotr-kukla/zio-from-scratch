package zio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

import zio.ZIO.{Async, Effect, FlatMap, Fork, Shift, Succeed}

import scala.concurrent.ExecutionContext

trait Fiber[+A] {
  def join: ZIO[A]
  def interrupt: ZIO[Unit] = ???
}

private final case class FiberContext[A](startZIO: ZIO[A], startExecutor: ExecutionContext) extends Fiber[A] {

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

  override def join: ZIO[A] = ZIO.async {
    complete => await(complete)
  }

  type Erased = ZIO[Any]
  type ErasedCallback = Any => Any
  type Cont = Any => Erased

  def erase[A](zio: ZIO[A]): Erased = zio

  def erasedCallback[A](cb: A => Unit): ErasedCallback = cb.asInstanceOf[ErasedCallback]

  val stack = new scala.collection.mutable.Stack[Cont]()

  var currentZIO = erase(startZIO)
  var currentExecutor = startExecutor

  var loop = true

  def resume(): Unit = {
    loop = true;
    run();
  }

  def continue(value: Any) = {
    if (stack.isEmpty) {
      loop = false;
      complete(value.asInstanceOf[A])
    } else {
      val cont = stack.pop()
      currentZIO = cont(value)
    }
  }

  def run(): Unit =
    while (loop) {
      currentZIO match {
        case Succeed(value) =>
          continue(value)
        case Effect(thunk) =>
          continue(thunk())
        case FlatMap(zio, cont: Cont) =>
          stack.push(cont)
          currentZIO = zio

        case Async(register) => {
          if (stack.isEmpty) {
            loop = false
            register { a => complete(a.asInstanceOf[A]) }
          } else {
            loop = false;
            register { a =>
              currentZIO = ZIO.succeedNow(a)
              resume()
            }
          }
        }

        case Fork(zio) => {
          val fiber = FiberContext(zio, currentExecutor)
          continue(fiber)
        }

        case Shift(executor) => {
          currentExecutor = executor
          continue(())
        }

      }
    }

  currentExecutor.execute(() => run())
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

  def shift(executor: ExecutionContext): ZIO[Unit] =
    Shift(executor)

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

  private final def unsafeRunFiber: Fiber[A] =
    FiberContext(self, ZIO.defaultExecutor)

  final def unsafeRunSync: A = {
    val latch = new CountDownLatch(1)
    var result: A = null.asInstanceOf[A]
    val zio = self.flatMap { a =>
      ZIO.succeed {
        result = a
        latch.countDown()
      }
    }
    zio.unsafeRunFiber
    latch.await()
    result
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

  case class Shift(executor: ExecutionContext) extends ZIO[Unit]

  private val defaultExecutor = ExecutionContext.global
}
