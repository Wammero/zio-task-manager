package taskmanager.service

import taskmanager.domain.{AppError, CreateTaskRequest, Task => DomainTask, TaskStatus, UpdateTaskRequest}
import zio.{Clock, IO, Ref, UIO, ULayer, ZIO, ZLayer}
import java.time.Instant
import java.util.UUID

// --- In-memory реализация через ZIO Ref (concurrent-safe mutable state) ---
// Ref — это атомарная ссылка, безопасная для конкурентного доступа.
// В отличие от var или mutable.Map, Ref гарантирует consistency без lock'ов.
case class InMemoryTaskService(ref: Ref[Map[UUID, DomainTask]]) extends TaskService {

  override def create(request: CreateTaskRequest): IO[AppError, DomainTask] =
    for {
      _ <- ZIO.when(request.title.trim.isEmpty)(
             ZIO.fail(AppError.ValidationError("Title must not be empty"))
           )
      now  <- Clock.instant
      id    = UUID.randomUUID()
      task  = DomainTask(
                id          = id,
                title       = request.title.trim,
                description = request.description.map(_.trim).filter(_.nonEmpty),
                status      = TaskStatus.Todo,
                createdAt   = now,
                updatedAt   = now
              )
      _ <- ref.update(_ + (id -> task))
    } yield task

  override def getById(id: UUID): IO[AppError, DomainTask] =
    ref.get.flatMap { tasks =>
      ZIO.fromOption(tasks.get(id)).orElseFail(AppError.TaskNotFound(id))
    }

  override def list(statusFilter: Option[TaskStatus]): UIO[List[DomainTask]] =
    ref.get.map { tasks =>
      val all = tasks.values.toList.sortBy(_.createdAt)
      statusFilter.fold(all)(s => all.filter(_.status == s))
    }

  override def update(id: UUID, request: UpdateTaskRequest): IO[AppError, DomainTask] =
    for {
      now <- Clock.instant
      result <- ref.modify { tasks =>
        tasks.get(id) match {
          case None =>
            (Left(AppError.TaskNotFound(id)), tasks)
          case Some(existing) =>
            val updated = existing.copy(
              title       = request.title.map(_.trim).filter(_.nonEmpty).getOrElse(existing.title),
              description = request.description.orElse(existing.description),
              status      = request.status.getOrElse(existing.status),
              updatedAt   = now
            )
            (Right(updated), tasks + (id -> updated))
        }
      }
      task <- ZIO.fromEither(result)
    } yield task

  override def delete(id: UUID): IO[AppError, Unit] =
    ref.modify { tasks =>
      if (tasks.contains(id)) (Right(()), tasks - id)
      else (Left(AppError.TaskNotFound(id)), tasks)
    }.flatMap(ZIO.fromEither(_))

  // Удаляет завершённые задачи старше threshold — для фонового cleanup'а
  override def deleteCompletedBefore(threshold: Instant): UIO[Int] =
    ref.modify { tasks =>
      val toDelete = tasks.values.filter { t =>
        t.status == TaskStatus.Done && t.updatedAt.isBefore(threshold)
      }.map(_.id).toSet
      (toDelete.size, tasks -- toDelete)
    }
}

object InMemoryTaskService {
  // ZLayer — ключевой механизм DI в ZIO.
  // ULayer[TaskService] означает: слой без зависимостей, который предоставляет TaskService.
  // Можно легко подменить на PostgresTaskService.layer для продакшена.
  val layer: ULayer[TaskService] = ZLayer {
    Ref.make(Map.empty[UUID, DomainTask]).map(InMemoryTaskService(_))
  }
}
