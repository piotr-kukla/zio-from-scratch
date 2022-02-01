package zio

import zio.ZIO.{succeed, _}

import scala.concurrent.ExecutionContext

case class Person(name: String, age: Int)

object Person {
  val peter: Person = Person("Peter", 88)
}


trait ZIOApp {

  def run: ZIO[Any, Any, Any]

  def main(args: Array[String]): Unit = {
    val result = run.unsafeRunSync
    println(s"The result was $result")
    Thread.sleep(2000)
  }


}

object succeedNow extends ZIOApp {

  val peterZIO: ZIO[Any, Nothing, Person] = ZIO.succeedNow(Person.peter)

  override def run: ZIO[Any, Nothing, Person] = peterZIO
}

object succeed extends ZIOApp {
  val howdyZIO = ZIO.succeed(println("Howdy!"))

  override def run: ZIO[Any, Nothing, Unit] = howdyZIO
}

object zip extends ZIOApp {
  val zippedZIO: ZIO[Any, Nothing, (Int, String)] =
    ZIO.succeed(8) zip ZIO.succeed("LO")

  override def run: ZIO[Any, Nothing, (Int, String)] = zippedZIO
}

object map extends ZIOApp {
  val zippedZIO: ZIO[Any, Nothing, (Int, String)] =
    ZIO.succeed(8) zip ZIO.succeed("LO")

  val mappedZIO: ZIO[Any, Nothing, String] =
    zippedZIO.map {
      case (int, string) => string * int
    }

  val personZIO: ZIO[Any, Nothing, Person] = zippedZIO.map {
    case (int, string) => Person(string, int)
  }

  override def run: ZIO[Any, Nothing, Person] = personZIO
}

object mapUhOh extends ZIOApp {
  val zippedZIO: ZIO[Any, Nothing, (Int, String)] =
    ZIO.succeed(8) zip ZIO.succeed("LO")

  def printLine(message: String): ZIO[Any, Nothing, Unit] =
    ZIO.succeed(println(message))

  val mappedZIO =
    zippedZIO.map{ tuple => printLine(s"MY BEAUTIFUL TUPLE: $tuple")}

  def run: ZIO[Any, Nothing, ZIO[Any, Nothing, Unit]] = mappedZIO
}

object flatMap extends ZIOApp {
  val zippedZIO: ZIO[Any, Nothing, (Int, String)] =
    ZIO.succeed(8) zip ZIO.succeed("LO")

  def printLine(message: String): ZIO[Any, Nothing, Unit] =
    ZIO.succeed(println(message))

  val flatMappedZIO: ZIO[Any, Nothing, Unit] =
    zippedZIO.flatMap{ tuple => printLine(s"MY BEAUTIFUL TUPLE: $tuple")}

  def run: ZIO[Any, Nothing, Unit] = flatMappedZIO
}

object forComprehension extends ZIOApp {
  val zippedZIO: ZIO[Any, Nothing, (Int, String)] =
    ZIO.succeed(8) zip ZIO.succeed("LO")

  def printLine(message: String): ZIO[Any, Nothing, Unit] =
    ZIO.succeed(println(message))

  val flatMappedZIO =
    zippedZIO
      .flatMap(tuple =>
        printLine(s"MY BEAUTIFUL TUPLE: $tuple")
          .as("Nice")
      )

  def run: ZIO[Any, Nothing, String] = flatMappedZIO
}

object async extends ZIOApp {
  val asyncZIO: ZIO[Any, Nothing, Int] = ZIO.async[Int] { complete =>
    println("Async Beinneth!")
    Thread.sleep(1000)
    complete(10)
  }

  def run = asyncZIO
}

object fork extends ZIOApp {
  val asyncZIO: ZIO[Any, Nothing, Int] = ZIO.async[Int] { complete =>
    println("Async Beinneth!")
    Thread.sleep(2000)
    complete(scala.util.Random.nextInt(999))
  }

  def printLine(message: String): ZIO[Any, Nothing, Unit] =
    ZIO.succeed(println(message))

  val forkedZIO = for {
    fiber  <- asyncZIO.fork
    fiber2 <- asyncZIO.fork
    _      <- printLine("NICE")
    int    <- fiber.join
    int2   <- fiber2.join
  } yield s"My beautiful ints ($int, $int2)"

  val forked2ZIO = ZIO.succeed(5).fork
    .flatMap(fiber =>
      fiber.join
        .map(int => int)
    ) // s"My beautifule int $int"

  def run = forkedZIO
}

object zipPar extends ZIOApp {
  val asyncZIO: ZIO[Any, Nothing, Int] = ZIO.async[Int] { complete =>
    println("Async Beinneth!")
    Thread.sleep(2000)
    complete(scala.util.Random.nextInt(999))
  }

  def run: ZIO[Any, Nothing, (Int, Int)] = asyncZIO zipPar asyncZIO
}

object StackSafety extends ZIOApp {

  val myProgram = ZIO.succeed(println("Howdy!")).repeat(40)

  def run = myProgram
}

object Shift extends ZIOApp {
  val myProgram = ZIO.succeed(5).shift(ExecutionContext.global)
  def run = myProgram
}

object ErrorHandling extends ZIOApp {

  val myProgram = ZIO.fail("Failed")
    .flatMap(_ => ZIO.succeed(println("Here")))
    .catchAll(e => ZIO.succeed(println("Recovered from an error")))
  def run = myProgram
}

object ErrorHandling2 extends ZIOApp {

  val io =
    ZIO.succeed { throw new NoSuchElementException("No such element")}
    .catchAll(_ => ZIO.succeed(println("This should never be shown")))
    .foldCauseZIO(
      c => ZIO.succeed(println(s"Recovered from a cause $c")) *> ZIO.succeed(1),
      _ => ZIO.succeed(0)
    )


  def run = io
}

object Interruption extends ZIOApp {

  val io = for {
    fiber <- ZIO.succeed(println("Howdy!")).forever.ensuring(ZIO.succeed(println("Good bye"))).fork
    _     <- ZIO.succeed(Thread.sleep(1000))
    _     <- fiber.interrupt
  } yield ()

  def run = io
}

object Uninterruptible extends ZIOApp {
  val io = for {
    fiber <- (ZIO.succeed(println("Howdy!")).repeat(100000).uninterruptible *>
               ZIO.succeed(println("Howdy Howdy!")).forever)
        .ensuring(ZIO.succeed(println("Bowdy!"))).fork
    _ <- ZIO.succeed(Thread.sleep(300))
    _ <- fiber.interrupt
  } yield ()

  def run = io
}

object Access extends ZIOApp {

  val zio: ZIO[Int, Nothing, Unit] = ZIO.accessZIO[Int, Nothing, Unit](n => ZIO.succeed(println(n)))
  val zio2: ZIO[Any, Nothing, Unit] = zio.provide(42)

  val zio3 = ZIO.accessZIO[String, Nothing, Unit](str => ZIO.succeed(println(s"Look I'm a $str")))

  val zio4: ZIO[String with Int, Nothing, (Unit, Unit)] = zio zip zio3

  def run = zio2
}


