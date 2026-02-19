package taskmanager.domain

import zio.json._
import java.time.Instant
import java.util.UUID

// --- TaskStatus ADT (Algebraic Data Type) ---
sealed trait TaskStatus

object TaskStatus {
  case object Todo       extends TaskStatus
  case object InProgress extends TaskStatus
  case object Done       extends TaskStatus

  implicit val encoder: JsonEncoder[TaskStatus] = JsonEncoder[String].contramap {
    case Todo       => "todo"
    case InProgress => "in_progress"
    case Done       => "done"
  }

  implicit val decoder: JsonDecoder[TaskStatus] = JsonDecoder[String].mapOrFail {
    case "todo"        => Right(Todo)
    case "in_progress" => Right(InProgress)
    case "done"        => Right(Done)
    case other         => Left(s"Unknown task status: $other")
  }
}

// --- Instant JSON codecs (zio-json doesn't have built-in support) ---
object JsonCodecs {
  implicit val instantEncoder: JsonEncoder[Instant] =
    JsonEncoder[String].contramap(_.toString)

  implicit val instantDecoder: JsonDecoder[Instant] =
    JsonDecoder[String].mapOrFail { s =>
      try Right(Instant.parse(s))
      catch { case e: Exception => Left(s"Invalid instant: ${e.getMessage}") }
    }
}

// --- Domain model ---
case class Task(
  id: UUID,
  title: String,
  description: Option[String],
  status: TaskStatus,
  createdAt: Instant,
  updatedAt: Instant
)

object Task {
  import JsonCodecs._
  implicit val encoder: JsonEncoder[Task] = DeriveJsonEncoder.gen[Task]
  implicit val decoder: JsonDecoder[Task] = DeriveJsonDecoder.gen[Task]
}

// --- API DTOs ---
case class CreateTaskRequest(
  title: String,
  description: Option[String]
)

object CreateTaskRequest {
  implicit val encoder: JsonEncoder[CreateTaskRequest] = DeriveJsonEncoder.gen[CreateTaskRequest]
  implicit val decoder: JsonDecoder[CreateTaskRequest] = DeriveJsonDecoder.gen[CreateTaskRequest]
}

case class UpdateTaskRequest(
  title: Option[String],
  description: Option[String],
  status: Option[TaskStatus]
)

object UpdateTaskRequest {
  implicit val encoder: JsonEncoder[UpdateTaskRequest] = DeriveJsonEncoder.gen[UpdateTaskRequest]
  implicit val decoder: JsonDecoder[UpdateTaskRequest] = DeriveJsonDecoder.gen[UpdateTaskRequest]
}
