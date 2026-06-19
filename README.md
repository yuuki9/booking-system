# 선착순 이벤트 예약

티켓 예매나 선착순 이벤트처럼 제한된 자원에 대량의 트래픽이 집중되는 상황을 가정하고, 
이벤트마다 최대 인원(`capacity`)이 있고, 예약이 들어올 때마다 남은 자리가 줄어듭니다.  
서버 설정이나 요청마다 **동시 예약 처리 방식**을 바꿔 보면서 동작 차이를 확인할 수 있습니다.

---

## System Architecture

![동시성 예약 시스템 아키텍처](docs/architecture.png)

k6/클라이언트 → Nginx → API 서버 3대 → PostgreSQL · Redis · Kafka.  
`docker compose --profile scale3`로 로컬에서 동일 구조를 재현할 수 있습니다.
