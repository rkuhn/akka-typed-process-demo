/**
 * Copyright (C) 2017 Roland Kuhn <http://rolandkuhn.com>
 */
package com.rolandkuhn.process_demo

import akka.typed._
import akka.typed.ScalaProcess._

object FirstStep extends App {

  val main =
    OpDSL[Nothing] { implicit opDSL =>
      for {
        self <- opProcessSelf
        actor <- opActorSelf
      } yield {
        println(s"Hello World!")
        println(s"My process reference is $self,")
        println(s"and I live in the actor $actor.")
      }
    }

  ActorSystem("first-step", main.toBehavior)
}
