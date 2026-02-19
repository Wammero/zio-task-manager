package taskmanager.domain

import zio.json._
import java.util.UUID

// --- Typed error hierarchy (вместо throw Exception) ---
sealed trait AppError

object AppError {
  case class TaskNotFound(id: UUID)       extends AppError
  case class ValidationError(msg: String) extends AppError
  case class InternalError(cause: String) extends AppError
}

// --- Error response DTO for API ---
case class ErrorResponse(code: String, message: String)

object ErrorResponse {
  implicit val encoder: JsonEncoder[ErrorResponse] = DeriveJsonEncoder.gen[ErrorResponse]
  implicit val decoder: JsonDecoder[ErrorResponse] = DeriveJsonDecoder.gen[ErrorResponse]

  def fromAppError(err: AppError): ErrorResponse = err match {
    case AppError.TaskNotFound(id)   => ErrorResponse("NOT_FOUND", s"Task $id not found")
    case AppError.ValidationError(m) => ErrorResponse("VALIDATION_ERROR", m)
    case AppError.InternalError(c)   => ErrorResponse("INTERNAL_ERROR", c)
  }
}
