# 분산 아키텍처 다이어그램 디자인

- 작성일: 2026-07-10
- 갱신일: 2026-07-10
- 결과물: `docs/distributed-architecture.drawio`, `docs/distributed-architecture.png`

## 목적

booking-system의 **현재 구조**를 한 장에서 설명한다. Reservation·Payment는 각각 서버 인스턴스 3대로 분산하고, Saga consumer는 별도 서비스가 아니라 각 프로세스 안에서 동작한다. 공용 인프라는 논리 단위로 단순화한다.

## 구성

- Client / k6
- Nginx Load Balancer 1대
- Reservation 인스턴스 3대 (API + `PaymentResultConsumer` + Outbox)
- Payment 인스턴스 3대 (`PendingConsumer` + Mock PG + Outbox)
- Redis 1개 논리 인스턴스
- Kafka 1개 논리 클러스터
- Reservation DB 1개
- Payment DB 1개

애플리케이션 서버는 총 6대다. Nginx는 Reservation 3대로만 요청을 분산한다. Payment는 Kafka 이벤트를 소비하며, `payment.result`는 다시 Reservation 프로세스의 consumer가 처리한다.

Compose의 `reservation-consumer`는 `reservation.confirmed` 로그 데모용이므로 이 그림에서 제외한다.

## 연결

- `Client / k6 → Nginx → Reservation ×3`
- `Reservation ×3 → Reservation DB`
- `Reservation ×3 → Redis`
- `Reservation ×3 → Kafka` (`reservation.pending`)
- `Kafka → Payment ×3` (`pending` / `refund`)
- `Payment ×3 → Kafka` (`payment.result`)
- `Kafka → Reservation ×3` (`payment.result` → confirmed / refund)
- `Payment ×3 → Payment DB`

Kafka 연결에는 대표 이벤트 흐름 `reservation.pending → payment.result → confirmed / refund`를 표시한다.

## 시각 스타일

참고 이미지처럼 흰 배경, 색 구분된 서비스 박스, 점선(Kafka)·실선(요청/DB) 연결선, 서비스별 3개 복제본을 사용한다. Redis는 빨간색, Kafka는 주황색, PostgreSQL은 파란색 데이터베이스 아이콘으로 구분한다.

## 범위

가용 영역, 오토스케일링, DB 복제, Redis Cluster, Kafka broker 수, 로그 전용 `reservation-consumer`는 표현하지 않는다.
