package taskmanager.api

import taskmanager.domain._
import taskmanager.service.TaskService
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import org.slf4j.LoggerFactory
import sttp.tapir.server.akkahttp.AkkaHttpServerInterpreter
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import zio.{IO, Runtime, UIO, Unsafe}

import scala.concurrent.{ExecutionContext, Future}

// --- Server routes: связывает Tapir endpoints с ZIO бизнес-логикой через Akka HTTP ---
class Routes(taskService: TaskService, runtime: Runtime[Any])(implicit ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(getClass)

  // Мост ZIO → Future: Akka HTTP работает с Future, а наша логика на ZIO.
  // .mapError конвертирует доменные ошибки в API-ответы.
  // .either превращает ZIO[Any, E, A] в ZIO[Any, Nothing, Either[E, A]].
  private def run[A](zio: IO[AppError, A]): Future[Either[ErrorResponse, A]] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(
        zio.mapError(ErrorResponse.fromAppError).either
      )
    }

  private def runInfallible[A](zio: UIO[A]): Future[Either[ErrorResponse, A]] =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.runToFuture(zio.map(Right(_)))
    }

  // --- Server endpoints: endpoint + server logic ---
  private val serverEndpoints: List[ServerEndpoint[Any, Future]] = List(
    Endpoints.createTask.serverLogic(req =>
      run(taskService.create(req))
    ),

    Endpoints.getTask.serverLogic(id =>
      run(taskService.getById(id))
    ),

    Endpoints.listTasks.serverLogic(status =>
      runInfallible(taskService.list(status))
    ),

    Endpoints.updateTask.serverLogic { case (id, req) =>
      run(taskService.update(id, req))
    },

    Endpoints.deleteTask.serverLogic(id =>
      run(taskService.delete(id))
    ),

    Endpoints.healthCheck.serverLogicSuccess(_ =>
      Future.successful("OK")
    )
  )

  // Swagger UI автоматически генерируется из описания endpoint'ов
  private val swaggerEndpoints: List[ServerEndpoint[Any, Future]] =
    SwaggerInterpreter().fromServerEndpoints[Future](
      serverEndpoints,
      "Functional Task Manager",
      "1.0.0"
    )

  // --- Correlation ID middleware ---
  // В микросервисах Тинькофф это must-have: сквозной ID для трейсинга запросов.
  private def withCorrelationId(inner: Route): Route =
    optionalHeaderValueByName("X-Correlation-Id") { maybeId =>
      extractRequest { request =>
        val correlationId = maybeId.getOrElse(java.util.UUID.randomUUID().toString)
        logger.info(s"[$correlationId] ${request.method.value} ${request.uri.path}")
        respondWithHeader(RawHeader("X-Correlation-Id", correlationId)) {
          inner
        }
      }
    }

  // Финальный Route для Akka HTTP сервера
  val route: Route = withCorrelationId {
    AkkaHttpServerInterpreter().toRoute(serverEndpoints ++ swaggerEndpoints)
  }
}
