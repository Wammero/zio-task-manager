package taskmanager.service

import taskmanager.domain._
import zio._
import zio.test._
import zio.test.Assertion._

object TaskServiceSpec extends ZIOSpecDefault {

  override def spec = suite("TaskService")(

    test("create — creates task with valid data") {
      for {
        task <- TaskService.create(CreateTaskRequest("My Task", Some("Description")))
      } yield assertTrue(
        task.title == "My Task",
        task.description.contains("Description"),
        task.status == TaskStatus.Todo
      )
    },

    test("create — fails with empty title") {
      for {
        result <- TaskService.create(CreateTaskRequest("  ", None)).either
      } yield assertTrue(
        result == Left(AppError.ValidationError("Title must not be empty"))
      )
    },

    test("getById — returns existing task") {
      for {
        created <- TaskService.create(CreateTaskRequest("Find Me", None))
        found   <- TaskService.getById(created.id)
      } yield assertTrue(found.id == created.id, found.title == "Find Me")
    },

    test("getById — returns TaskNotFound for missing ID") {
      val fakeId = java.util.UUID.randomUUID()
      for {
        result <- TaskService.getById(fakeId).either
      } yield assertTrue(result == Left(AppError.TaskNotFound(fakeId)))
    },

    test("list — returns all tasks and filters by status") {
      for {
        t1   <- TaskService.create(CreateTaskRequest("Todo task", None))
        t2   <- TaskService.create(CreateTaskRequest("Done task", None))
        _    <- TaskService.update(t2.id, UpdateTaskRequest(None, None, Some(TaskStatus.Done)))
        all  <- TaskService.list(None)
        done <- TaskService.list(Some(TaskStatus.Done))
        todo <- TaskService.list(Some(TaskStatus.Todo))
      } yield assertTrue(
        all.exists(_.id == t1.id),
        all.exists(_.id == t2.id),
        done.exists(_.id == t2.id),
        !done.exists(_.id == t1.id),
        todo.exists(_.id == t1.id)
      )
    },

    test("update — modifies task fields") {
      for {
        created <- TaskService.create(CreateTaskRequest("Old Title", Some("Old desc")))
        updated <- TaskService.update(
          created.id,
          UpdateTaskRequest(Some("New Title"), Some("New desc"), Some(TaskStatus.InProgress))
        )
      } yield assertTrue(
        updated.id == created.id,
        updated.title == "New Title",
        updated.description.contains("New desc"),
        updated.status == TaskStatus.InProgress
      )
    },

    test("update — returns TaskNotFound for missing ID") {
      val fakeId = java.util.UUID.randomUUID()
      for {
        result <- TaskService.update(fakeId, UpdateTaskRequest(Some("X"), None, None)).either
      } yield assertTrue(result == Left(AppError.TaskNotFound(fakeId)))
    },

    test("delete — removes task") {
      for {
        task   <- TaskService.create(CreateTaskRequest("Delete me", None))
        _      <- TaskService.delete(task.id)
        result <- TaskService.getById(task.id).either
      } yield assertTrue(result == Left(AppError.TaskNotFound(task.id)))
    },

    test("delete — returns TaskNotFound for missing ID") {
      val fakeId = java.util.UUID.randomUUID()
      for {
        result <- TaskService.delete(fakeId).either
      } yield assertTrue(result == Left(AppError.TaskNotFound(fakeId)))
    },

    test("deleteCompletedBefore — cleans up old completed tasks") {
      for {
        task  <- TaskService.create(CreateTaskRequest("Old completed", None))
        _     <- TaskService.update(task.id, UpdateTaskRequest(None, None, Some(TaskStatus.Done)))
        now   <- Clock.instant
        // threshold в будущем — значит все Done-задачи "старше"
        count <- TaskService.deleteCompletedBefore(now.plusSeconds(1))
      } yield assertTrue(count >= 1)
    },

    test("deleteCompletedBefore — does NOT delete non-completed tasks") {
      for {
        _     <- TaskService.create(CreateTaskRequest("Still todo", None))
        now   <- Clock.instant
        count <- TaskService.deleteCompletedBefore(now.plusSeconds(1))
      } yield assertTrue(count == 0)
    }

  ).provide(InMemoryTaskService.layer)
}
