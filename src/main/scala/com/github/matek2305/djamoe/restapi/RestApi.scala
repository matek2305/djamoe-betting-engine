package com.github.matek2305.djamoe.restapi

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, Route}
import com.github.matek2305.djamoe.app.CompetitionService
import com.github.matek2305.djamoe.auth.AuthService
import com.github.matek2305.djamoe.auth.GetAccessTokenResponse.{AccessToken, InvalidCredentials}
import com.github.matek2305.djamoe.auth.RegisterResponse.{UserRegistered, UsernameTaken}
import com.github.matek2305.djamoe.auth.ValidateAccessTokenResponse.{TokenExpired, TokenIsValid, ValidationFailed}
import com.github.matek2305.djamoe.domain.{MatchId, Score}
import com.github.matek2305.djamoe.restapi.RestApiRequest.{LoginRequest, RegisterRequest}
import com.github.matek2305.djamoe.restapi.RestApiResponse._
import com.typesafe.config.Config

trait RestApi
  extends CompetitionService
    with AuthService
    with SprayJsonSupport
    with Protocols {

  def config: Config

  val unsecuredRoutes: Route = {
    (pathPrefix("login") & post & pathEndOrSingleSlash & entity(as[LoginRequest])) { login =>
      onSuccess(getAccessToken(login.username, login.password)) {
        case AccessToken(jwt) => complete(StatusCodes.OK -> LoginResponse(jwt))
        case InvalidCredentials => complete(StatusCodes.Unauthorized -> "Invalid credentials")
      }
    } ~
      (pathPrefix("register") & post & pathEndOrSingleSlash & entity(as[RegisterRequest])) {
        case RegisterRequest(username, _) if username.length < 4 =>
          complete(StatusCodes.BadRequest -> "Username must have at least 4 chars")
        case RegisterRequest(_, password) if password.length < 8 =>
          complete(StatusCodes.BadRequest -> "Password must have at least 8 chars")
        case RegisterRequest(username, password) =>
          onSuccess(register(username, password)) {
            case UserRegistered(_, _) => complete(StatusCodes.OK)
            case UsernameTaken(_) => complete(StatusCodes.BadRequest -> "Username already taken")
          }
      }
  }

  val routes: Route = {
    (authenticated & logRequestResult("competition-api")) { username =>
      pathPrefix("matches") {
        get {
          pathEndOrSingleSlash {
            onSuccess(allMatches) { matchesMap =>
              val matches = matchesMap
                .map {
                  case (id, entry) => MatchResponseFactory.withoutBets(id, entry, username)
                }
                .toList

              complete(StatusCodes.OK -> GetMatchesResponse(matches))
            }
          } ~
            pathPrefix(JavaUUID.map(MatchId(_))) { matchId =>
              onSuccess(matchById(matchId)) {
                case Some(foundMatch) =>
                  complete(StatusCodes.OK -> GetMatchResponse(MatchResponseFactory.create(matchId, foundMatch, username)))
                case None =>
                  complete(StatusCodes.NotFound -> s"Match with id=$matchId not found")
              }
            }
        } ~
          post {
            pathPrefix(JavaUUID.map(MatchId(_))) { matchId =>
              (pathPrefix("bets") & entity(as[Score])) { bet =>
                onSuccess(makeBet(matchId, username, bet)) { made => complete(StatusCodes.OK -> made) }
              }
            }
          }
      } ~
        pathPrefix("points") {
          (get & pathEndOrSingleSlash) {
            onSuccess(points) { pointsMap =>
              val points = pointsMap
                .map { case (k, v) => PlayerPoints(k, v) }
                .toList

              complete(StatusCodes.OK -> GetPointsResponse(points))
            }
          }
        }
    }
  }

  private def authenticated: Directive1[String] = {
    optionalHeaderValueByName("Authorization").flatMap {
      case Some(jwt) => onSuccess(validateAccessToken(jwt)).flatMap {
        case ValidationFailed => complete(StatusCodes.Unauthorized)
        case TokenExpired => complete(StatusCodes.Unauthorized -> "Token expired.")
        case TokenIsValid(claims) => provide(claims.getOrElse("user", "").asInstanceOf[String])
      }
      case _ => complete(StatusCodes.Unauthorized)
    }
  }
}
