/**
 * Copyright (C) 2017 Roland Kuhn <http://rolandkuhn.com>
 */
package com.rolandkuhn.process_demo

import akka.typed._
import akka.typed.ScalaProcess._
import java.sql.Connection

object Cleanup {

  def openDatabase(): Connection = ???
  def getCredentials(c: Connection): String = ???

  val authService: ActorRef[AuthRequest] = ???

  case class AuthRequest(credentials: String, replyTo: ActorRef[AuthResult])

  sealed trait AuthResult
  case object AuthRejected extends AuthResult
  case class AuthSuccess(token: String) extends AuthResult

  OpDSL[AuthResult] { implicit opDSL =>
    for {
      self <- opProcessSelf
      db = openDatabase()
      _ <- opCleanup(() => db.close())
      creds = getCredentials(db)
      _ = authService ! AuthRequest(creds, self)
      AuthSuccess(token) <- opRead
    } ??? // use token and database
  }

}
