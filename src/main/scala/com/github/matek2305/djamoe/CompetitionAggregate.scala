package com.github.matek2305.djamoe

import akka.actor.{ActorLogging, Props}
import akka.pattern.pipe
import akka.persistence.{PersistentActor, RecoveryCompleted}

import scala.concurrent.{Future, Promise}

class CompetitionAggregate(id: String) extends PersistentActor with ActorLogging {

  import CompetitionAggregate._
  import context._

  private var state = CompetitionState()

  override def persistenceId: String = id

  override def receiveCommand: Receive = {
    case GetAllMatches => sender() ! state()
    case GetPoints => sender() ! state.table
    case CreateMatch(details) =>
      handleEvent(MatchCreated(MatchId(), details)) pipeTo sender()
      ()
    case FinishMatch(matchId, score) =>
      handleEvent(MatchFinished(matchId, score)) pipeTo sender()
      ()
    case MakeBet(matchId, bet) => state.matches(matchId).status match {
      case MatchState.LOCKED => sender() ! BettingAlreadyLocked(matchId)
      case MatchState.FINISHED => sender() ! MatchHaveFinished(matchId)
      case MatchState.CREATED =>
        handleEvent(BetMade(matchId, bet)) pipeTo sender()
        ()
    }
    case LockBetting(matchId) =>
      handleEvent(BettingLocked(matchId)) pipeTo sender()
      ()
  }

  override def receiveRecover: Receive = {
    case event: MatchEvent => state = applyEvent(event)
    case RecoveryCompleted => log.info("Recovery completed!")
  }

  private def handleEvent[E <: MatchEvent](e: => E): Future[E] = {
    val promise = Promise[E]
    persist(e) { event =>
      promise.success(event)
      state = applyEvent(event)
      system.eventStream.publish(event)
    }
    promise.future
  }

  private def applyEvent(event: MatchEvent): CompetitionState = {
    case MatchCreated(matchId, details) => state.add(matchId, details)
    case MatchFinished(matchId, score) => state.finishMatch(matchId, score)
    case BetMade(matchId, bet) => state.addBet(matchId, bet)
    case BettingLocked(matchId) => state.lockBetting(matchId)
  }
}

object CompetitionAggregate {

  def props(id: String) = Props(new CompetitionAggregate(id))

  sealed trait MatchCommand

  final case class GetAllMatches() extends MatchCommand
  final case class CreateMatch(details: Match) extends MatchCommand
  final case class FinishMatch(id: MatchId, score: MatchScore) extends MatchCommand
  final case class LockBetting(id: MatchId) extends MatchCommand
  final case class MakeBet(id: MatchId, bet: Bet) extends MatchCommand
  final case class GetPoints() extends MatchCommand

  sealed trait MatchEvent {
    val id: MatchId
  }

  final case class MatchCreated(id: MatchId, details: Match) extends MatchEvent
  final case class MatchFinished(id: MatchId, score: MatchScore) extends MatchEvent
  final case class BetMade(id: MatchId, bet: Bet) extends MatchEvent
  final case class BettingLocked(id: MatchId) extends MatchEvent

  final case class BettingAlreadyLocked(id: MatchId)
    extends RuntimeException(s"Betting already locked for match with id=$id")

  final case class MatchHaveFinished(id: MatchId)
    extends RuntimeException(s"Match with id=$id have finished")

}
