package com.booking.payment.gateway

import com.booking.payment.config.PaymentGatewayProperties
import org.springframework.stereotype.Component
import kotlin.random.Random

/**
 * 외부 PG를 흉내 내는 결정적·확률적 실패 주입기.
 *
 * ## 선제 개념
 * - **Chaos / fault injection**: 실 PG 없이 거절·타임아웃·지연을 재현해 Saga 보상 경로를 검증한다.
 * - **결정적 prefix**: E2E/curl에서 `fail-` / `timeout-` userId로 경로를 고정한다 (플래키 테스트 방지).
 * - **확률적 rate**: k6 `payment-failure`처럼 “실패 N%에서도 재고 정합”을 수치로 증명할 때 사용.
 *
 * ## 작업 흐름 (우선순위)
 * ```
 * userId startsWith "fail-"     → Declined (즉시)
 * userId startsWith "timeout-"  → sleep(timeoutMs) → TimedOut
 * random < timeoutRate          → sleep → TimedOut
 * random < failureRate          → Declined
 * else                          → sleep(delayMin..delayMax) → Approved
 * ```
 *
 * ## 트레이드오프
 * - **Thread.sleep**: 호출 스레드(Kafka listener)를 블로킹. 랩·데모용. 실무 PG 클라이언트는
 *   논블로킹/타임아웃 예산·서킷브레이커를 둔다.
 * - **Mock만으로 충분**: 포트폴리오 초점은 “실제 카드사 연동”이 아니라 Saga·보상·멱등이다.
 */
@Component
class MockPaymentGateway(
    private val properties: PaymentGatewayProperties,
    private val random: Random = Random.Default,
) : PaymentGateway {
    override fun approve(request: PaymentRequest): PaymentGatewayResult {
        when {
            request.userId.startsWith(FAIL_PREFIX) ->
                return PaymentGatewayResult.Declined("DECLINED")

            request.userId.startsWith(TIMEOUT_PREFIX) -> {
                Thread.sleep(properties.timeoutMs)
                return PaymentGatewayResult.TimedOut
            }
        }

        if (random.nextDouble() < properties.timeoutRate) {
            Thread.sleep(properties.timeoutMs)
            return PaymentGatewayResult.TimedOut
        }

        if (random.nextDouble() < properties.failureRate) {
            return PaymentGatewayResult.Declined("DECLINED")
        }

        val delayRange = properties.delayMaxMs - properties.delayMinMs
        val delayMs = properties.delayMinMs + if (delayRange > 0) {
            random.nextLong(delayRange + 1)
        } else {
            0L
        }
        if (delayMs > 0) {
            Thread.sleep(delayMs)
        }

        return PaymentGatewayResult.Approved
    }

    companion object {
        const val FAIL_PREFIX = "fail-"
        const val TIMEOUT_PREFIX = "timeout-"
    }
}
