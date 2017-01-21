/**
 * Copyright (C) 2017 Roland Kuhn <http://rolandkuhn.com>
 */
package com.rolandkuhn.process_demo

import akka.typed._
import akka.typed.ScalaProcess._

object AskPattern extends App {

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
        _ = target ! msg(self)
      } yield opRead
    }

  val mainForName = WhatIsYourName andThen MainCmd.apply _

  val main =
    OpDSL[Nothing] { implicit opDSL =>
      for {
        hello <- opSpawn(sayName.named("hello"))
        greeting <- opCall(opAsk(hello, mainForName).named("getGreeting"))
        world <- opFork(sayName.named("world"))
        name <- opCall(opAsk(world.ref, WhatIsYourName).named("getName"))
      } yield {
        println(s"$greeting $name!")
        world.cancel()
      }
    }

  ActorSystem("process-demo", main.toBehavior)

}
