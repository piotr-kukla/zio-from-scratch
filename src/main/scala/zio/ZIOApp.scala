package zio

import zio.ZIO._

import scala.concurrent.ExecutionContext

case class Person(name: String, age: Int)

object Person {
  val peter: Person = Person("Peter", 88)
}


trait ZIOApp {

  def run: ZIO[Any, Any]

  def main(args: Array[String]): Unit = {
    val result = run.unsafeRunSync
    println(s"The result was $result")
  }


}

object succeedNow extends ZIOApp {

  val peterZIO: ZIO[Nothing, Person] = ZIO.succeedNow(Person.peter)

  override def run: ZIO[Nothing, Person] = peterZIO
}

object succeed extends ZIOApp {
  val howdyZIO = ZIO.succeed(println("Howdy!"))

  override def run: ZIO[Nothing, Unit] = howdyZIO
}

object zip extends ZIOApp {
  val zippedZIO: ZIO[Nothing, (Int, String)] =
    ZIO.succeed(8) zip ZIO.succeed("LO")

  override def run: ZIO[Nothing, (Int, String)] = zippedZIO
}

object map extends ZIOApp {
  val zippedZIO: ZIO[Nothing, (Int, String)] =
    ZIO.succeed(8) zip ZIO.succeed("LO")

  val mappedZIO: ZIO[Nothing, String] =
    zippedZIO.map {
      case (int, string) => string * int
    }

  val personZIO: ZIO[Nothing, Person] = zippedZIO.map {
    case (int, string) => Person(string, int)
  }

  override def run: ZIO[Nothing, Person] = personZIO
}

object mapUhOh extends ZIOApp {
  val zippedZIO: ZIO[Nothing, (Int, String)] =
    ZIO.succeed(8) zip ZIO.succeed("LO")

  def printLine(message: String): ZIO[Nothing, Unit] =
    ZIO.succeed(println(message))

  val mappedZIO =
    zippedZIO.map{ tuple => printLine(s"MY BEAUTIFUL TUPLE: $tuple")}

  def run: ZIO[Nothing, ZIO[Nothing, Unit]] = mappedZIO
}

object flatMap extends ZIOApp {
  val zippedZIO: ZIO[Nothing, (Int, String)] =
    ZIO.succeed(8) zip ZIO.succeed("LO")

  def printLine(message: String): ZIO[Nothing, Unit] =
    ZIO.succeed(println(message))

  val flatMappedZIO: ZIO[Nothing, Unit] =
    zippedZIO.flatMap{ tuple => printLine(s"MY BEAUTIFUL TUPLE: $tuple")}

  def run: ZIO[Nothing, Unit] = flatMappedZIO
}

object forComprehension extends ZIOApp {
  val zippedZIO: ZIO[Nothing, (Int, String)] =
    ZIO.succeed(8) zip ZIO.succeed("LO")

  def printLine(message: String): ZIO[Nothing, Unit] =
    ZIO.succeed(println(message))

  val flatMappedZIO =
    zippedZIO
      .flatMap(tuple =>
        printLine(s"MY BEAUTIFUL TUPLE: $tuple")
          .as("Nice")
      )

  def run: ZIO[Nothing, String] = flatMappedZIO
}

object async extends ZIOApp {
  val asyncZIO: ZIO[Nothing, Int] = ZIO.async[Int] { complete =>
    println("Async Beinneth!")
    Thread.sleep(1000)
    complete(10)
  }

  def run = asyncZIO
}

object fork extends ZIOApp {
  val asyncZIO: ZIO[Nothing, Int] = ZIO.async[Int] { complete =>
    println("Async Beinneth!")
    Thread.sleep(2000)
    complete(scala.util.Random.nextInt(999))
  }

  def printLine(message: String): ZIO[Nothing, Unit] =
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

  def run = forked2ZIO
}

object zipPar extends ZIOApp {
  val asyncZIO: ZIO[Nothing, Int] = ZIO.async[Int] { complete =>
    println("Async Beinneth!")
    Thread.sleep(2000)
    complete(scala.util.Random.nextInt(999))
  }

  def run: ZIO[Nothing, (Int, Int)] = asyncZIO zipPar asyncZIO
}

object StackSafety extends ZIOApp {

  val myProgram = ZIO.succeed(println("Howdy!")).repeat(100000)

  def run = myProgram
}

object Shift extends ZIOApp {
  val myProgram = ZIO.succeed(5).shift(ExecutionContext.global)
  def run = myProgram
}


