package taskmanager.service

import taskmanager.domain.{AppError, CreateTaskRequest, Task => DomainTask, TaskStatus, UpdateTaskRequest}
import zio.{IO, UIO, URIO, ZIO}
import java.time.Instant
import java.util.UUID

// --- Service trait: описывает контракт бизнес-логики ---
// IO[E, A] = ZIO[Any, E, A] — эффект без зависимостей
// UIO[A] = ZIO[Any, Nothing, A] — эффект, который не может упасть
trait TaskService {
  def create(request: CreateTaskRequest): IO[AppError, DomainTask]
  def getById(id: UUID): IO[AppError, DomainTask]
  def list(statusFilter: Option[TaskStatus]): UIO[List[DomainTask]]
  def update(id: UUID, request: UpdateTaskRequest): IO[AppError, DomainTask]
  def delete(id: UUID): IO[AppError, Unit]
  def deleteCompletedBefore(threshold: Instant): UIO[Int]
}

// --- Accessor methods для удобного вызова через ZLayer ---
// Позволяют писать: TaskService.create(req) вместо ZIO.serviceWithZIO[TaskService](_.create(req))
object TaskService {
  def create(request: CreateTaskRequest): ZIO[TaskService, AppError, DomainTask] =
    ZIO.serviceWithZIO[TaskService](_.create(request))

  def getById(id: UUID): ZIO[TaskService, AppError, DomainTask] =
    ZIO.serviceWithZIO[TaskService](_.getById(id))

  def list(statusFilter: Option[TaskStatus]): URIO[TaskService, List[DomainTask]] =
    ZIO.serviceWithZIO[TaskService](_.list(statusFilter))

  def update(id: UUID, request: UpdateTaskRequest): ZIO[TaskService, AppError, DomainTask] =
    ZIO.serviceWithZIO[TaskService](_.update(id, request))

  def delete(id: UUID): ZIO[TaskService, AppError, Unit] =
    ZIO.serviceWithZIO[TaskService](_.delete(id))

  def deleteCompletedBefore(threshold: Instant): URIO[TaskService, Int] =
    ZIO.serviceWithZIO[TaskService](_.deleteCompletedBefore(threshold))
}
