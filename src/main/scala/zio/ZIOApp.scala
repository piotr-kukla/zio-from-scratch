package zio

import zio.ZIO._

case class Person(name: String, age: Int)

object Person {
  val peter: Person = Person("Peter", 88)
}


trait ZIOApp {

  def run: ZIO[Any]

  def main(args: Array[String]): Unit =
    run.run(result => println(s"The result was $result"))

}

object succeedNow extends ZIOApp {

  val peterZIO: ZIO[Person] = ZIO.succeedNow(Person.peter)

  override def run: ZIO[Person] = peterZIO
}

object succeed extends ZIOApp {
  val howdyZIO = ZIO.succeed(println("Howdy!"));

  override def run: ZIO[Unit] = howdyZIO
}

object zip extends ZIOApp {
  val zippedZIO: ZIO[(Int, String)] =
    ZIO.succeed(8) zip ZIO.succeed("LO")

  override def run: ZIO[(Int, String)] = zippedZIO
}

object map extends ZIOApp {
  val zippedZIO: ZIO[(Int, String)] =
    ZIO.succeed(8) zip ZIO.succeed("LO")

  val mappedZIO: ZIO[String] =
    zippedZIO.map {
      case (int, string) => string * int
    }

  val personZIO: ZIO[Person] = zippedZIO.map {
    case (int, string) => Person(string, int)
  }

  override def run: ZIO[Person] = personZIO
}

object mapUhOh extends ZIOApp {
  val zippedZIO: ZIO[(Int, String)] =
    ZIO.succeed(8) zip ZIO.succeed("LO")

  def printLine(message: String): ZIO[Unit] =
    ZIO.succeed(println(message))

  val mappedZIO =
    zippedZIO.map{ tuple => printLine(s"MY BEAUTIFUL TUPLE: $tuple")}

  def run: ZIO[ZIO[Unit]] = mappedZIO
}

object flatMap extends ZIOApp {
  val zippedZIO: ZIO[(Int, String)] =
    ZIO.succeed(8) zip ZIO.succeed("LO")

  def printLine(message: String): ZIO[Unit] =
    ZIO.succeed(println(message))

  val flatMappedZIO: ZIO[Unit] =
    zippedZIO.flatMap{ tuple => printLine(s"MY BEAUTIFUL TUPLE: $tuple")}

  def run: ZIO[Unit] = flatMappedZIO
}

object forComprehension extends ZIOApp {
  val zippedZIO: ZIO[(Int, String)] =
    ZIO.succeed(8) zip ZIO.succeed("LO")

  def printLine(message: String): ZIO[Unit] =
    ZIO.succeed(println(message))

  val flatMappedZIO =
    zippedZIO
      .flatMap(tuple =>
        printLine(s"MY BEAUTIFUL TUPLE: $tuple")
          .as("Nice")
      )

  def run: ZIO[String] = flatMappedZIO
}

object async extends ZIOApp {
  val asyncZIO: ZIO[Int] = ZIO.async[Int] { complete =>
    println("Async Beinneth!")
    Thread.sleep(1000)
    complete(10)
  }

  def run = asyncZIO
}


