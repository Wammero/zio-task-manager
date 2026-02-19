package taskmanager.api

import taskmanager.domain._
import sttp.tapir._
import sttp.tapir.json.zio._
import sttp.tapir.generic.auto._
import java.time.Instant
import java.util.UUID

// --- Tapir endpoints: type-safe описание API ---
// Каждый endpoint — это чистое значение (data), без побочных эффектов.
// Из этих описаний Tapir генерирует: серверные маршруты, Swagger UI, клиенты.
object Endpoints {

  // Tapir Schema для кастомных типов (нужен для OpenAPI документации)
  implicit val instantSchema: Schema[Instant]       = Schema.string.format("date-time")
  implicit val taskStatusSchema: Schema[TaskStatus]  = Schema.string

  // Codec для TaskStatus в query-параметрах (?status=todo)
  implicit val taskStatusCodec: Codec[String, TaskStatus, CodecFormat.TextPlain] =
    Codec.string.mapDecode { s: String =>
      val result: DecodeResult[TaskStatus] = s match {
        case "todo"        => DecodeResult.Value(TaskStatus.Todo)
        case "in_progress" => DecodeResult.Value(TaskStatus.InProgress)
        case "done"        => DecodeResult.Value(TaskStatus.Done)
        case other         => DecodeResult.Error(other, new RuntimeException(s"Unknown status: $other"))
      }
      result
    } { ts: TaskStatus =>
      ts match {
        case TaskStatus.Todo       => "todo"
        case TaskStatus.InProgress => "in_progress"
        case TaskStatus.Done       => "done"
      }
    }

  private val baseEndpoint = endpoint.in("api")

  // POST /api/tasks — создать задачу
  val createTask: PublicEndpoint[CreateTaskRequest, ErrorResponse, Task, Any] =
    baseEndpoint.post
      .in("tasks")
      .in(jsonBody[CreateTaskRequest].description("Task to create"))
      .out(jsonBody[Task].description("Created task"))
      .errorOut(jsonBody[ErrorResponse])
      .summary("Create a new task")
      .tag("Tasks")

  // GET /api/tasks/:id — получить задачу по ID
  val getTask: PublicEndpoint[UUID, ErrorResponse, Task, Any] =
    baseEndpoint.get
      .in("tasks" / path[UUID]("taskId").description("Task identifier"))
      .out(jsonBody[Task])
      .errorOut(jsonBody[ErrorResponse])
      .summary("Get task by ID")
      .tag("Tasks")

  // GET /api/tasks?status=todo — список задач с фильтром
  val listTasks: PublicEndpoint[Option[TaskStatus], ErrorResponse, List[Task], Any] =
    baseEndpoint.get
      .in("tasks")
      .in(query[Option[TaskStatus]]("status").description("Filter by status: todo, in_progress, done"))
      .out(jsonBody[List[Task]])
      .errorOut(jsonBody[ErrorResponse])
      .summary("List all tasks")
      .tag("Tasks")

  // PUT /api/tasks/:id — обновить задачу
  val updateTask: PublicEndpoint[(UUID, UpdateTaskRequest), ErrorResponse, Task, Any] =
    baseEndpoint.put
      .in("tasks" / path[UUID]("taskId"))
      .in(jsonBody[UpdateTaskRequest].description("Fields to update"))
      .out(jsonBody[Task])
      .errorOut(jsonBody[ErrorResponse])
      .summary("Update a task")
      .tag("Tasks")

  // DELETE /api/tasks/:id — удалить задачу
  val deleteTask: PublicEndpoint[UUID, ErrorResponse, Unit, Any] =
    baseEndpoint.delete
      .in("tasks" / path[UUID]("taskId"))
      .out(emptyOutput)
      .errorOut(jsonBody[ErrorResponse])
      .summary("Delete a task")
      .tag("Tasks")

  // GET /health — healthcheck
  val healthCheck: PublicEndpoint[Unit, Unit, String, Any] =
    endpoint.get
      .in("health")
      .out(stringBody)
      .summary("Health check")
      .tag("System")

  // Все endpoint'ы — для генерации Swagger
  val all: List[AnyEndpoint] =
    List(createTask, getTask, listTasks, updateTask, deleteTask, healthCheck)
}
