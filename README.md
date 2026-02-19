# Functional Task Manager

Backend-сервис для управления задачами, написанный на **Scala 2.13** в функциональном стиле.

## Зачем нужен проект

Классический CRUD выглядит просто — но в продакшене он обрастает проблемами: гонки при конкурентном доступе, необработанные ошибки, хрупкие API-контракты, сервисы, намертво связанные с конкретной базой данных.

Этот проект показывает, как функциональное программирование решает каждую из этих проблем **на уровне компиляции**, а не в рантайме.

## Какую проблему решает каждая технология

### ZIO 2 — управление эффектами

**Проблема:** В императивном коде побочные эффекты (запросы к БД, HTTP-вызовы, чтение времени) скрыты внутри функций. Невозможно понять по сигнатуре, что функция делает, может ли она упасть и от чего зависит.

**Решение:** ZIO делает эффекты явными через тип `ZIO[R, E, A]`:
- `R` — какие зависимости нужны (например, `TaskService`)
- `E` — какие ошибки возможны (например, `AppError.TaskNotFound`)
- `A` — что возвращает в случае успеха (например, `Task`)

```scala
// Сигнатура говорит всё: нужен TaskService, может вернуть AppError, результат — Task
def create(request: CreateTaskRequest): ZIO[TaskService, AppError, Task]
```

Вместо `throw new Exception("not found")` ошибки становятся **типами данных** — компилятор заставляет их обработать.

### ZLayer — Dependency Injection

**Проблема:** Как тестировать сервис, который ходит в PostgreSQL? Поднимать Docker в каждом тесте?

**Решение:** ZLayer позволяет описать зависимости как слои и подменять их:

```scala
// В продакшене — реальная база
program.provide(PostgresTaskService.layer)

// В тестах — in-memory заглушка (та же логика, без Docker)
program.provide(InMemoryTaskService.layer)
```

Слои проверяются **на этапе компиляции** — если забыл предоставить зависимость, код не скомпилируется.

### ZIO Ref — конкурентное состояние без lock'ов

**Проблема:** `var` и `mutable.Map` ломаются при конкурентном доступе. `synchronized` работает, но медленно и легко приводит к дедлокам.

**Решение:** `Ref[Map[UUID, Task]]` — атомарная ссылка, безопасная для конкурентного доступа. Операции `update` и `modify` выполняются атомарно, без явных блокировок:

```scala
ref.modify { tasks =>
  tasks.get(id) match {
    case Some(existing) => (Right(updated), tasks + (id -> updated))
    case None           => (Left(TaskNotFound(id)), tasks)
  }
}
```

### Tapir — type-safe API

**Проблема:** REST API описан строками (`"/api/tasks/:id"`), типы запросов/ответов не связаны с кодом. Переименовал поле в модели — API молча возвращает `null`, а узнаёшь об этом только из бага в проде.

**Решение:** Tapir описывает эндпоинты как **значения** с типами. Если изменить тип поля в `Task`, сломается компиляция эндпоинта, а не рантайм:

```scala
val getTask: PublicEndpoint[UUID, ErrorResponse, Task, Any] =
  endpoint.get
    .in("api" / "tasks" / path[UUID]("taskId"))
    .out(jsonBody[Task])
    .errorOut(jsonBody[ErrorResponse])
```

Бонус: из этих же описаний Tapir **автоматически** генерирует Swagger UI — документация никогда не расходится с кодом.

### Akka HTTP — сервер

**Проблема:** Нужен HTTP-сервер, который работает с `Future` (стандарт в Akka-экосистеме), а бизнес-логика написана на ZIO.

**Решение:** Akka HTTP выступает как транспортный слой. Tapir конвертирует описание эндпоинтов в Akka-маршруты. Мост `ZIO → Future` выполняет преобразование на границе:

```scala
private def run[A](zio: IO[AppError, A]): Future[Either[ErrorResponse, A]] =
  Unsafe.unsafe { implicit unsafe =>
    runtime.unsafe.runToFuture(zio.mapError(ErrorResponse.fromAppError).either)
  }
```

### Fiber — фоновые задачи

**Проблема:** Нужно периодически чистить старые завершённые задачи, не блокируя основной поток сервера.

