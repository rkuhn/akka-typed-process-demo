/**
 * Copyright (C) 2017 Roland Kuhn <http://rolandkuhn.com>
 */
package com.rolandkuhn.process_demo

import akka.typed._
import akka.typed.ScalaProcess._
import scala.concurrent.duration._
import scala.collection.immutable
import akka.actor.Cancellable

/**
 * An alternative implementation of akka.typed.patterns.Receiver.
 */
object Receiver {
  import patterns.Receiver.{ Command, GetOne, GetAll, ExternalAddress, GetOneResult, GetAllResult }

  private def opUpdateSimpleState[T, Ex](key: SimpleStateKey[T], afterUpdates: Boolean = true)(
    transform: T ⇒ (T, Ex))(implicit opDSL: OpDSL): Operation[opDSL.Self, Ex] =
    opUpdateState(key, afterUpdates) { s =>
      val (state, extract) = transform(s)
      SetState(state) -> extract
    }

  private def opUpdateReadSimpleState[T](key: SimpleStateKey[T], afterUpdates: Boolean = true)(
    transform: T ⇒ T)(implicit opDSL: OpDSL): Operation[opDSL.Self, T] =
    opUpdateAndReadState(key, afterUpdates)(s => SetState(transform(s)))

  def spawn[T](name: String): Operation[ActorRef[Command[T]], ActorRef[Command[T]]] =
    OpDSL[ActorRef[Command[T]]] { implicit opDSL =>
      for {
        self <- opProcessSelf
        recv <- opSpawn(commands[T](self).named(name).withMailboxCapacity(1000))
      } yield opRead
    }

  private def commands[T](register: ActorRef[ActorRef[Command[T]]]) =
    OpDSL[Command[T]] { implicit opDSL =>
      for {
        self <- opProcessSelf
        _ = register ! self
        key = new SimpleStateKey[State[T]](Empty(self))
        msgs <- opFork(msgHandler(key).named("msgHandler").withMailboxCapacity(1000))
        time <- opFork(timeouts(key).named("timeouts"))
      } yield OpDSL.loopInf(_ =>
        opRead.flatMap {
          case g: GetOne[_]                       => opUpdateSimpleState(key)(_.getOne(g.timeout, g.replyTo)).flatMap(schedule(time.ref)(_))
          case g @ GetAll(t) if t > Duration.Zero => opSchedule(t, GetAll(Duration.Zero)(g.replyTo), self)
          case g: GetAll[_]                       => opUpdateReadSimpleState(key)(_.getAll(g.replyTo))
          case ExternalAddress(replyTo)           => opUnit(replyTo ! msgs.ref)
        })
    }

  private def msgHandler[T](key: SimpleStateKey[State[T]]) =
    OpDSL.loopInf[T] { implicit opDSL =>
      opRead.flatMap(msg => opUpdateReadSimpleState(key)(_.enqueue(msg)))
    }

  private sealed abstract class Timeout
  private case object Timeout extends Timeout

  private def timeouts[T](key: SimpleStateKey[State[T]]) =
    OpDSL[Timeout] { implicit opDSL =>
      for {
        self <- opProcessSelf
      } yield OpDSL.loopInf { _ =>
        opRead
          .flatMap(_ => opUpdateSimpleState(key)(_.timeout()))
          .flatMap(schedule(self)(_))
      }
    }

  private val timeout = new SimpleStateKey[Option[Cancellable]](None)
  private def schedule(t: ActorRef[Timeout])(d: Duration)(implicit opDSL: OpDSL) = {
    val op = d match {
      case f: FiniteDuration => opSchedule(f, Timeout, t).map(Some(_))
      case _                 => opUnit(None)
    }
    op.flatMap(next => opUpdateReadSimpleState(timeout) {
      case None    => next
      case Some(c) => { c.cancel(); next }
    })
  }

  private sealed trait State[T] {
    def getOne(timeout: FiniteDuration, replyTo: ActorRef[GetOneResult[T]]): (State[T], Duration)
    def getAll(replyTo: ActorRef[GetAllResult[T]]): State[T]
    def enqueue(msg: T): State[T]
    def timeout(): (State[T], Duration)
  }

  private case class Empty[T](self: ActorRef[Command[T]]) extends State[T] {
    def getOne(timeout: FiniteDuration, replyTo: ActorRef[GetOneResult[T]]): (State[T], Duration) =
      if (timeout <= Duration.Zero) {
        replyTo ! GetOneResult(self, None)
        this -> Duration.Undefined
      } else {
        val d = Deadline.now + timeout
        Asked(self, immutable.Queue((d, replyTo))) -> timeout
      }
    def getAll(replyTo: ActorRef[GetAllResult[T]]): State[T] = {
      replyTo ! GetAllResult(self, Nil)
      this
    }
    def enqueue(msg: T): State[T] = Queued(self, immutable.Queue(msg))
    def timeout(): (State[T], Duration) = this -> Duration.Undefined
  }

  private case class Queued[T](self: ActorRef[Command[T]], msgs: immutable.Queue[T]) extends State[T] {
    def getOne(timeout: FiniteDuration, replyTo: ActorRef[GetOneResult[T]]): (State[T], Duration) = {
      val (msg, queue) = msgs.dequeue
      replyTo ! GetOneResult(self, Some(msg))
      if (queue.isEmpty) Empty(self) -> Duration.Undefined
      else copy(msgs = queue) -> Duration.Undefined
    }
    def getAll(replyTo: ActorRef[GetAllResult[T]]): State[T] = {
      replyTo ! GetAllResult(self, msgs)
      Empty(self)
    }
    def enqueue(msg: T): State[T] = copy(msgs = msgs.enqueue(msg))
    def timeout(): (State[T], Duration) = this -> Duration.Undefined
  }

  private case class Asked[T](self: ActorRef[Command[T]], q: immutable.Queue[(Deadline, ActorRef[GetOneResult[T]])]) extends State[T] {
    def getOne(timeout: FiniteDuration, replyTo: ActorRef[GetOneResult[T]]): (State[T], Duration) =
      if (timeout <= Duration.Zero) {
        replyTo ! GetOneResult(self, None)
        this -> Duration.Undefined
      } else {
        val d = Deadline.now + timeout
        val next = q.enqueue((d, replyTo))
        val tick = next.map(_._1).min.timeLeft
        Asked(self, next) -> tick
      }
    def getAll(replyTo: ActorRef[GetAllResult[T]]): State[T] = {
      replyTo ! GetAllResult(self, Nil)
      this
    }
    def enqueue(msg: T): State[T] = {
      val ((_, replyTo), next) = q.dequeue
      replyTo ! GetOneResult(self, Some(msg))
      if (next.isEmpty) Empty(self)
      else copy(q = next)
    }
    def timeout(): (State[T], Duration) = {
      val now = Deadline.now
      val (overdue, remaining) = q.partition(_._1 <= now)
      overdue.foreach(_._2 ! GetOneResult(self, None))
      if (remaining.isEmpty) Empty(self) -> Duration.Undefined
      else copy(q = remaining) -> remaining.map(_._1).min.timeLeft
    }
  }
}
