package taskmanager

import taskmanager.api.Routes
import taskmanager.service.{InMemoryTaskService, TaskService}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import zio._

object Main extends ZIOAppDefault {

  override def run: ZIO[Any, Throwable, Unit] = {
    val program = for {
      taskService <- ZIO.service[TaskService]
      runtime     <- ZIO.runtime[Any]
      _           <- startServer(taskService, runtime)
    } yield ()

    program.provide(InMemoryTaskService.layer)
  }

  private def startServer(taskService: TaskService, runtime: Runtime[Any]): Task[Unit] =
    ZIO.scoped {
      for {
        // Управление жизненным циклом ActorSystem через ZIO.acquireRelease
        system <- ZIO.acquireRelease(
                    ZIO.succeed(ActorSystem("task-manager"))
                  )(sys => ZIO.fromFuture(_ => sys.terminate()).orDie)

        routes = {
          implicit val ec: scala.concurrent.ExecutionContext = system.dispatcher
          new Routes(taskService, runtime)
        }

        binding <- {
          implicit val sys: ActorSystem = system
          ZIO.fromFuture(_ => Http().newServerAt("0.0.0.0", 8080).bind(routes.route))
        }

        _ <- Console.printLine(s"Server started at http://localhost:8080")
        _ <- Console.printLine(s"Swagger UI:       http://localhost:8080/docs")
        _ <- Console.printLine(s"Health check:     http://localhost:8080/health")

        // Фоновый fiber для очистки завершённых задач старше 24 часов.
        // Fiber в ZIO — это lightweight thread, управляемый рантаймом.
        _ <- backgroundCleanup(taskService).fork

        // ZIO.never блокирует навсегда — сервер работает до Ctrl+C.
        _ <- ZIO.never
      } yield ()
    }

  // Периодическая очистка: каждый час удаляет Done-задачи старше 24h.
  // Schedule.fixed(1.hour) — декларативное расписание повторений.
  private def backgroundCleanup(taskService: TaskService): UIO[Unit] = {
    val cleanup = for {
      now       <- Clock.instant
      threshold  = now.minusSeconds(86400) // 24 hours
      count     <- taskService.deleteCompletedBefore(threshold)
      _         <- ZIO.when(count > 0)(
                     Console.printLine(s"Background cleanup: removed $count completed task(s)").orDie
                   )
    } yield ()

    cleanup
      .repeat(Schedule.fixed(1.hour))
      .unit
  }
}
