package zio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import zio.InterruptStatus.{Interruptible, Uninterruptible}
import zio.ZIO.{Async, Fail, FlatMap, Fold, Fork, SetInterruptStatus, Shift, Succeed, SucceedNow, failCause}

import scala.concurrent.ExecutionContext

trait Fiber[+E, +A] {
  def join: ZIO[E, A]
  def interrupt: ZIO[Nothing, Unit]
}

private final case class FiberContext[E, A](startZIO: ZIO[E, A], startExecutor: ExecutionContext) extends Fiber[E, A] {

  sealed trait FiberState

  case class Running(callbacks: List[Exit[E, A] => Any]) extends FiberState
  case class Done(result: Exit[E, A]) extends FiberState

  val state: AtomicReference[FiberState] = new AtomicReference[FiberState](Running(List.empty))

  //Has someone sent us the signal to stop executing?
  val interrupted: AtomicBoolean = new AtomicBoolean(false)
  //Ae we in the process of finalizing ourselves
  val isInterrupting: AtomicBoolean = new AtomicBoolean(false)
  //Are we in a region where we are subject to being interrupted
  val isInterruptible: AtomicBoolean = new AtomicBoolean(true)

  def shouldInterrupt(): Boolean =
    interrupted.get() && isInterruptible.get() && !isInterrupting.get()

  def interrupt: ZIO[Nothing, Unit] = ZIO.succeed(interrupted.set(true))

  def complete(result: Exit[E, A]): Unit = {
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

  def await(callback: Exit[E, A] => Any): Unit = {
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

  override def join: ZIO[E, A] = ZIO.async[Exit[E, A]] {
    complete => await(complete)
  }.flatMap(ZIO.done)

  type Erased = ZIO[Any, Any]
  type ErasedCallback = Any => Any
  type Cont = Any => Erased

  def erase[E, A](zio: ZIO[E, A]): Erased = zio

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
      complete(Exit.succeed(value.asInstanceOf[A]))
    } else {
      val cont = stack.pop()
      currentZIO = cont(value)
    }
  }

  def findNextErrorHandler(): Fold[Any, Any, Any, Any] = {
    var loop = true;
    var errorHandler: Fold[Any, Any, Any, Any] = null;
    while (loop) {
      if (stack.isEmpty) loop = false;
      else {
        val cont = stack.pop();
        if (cont.isInstanceOf[Fold[Any, Any, Any, Any]]){
          errorHandler = cont.asInstanceOf[Fold[Any, Any, Any, Any]]
          loop = false
        }
      }
    }
    errorHandler
  }

  def run(): Unit =
    while (loop) {
      //val shouldInterrupt = interrupted.get
      if (shouldInterrupt()) {
        isInterrupting.set(true)
        stack.push(_ => currentZIO)
        currentZIO = ZIO.failCause(Cause.Interrupt)
      } else {
        try {
          currentZIO match {
            case SucceedNow(value) =>
              continue(value)
            case Succeed(thunk) =>
              continue(thunk())
            case FlatMap(zio, cont: Cont) =>
              stack.push(cont)
              currentZIO = zio

            case Async(register) => {
              if (stack.isEmpty) {
                loop = false
                register { a => complete(Exit.succeed(a.asInstanceOf[A])) }
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

            case SetInterruptStatus(zio, interruptStatus) =>
              val oldIsInterruptible = isInterruptible.get()
              isInterruptible.set(interruptStatus.toBoolean)
              currentZIO = zio.ensuring(ZIO.succeed(isInterruptible.set(oldIsInterruptible)))

            case Fail(e) => {
              val errorHandler = findNextErrorHandler()
              if (errorHandler eq null) {
                complete(Exit.fail(e().asInstanceOf[E]))
                //complete(Exit.Failure(Cause.Fail(e().asInstanceOf[E])))
                //loop = false
              } else {
                currentZIO = errorHandler.failure(e())
              }
            }

            case fold @ Fold(zio, failure, success) =>
              stack.push(fold)
              currentZIO = zio

          }
        } catch {
          case t: Throwable => currentZIO = ZIO.failCause(Cause.Die(t))
        }
      }
    }

  currentExecutor.execute(() => run())
}

sealed trait ZIO[+E, +A] { self =>


  def fork: ZIO[Nothing, Fiber[E, A]] = ZIO.Fork(self)

  def as[B](value: B): ZIO[E, B] = self.map(_ => value)

  def catchAll[E2, A1 >: A](failure: E => ZIO[E2, A1]): ZIO[E2, A1] =
    foldZIO(failure, ZIO.succeedNow(_))

  def ensuring(finalizer: ZIO[Nothing, Any]): ZIO[E, A] =
    foldCauseZIO(cause => finalizer *> ZIO.failCause(cause), a => finalizer *> ZIO.succeedNow(a))

  def flatMap[E1 >: E, B](f: A => ZIO[E1, B]): ZIO[E1, B] = ZIO.FlatMap(self, f)

  def fold[B](failure: E => B, success: A => B): ZIO[E, B] =
    foldZIO(e => ZIO.succeedNow(failure(e)), a => ZIO.succeedNow (success(a)))

  def foldZIO[E2, B](failure: E => ZIO[E2, B], success: A => ZIO[E2, B]): ZIO[E2, B] =
    foldCauseZIO( {
      case Cause.Fail(e) => failure(e)
      case Cause.Die(throwable) => failCause(Cause.Die(throwable))
    }, success)

  def foldCauseZIO[E2, B](failure: Cause[E] => ZIO[E2, B], success: A => ZIO[E2, B]): ZIO[E2, B] =
    Fold(self, failure, success)

  def forever: ZIO[E, Nothing] = self *> self.forever

  def map[B](f: A => B): ZIO[E, B] =
    flatMap(a => ZIO.succeedNow(f(a)))

  def repeat(n: Int): ZIO[E, Unit] =
    if (n <= 0) ZIO.succeedNow()
    else self *> repeat(n-1)

  def setInterruptStatus(interruptStatus: InterruptStatus): ZIO[E, A] =
    ZIO.SetInterruptStatus(self, interruptStatus)

  def interruptible: ZIO[E, A] = setInterruptStatus(InterruptStatus.Interruptible)

  def uninterruptible: ZIO[E, A] = setInterruptStatus(InterruptStatus.Uninterruptible)

  def shift(executor: ExecutionContext): ZIO[Nothing, Unit] =
    Shift(executor)

  def zipPar[E1 >: E, B](that: ZIO[E1, B]): ZIO[E1, (A,B)] =
    for {
      f1 <- self.fork
      f2 <- that.fork
      a <- f1.join
      b <- f2.join
    } yield (a,b)

  def zip[E1 >: E, B](that: ZIO[E1, B]): ZIO[E1, (A,B)] =
    zipWith(that)(_ -> _)

  def *>[E1 >: E, B](that: => ZIO[E1, B]): ZIO[E1, B] = self zipRight that

  def zipRight[E1 >: E, B](that: => ZIO[E1, B]): ZIO[E1, B] =
    zipWith(that)((_,b) => b)

  def zipWith[E1 >: E, B, C](that: => ZIO[E1, B])(f: (A, B) => C): ZIO[E1, C] =
    self
      .flatMap(a =>
        that
          .map(b => f(a, b))
      )

  private final def unsafeRunFiber: Fiber[E, A] =
    FiberContext(self, ZIO.defaultExecutor)

  final def unsafeRunSync: Exit[E, A] = {
    val latch = new CountDownLatch(1)
    var result: Exit[E, A] = null.asInstanceOf[Exit[E, A]]
    val zio = self.foldCauseZIO (
      cause => ZIO.succeed {
        result = Exit.Failure(cause)
        latch.countDown()
      },
      a => ZIO.succeed {
        result = Exit.succeed(a)
        latch.countDown()
      }
    )
    zio.unsafeRunFiber
    latch.await()
    result
  }

}

object ZIO {
  def async[A](register: (A => Any) => Any):ZIO[Nothing, A] = ZIO.Async(register)

  def fail[E](e: => E): ZIO[E, Nothing] = failCause(Cause.Fail(e))

  def failCause[E](cause: => Cause[E]): ZIO[E, Nothing] = ZIO.Fail(() => cause)

  def fromEither[E, A](either: Either[E, A]): ZIO[E, A] =
    either.fold(e => fail(e), a => succeedNow(a))

  def done[E,A](exit: Exit[E,A]): ZIO[E, A] =
    exit match {
      case Exit.Success(a) => succeedNow(a)
      case Exit.Failure(e) => failCause(e)
    }

  def succeed[A](value: => A): ZIO[Nothing, A] = ZIO.Succeed(() => value)


  def succeedNow[A](value: A): ZIO[Nothing, A] = ZIO.SucceedNow(value)


  case class SucceedNow[A](value: A) extends ZIO[Nothing, A]

  case class Succeed[A](f: () => A) extends ZIO[Nothing, A]

  case class FlatMap[E, A, B](zio: ZIO[E, A], f: A => ZIO[E, B]) extends ZIO[E, B]

  case class Async[A](register: (A => Any) => Any) extends ZIO[Nothing, A]

  case class Fork[E, A](zio: ZIO[E, A]) extends ZIO[Nothing, Fiber[E, A]]

  case class SetInterruptStatus[E, A](zio: ZIO[E, A], interruptStatus: InterruptStatus) extends ZIO[E, A]

  case class Shift(executor: ExecutionContext) extends ZIO[Nothing, Unit]

  case class Fail[E](e: () => Cause[E]) extends ZIO[E, Nothing]

  case class Fold[E, E2, A, B](zio: ZIO[E, A], failure: Cause[E] => ZIO[E2, B], success: A => ZIO[E2, B])
    extends ZIO[E2, B] with (A => ZIO[E2, B]) {
    override def apply(a: A): ZIO[E2, B] = success(a)
  }

  private val defaultExecutor = ExecutionContext.global
}

sealed trait Cause[+E]

object Cause {
  final case class Fail[+E](error: E) extends Cause[E]
  final case class Die(throwable: Throwable) extends Cause[Nothing]
  case object Interrupt extends Cause[Nothing]
}

sealed trait Exit[+E, +A]

object Exit {
  final case class Success[+A](a: A) extends Exit[Nothing, A]
  final case class Failure[+E](failure: Cause[E]) extends Exit[E, Nothing]

  def succeed[A](value: A): Exit[Nothing, A] = Success(value)
  def fail[E](error: E): Exit[E, Nothing] = Failure(Cause.Fail(error))
  def die(throwable: Throwable): Exit[Nothing, Nothing] = Failure(Cause.Die(throwable))
}

sealed trait InterruptStatus { self =>
  def toBoolean: Boolean =
    self match {
      case Interruptible => true
      case Uninterruptible => false
    }

}

object InterruptStatus {
  case object Interruptible extends InterruptStatus
  case object Uninterruptible extends InterruptStatus
}