**Решение:** ZIO Fiber — это легковесный поток, управляемый рантаймом. Запуск фонового процесса — одна строка:

```scala
backgroundCleanup(taskService).fork
```

`Schedule.fixed(1.hour)` — декларативное расписание повторений без `Thread.sleep` и `ScheduledExecutorService`.

### Correlation ID — трейсинг запросов

**Проблема:** В микросервисной архитектуре один пользовательский запрос проходит через несколько сервисов. Без сквозного идентификатора невозможно собрать логи в единую цепочку.

**Решение:** Middleware добавляет `X-Correlation-Id` к каждому запросу и ответу. Если клиент передал свой ID — используется он, иначе генерируется новый:

```
[a]  [d7e3f1a2-...] POST /api/tasks
[b]  [d7e3f1a2-...] GET /api/tasks/550e8400-...
```

### ADT (Algebraic Data Types) — моделирование домена

**Проблема:** Статус задачи хранится как `String` — можно записать `"TODOO"` и узнать об этом только в рантайме.

**Решение:** `sealed trait` + `case object` — компилятор знает **все** возможные значения и проверяет полноту `match`:

```scala
sealed trait TaskStatus
object TaskStatus {
  case object Todo       extends TaskStatus
  case object InProgress extends TaskStatus
  case object Done       extends TaskStatus
}
```

Если добавить новый статус и забыть обработать его в `match` — компилятор выдаст предупреждение.

## Структура проекта

```
src/main/scala/taskmanager/
├── domain/
│   ├── Task.scala              — Доменные модели, ADT, JSON-кодеки
│   └── AppError.scala          — Типизированная иерархия ошибок
├── service/
│   ├── TaskService.scala       — Trait сервиса + ZIO accessor methods
│   └── InMemoryTaskService.scala — Реализация через Ref (подменяемая в тестах)
├── api/
│   ├── Endpoints.scala         — Tapir-описания эндпоинтов
│   └── Routes.scala            — Server logic + Correlation ID middleware
└── Main.scala                  — Точка входа: сервер + фоновый cleanup fiber
```

## API

| Метод  | Путь               | Описание                  |
|--------|--------------------| --------------------------|
| POST   | /api/tasks         | Создать задачу            |
| GET    | /api/tasks         | Список задач (?status=todo) |
| GET    | /api/tasks/:id     | Получить задачу по ID     |
| PUT    | /api/tasks/:id     | Обновить задачу           |
| DELETE | /api/tasks/:id     | Удалить задачу            |
| GET    | /health            | Healthcheck               |
| GET    | /docs              | Swagger UI                |

## Запуск

```bash
sbt run
```

Сервер стартует на `http://localhost:8080`, Swagger UI доступен на `/docs`.

## Тесты

```bash
sbt test
```

11 тестов покрывают CRUD-операции, валидацию, обработку ошибок и фоновую очистку. Тесты используют `InMemoryTaskService.layer` — работают мгновенно, без внешних зависимостей.

## Пример использования

```bash
# Создать задачу
curl -s -X POST localhost:8080/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"title": "Изучить ZIO", "description": "Подготовка к собеседованию"}' | jq

# Список задач
curl -s localhost:8080/api/tasks | jq

# Фильтр по статусу
curl -s 'localhost:8080/api/tasks?status=todo' | jq

# Обновить статус
curl -s -X PUT localhost:8080/api/tasks/<UUID> \
  -H 'Content-Type: application/json' \
  -d '{"status": "in_progress"}' | jq

# Удалить задачу
curl -s -X DELETE localhost:8080/api/tasks/<UUID>

# Healthcheck
curl localhost:8080/health
```

## Стек

| Технология   | Версия | Назначение                              |
|-------------|--------|-----------------------------------------|
| Scala       | 2.13   | Язык                                    |
| ZIO         | 2.x    | Управление эффектами, конкурентность, DI |
| Tapir       | 1.9    | Type-safe описание API + Swagger        |
| Akka HTTP   | 10.5   | HTTP-сервер                             |
| ZIO JSON    | 0.6    | Сериализация                            |
| ZIO Test    | 2.x    | Тестирование                            |
