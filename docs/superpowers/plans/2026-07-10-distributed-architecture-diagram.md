# Distributed Architecture Diagram Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 현재 구조(Reservation ×3 + Payment ×3, 별도 Consumer 없음)를 보여주는 draw.io 다이어그램 및 PNG를 만든다.

**Architecture:** Nginx가 Reservation 3대로 트래픽을 분산하고, Reservation·Payment가 Kafka choreography로 협업한다. Saga consumer는 각 서비스 프로세스 내부에 있다. Redis, Kafka, DB는 논리 구성요소 하나씩 표시한다.

**Tech Stack:** diagrams.net XML, PowerShell System.Drawing PNG 렌더

---

### Task 1: draw.io 원본 제작

**Files:**
- Create/Update: `docs/distributed-architecture.drawio`

- [x] **Step 1: 서비스와 인프라 배치**

Client, Nginx, Reservation ×3, Kafka, Payment ×3, Redis, Reservation DB, Payment DB를 배치한다. Reservation Consumer 그룹은 넣지 않는다.

- [x] **Step 2: 연결과 라벨 추가**

동기 요청은 실선, Kafka 이벤트는 점선. `payment.result`는 Kafka에서 Reservation으로 되돌아온다.

- [x] **Step 3: draw.io XML 검증**

Expected: `mxfile` 루트, Reservation/Payment 각 3개, Consumer 그룹 없음.

### Task 2: PNG 내보내기

**Files:**
- Create/Update: `docs/distributed-architecture.png`
- Update: `scripts/render-distributed-architecture.ps1`

- [x] **Step 1: PNG 렌더**

`scripts/render-distributed-architecture.ps1`로 동일 레이아웃 PNG 생성.

- [x] **Step 2: 결과 검증**

Expected: PNG 1600×900, Reservation 3 + Payment 3, Consumer 없음.
