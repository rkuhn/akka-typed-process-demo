/**
 * Copyright (C) 2017 Roland Kuhn <http://rolandkuhn.com>
 */
package com.rolandkuhn.process_demo

import akka.typed._
import akka.typed.ScalaProcess._

object HelloWorld extends App {

  case class WhatIsYourName(replyTo: ActorRef[String])

  val sayHello =
    OpDSL.loopInf[WhatIsYourName] { implicit opDSL =>
      for {
        request <- opRead
      } request.replyTo ! "Hello"
    }

  val theWorld =
    OpDSL.loopInf[WhatIsYourName] { implicit opDSL =>
      for {
        WhatIsYourName(replyTo) <- opRead
      } replyTo ! "World"
    }

  val main =
    OpDSL[String] { implicit opDSL =>
      for {
        self <- opProcessSelf
        hello <- opFork(sayHello.named("hello"))
        _ = hello.ref ! WhatIsYourName(self)
        greeting <- opRead
        world <- opSpawn(theWorld.named("world"))
        _ = world ! MainCmd(WhatIsYourName(self))
        name <- opRead
      } yield {
        println(s"$greeting $name!")
        hello.cancel()
      }
    }

  ActorSystem("process-demo", main.toBehavior)

}
