# Reservation Concurrency Test App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Spring Boot Kotlin 예약 앱 + 4종 락 전략 + Kafka + Docker Compose + k6 실험 환경 구축

**Architecture:** 단일 Gradle 프로젝트, Strategy 패턴으로 락 전략 분리, API/Consumer는 Spring Profile로 분리

**Tech Stack:** Spring Boot 3.4, Kotlin, JPA, PostgreSQL, Flyway, Redis, Kafka, Docker Compose, k6

---

### Task 1: Gradle scaffold + application.yml

**Files:**
- Create: `build.gradle.kts`, `settings.gradle.kts`, `application.yml`, Flyway migration

### Task 2: Domain + Repository

**Files:**
- Create: `Event.kt`, `Reservation.kt`, repositories

### Task 3: Lock strategies + ReservationService

**Files:**
- Create: `LockStrategy.kt`, 4 handlers, `ReservationService.kt`

### Task 4: REST API + Exception handling

**Files:**
- Create: Controller, DTOs, `GlobalExceptionHandler.kt`

### Task 5: Kafka producer + consumer

**Files:**
- Create: Publisher, Consumer, Kafka config

### Task 6: Docker Compose + k6 + scripts

**Files:**
- Create: `docker-compose.yml`, `Dockerfile`, nginx, k6 scripts, `reset-lab.sh`

### Task 7: Verify build

Run: `./gradlew build` and document README
