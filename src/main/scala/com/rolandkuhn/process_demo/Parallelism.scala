/**
 * Copyright (C) 2017 Roland Kuhn <http://rolandkuhn.com>
 */
package com.rolandkuhn.process_demo

import akka.typed._
import akka.typed.ScalaProcess._
import scala.concurrent.duration._

object Parallelism extends App {

  case class WhatIsYourName(replyTo: ActorRef[String])

  val sayName =
    OpDSL[WhatIsYourName] { implicit opDSL =>
      for {
        self <- opActorSelf
      } yield OpDSL.loopInf { _ =>
        for {
          request <- opRead
        } request.replyTo ! self.path.name
      }
    }

  def opAsk[T, U](target: ActorRef[T], msg: ActorRef[U] => T): Operation[U, U] =
    OpDSL[U] { implicit opDSL =>
      for {
        self <- opProcessSelf
        _ = println(s"$self asking $target")
        _ = target ! msg(self)
      } yield opRead
    }

  def opParallel[T](processes: Process[_, T]*)(implicit opDSL: OpDSL): Operation[opDSL.Self, List[T]] = {
    def forkAll(self: ActorRef[(T, Int)], index: Int, procs: List[Process[_, T]])(implicit opDSL: OpDSL): Operation[opDSL.Self, Unit] =
      procs match {
        case x :: xs =>
          opFork(x.foreach(t => { println(s"collecting answer $t"); self ! (t -> index) }))
            .flatMap(s => { println(s"spawned ${s.ref}"); forkAll(self, index + 1, xs) })
        case Nil => opUnit(())
      }

    opCall(OpDSL[(T, Int)] { implicit opDSL =>
      for {
        self <- opProcessSelf
        _ <- forkAll(self, 0, processes.toList)
        results <- OpDSL.loop(processes.size)(_ => { println("waiting for answer"); opRead })
      } yield results.sortBy(_._2).map(_._1)
    }.withMailboxCapacity(processes.size))
  }

  val mainToName = WhatIsYourName andThen MainCmd.apply _

  val main =
    OpDSL[Nothing] { implicit opDSL =>
      for {
        hello <- opSpawn(sayName.named("hello"))
        world <- opSpawn(sayName.named("world"))
        List(greeting, name) <- opParallel(
          opAsk(hello, mainToName).withTimeout(1.second),
          opAsk(world, mainToName).withTimeout(1.second)
        )
      } yield {
        println(s"$greeting $name!")
      }
    }

  ActorSystem("process-demo", main.toBehavior)

}
