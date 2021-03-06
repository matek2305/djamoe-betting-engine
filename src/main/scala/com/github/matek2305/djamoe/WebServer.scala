package com.github.matek2305.djamoe

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.Timeout
import com.github.matek2305.djamoe.app.{CompetitionActor, LocalDateTimeProvider}
import com.github.matek2305.djamoe.auth.AuthActor
import com.github.matek2305.djamoe.domain.{MakeBetPolicy, TimeProvider}
import com.github.matek2305.djamoe.domain.MakeBetPolicy.LockBettingBeforeMatchStart
import com.github.matek2305.djamoe.restapi.{AdminRestApi, RestApi}
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.duration._
import scala.concurrent.ExecutionContextExecutor

object WebServer extends App with RestApi with AdminRestApi {

  implicit val timeProvider: TimeProvider = new LocalDateTimeProvider
  implicit val makeBetPolicy: MakeBetPolicy = new LockBettingBeforeMatchStart(60)

  override implicit val system: ActorSystem = ActorSystem()
  override implicit val materializer: Materializer = ActorMaterializer()
  override implicit val executor: ExecutionContextExecutor = system.dispatcher
  override implicit val timeout: Timeout = Timeout(30.seconds)

  override def config: Config = ConfigFactory.load()

  override def competitionActor: ActorRef = system.actorOf(CompetitionActor.props("competition1"))
  override def authActor: ActorRef = system.actorOf(AuthActor.props("users"))

  val interface = config.getString("http.interface")
  val port = config.getInt("http.port")

  Http().bindAndHandle(adminRoutes ~ unsecuredRoutes ~ routes, interface, port)
  println(s"Server online at http://$interface:$port/")
}
